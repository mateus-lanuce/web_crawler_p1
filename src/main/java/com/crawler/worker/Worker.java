package com.crawler.worker;

import com.crawler.common.Config;
import com.crawler.common.Protocol;

import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Worker {
    private final String coordinatorHost;
    private final int coordinatorPort;
    private final String dataServerHost;
    private final int dataServerPort;
    private final ExecutorService executor;
    private final int numThreads;
    private final Semaphore semaphore;

    public Worker(String coordinatorHost, int coordinatorPort, String dataServerHost, int dataServerPort, int numThreads) {
        this.coordinatorHost = coordinatorHost;
        this.coordinatorPort = coordinatorPort;
        this.dataServerHost = dataServerHost;
        this.dataServerPort = dataServerPort;
        this.numThreads = numThreads;
        this.executor = Executors.newFixedThreadPool(numThreads);
        this.semaphore = new Semaphore(numThreads);
    }

    public void start() {
        System.out.println("Worker iniciado com " + numThreads + " threads.");
        while (true) {
            try (Socket coordSocket = new Socket(coordinatorHost, coordinatorPort);
                    BufferedReader coordIn = new BufferedReader(new InputStreamReader(coordSocket.getInputStream()));
                    PrintWriter coordOut = new PrintWriter(coordSocket.getOutputStream(), true)) {

                // Registrar capacidade do worker
                coordOut.println(Protocol.REGISTER_CMD + " " + numThreads);

                while (true) {
                    // Só pede trabalho se tiver uma thread livre (capacidade)
                    semaphore.acquire();

                    // comando só é enviado quando há uma thread realmente livre
                    // Isso garante que o coordenador não sobrecarregue a memoria RAM do worker,
                    // tornando possivel escalar para milhares de workers com N capacidades
                    // diferentes.
                    coordOut.println(Protocol.WORKER_READY);
                    String command = coordIn.readLine();

                    if (command == null || command.equals(Protocol.SHUTDOWN_CMD)) {
                        System.out.println("Worker recebendo shutdown.");
                        executor.shutdownNow(); // Para as threads do pool
                        System.exit(0); // Força a saída do processo para encerrar o container/máquina
                        return;
                    } else if (command.startsWith(Protocol.TASK_CMD)) {
                        String url = command.substring(Protocol.TASK_CMD.length()).trim();
                        System.out.println("Processando URL: " + url);

                        // Dispara a tarefa no ExecutorService local
                        executor.submit(() -> {
                            try {
                                processUrl(url, coordOut);
                            } finally {
                                semaphore.release(); // Libera a vaga para pedir novo trabalho
                            }
                        });
                    } else if (command.equals("WAIT")) {
                        semaphore.release(); // Não pegou tarefa, devolve permissão
                        Thread.sleep(1000);
                    } else {
                        semaphore.release(); // Fallback de segurança
                    }
                }
            } catch (Exception e) {
                System.err.println("Worker aguardando conexão com Coordenador: " + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private void processUrl(String url, PrintWriter coordOut) {
        int retries = 5;
        Socket dataSocket = null;

        while (dataSocket == null && retries > 0) {
            try {
                dataSocket = new Socket(dataServerHost, dataServerPort);
            } catch (IOException e) {
                retries--;
                if (retries == 0) {
                    System.err.println("Erro final ao conectar no DataServer para " + url + ": " + e.getMessage());
                    synchronized (coordOut) {
                        coordOut.println(Protocol.FOUND_CMD + "  FROM " + url + " CATEGORY ERRO");
                    }
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }

        try (BufferedReader dataIn = new BufferedReader(new InputStreamReader(dataSocket.getInputStream()));
                PrintWriter dataOut = new PrintWriter(dataSocket.getOutputStream(), true)) {

            dataOut.println(Protocol.GET_CMD + " " + url);
            String response = dataIn.readLine();
            System.out.println("Resposta do DataServer para " + url + ": "
                    + (response != null ? response.substring(0, Math.min(50, response.length())) : "null"));

            if (response != null && response.startsWith(Protocol.LINKS_RESP)) {
                int textIndex = response.indexOf(" TEXT: ");
                String linksStr = "";
                String textStr = "";

                if (textIndex != -1) {
                    linksStr = response.substring(Protocol.LINKS_RESP.length(), textIndex).trim();
                    textStr = response.substring(textIndex + " TEXT: ".length()).trim();
                } else {
                    linksStr = response.substring(Protocol.LINKS_RESP.length()).trim();
                }

                // Validação e processamento com Streams
                Predicate<String> isValidLink = link -> !link.equals("invalid-link");
                Predicate<String> isNotSelfRef = link -> !link.equals(url); // Remove auto-referência

                List<String> validLinks = Arrays.stream(linksStr.split(","))
                        .map(String::trim)
                        .filter(l -> !l.isEmpty())
                        .filter(isValidLink)
                        .filter(isNotSelfRef)
                        .collect(Collectors.toList());

                // Classificacao usando Predicate no texto da página
                Predicate<String> isSports = t -> t.toLowerCase().contains("esporte")
                        || t.toLowerCase().contains("futebol") || t.toLowerCase().contains("basquete");
                String category = isSports.test(textStr) ? "ESPORTES" : "GERAL";

                String foundLinksStr = String.join(",", validLinks);
                String msg = Protocol.FOUND_CMD + " " + foundLinksStr + " FROM " + url + " CATEGORY " + category;

                // Sincronizar envio para o socket compartilhado do coordenador
                synchronized (coordOut) {
                    coordOut.println(msg);
                }
            } else {
                synchronized (coordOut) {
                    coordOut.println(Protocol.FOUND_CMD + "  FROM " + url + " CATEGORY GERAL");
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao processar " + url + ": " + e.getMessage());
            synchronized (coordOut) {
                coordOut.println(Protocol.FOUND_CMD + "  FROM " + url + " CATEGORY ERRO");
            }
        } finally {
            try {
                if (dataSocket != null)
                    dataSocket.close();
            } catch (IOException e) {
            }
        }
    }

    public static void main(String[] args) {
        String dataHost = Config.get("DATA_HOST", "localhost");
        int dataPort = Config.getInt("DATA_PORT", 8081);
        String coordHost = Config.get("COORD_HOST", "localhost");
        int coordPort = Config.getInt("COORD_PORT", 8080);
        int numThreads = Config.getInt("WORKER_THREADS", 4);
        
        new Worker(coordHost, coordPort, dataHost, dataPort, numThreads).start();
    }
}
