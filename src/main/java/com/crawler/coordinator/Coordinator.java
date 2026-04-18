package com.crawler.coordinator;

import com.crawler.common.Config;
import com.crawler.common.Protocol;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Coordinator {
    private final BlockingQueue<String> urlQueue = new LinkedBlockingQueue<>();
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private static final Logger logger = Logger.getLogger("CrawlerCoordinator");

    static {
        try {
            // Overwrite log file on each execution (append = false)
            FileHandler fh = new FileHandler("coordinator.log", false);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Coordinator() {
        // Inicializar com uma semente
        String seedUrl = "page1.com";
        urlQueue.add(seedUrl);
        visitedUrls.add(seedUrl);
        System.out.println("Semente adicionada: " + seedUrl);
        logger.info("INICIO DO CRAWLER - Semente: " + seedUrl);
    }

    public void start() {
        int port = Config.getInt("COORD_PORT", 8080);
        System.out.println("Iniciando Coordinator na porta " + port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket workerSocket = serverSocket.accept();
                new Thread(new WorkerHandler(workerSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private synchronized boolean isFinished() {
        return urlQueue.isEmpty() && activeWorkers.get() == 0;
    }

    private class WorkerHandler implements Runnable {
        private final Socket socket;

        public WorkerHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                while (true) {
                    String msg = in.readLine();
                    if (msg == null)
                        break;

                    if (msg.startsWith(Protocol.REGISTER_CMD)) {
                        String[] parts = msg.split(" ");
                        int threads = 0;
                        if (parts.length > 1) {
                            try {
                                threads = Integer.parseInt(parts[1].trim());
                            } catch (NumberFormatException ignored) {}
                        }
                        System.out.println("Worker " + socket.getInetAddress() + ":" + socket.getPort() + " conectou-se informando capacidade de " + threads + " threads.");
                        logger.info("Worker conectado [" + socket.getInetAddress() + ":" + socket.getPort() + "] - Capacidade: " + threads + " threads.");
                    } else if (msg.startsWith(Protocol.WORKER_READY)) {
                        String url = null;

                        // Esperar um pouco para que tarefas em processamento alimentem a fila se
                        // estiver momentaneamente vazia
                        int retries = 5;
                        while (retries > 0 && !isFinished() && url == null) {
                            url = urlQueue.poll();
                            if (url == null) {
                                Thread.sleep(500);
                                retries--;
                            }
                        }

                        if (url != null) {
                            activeWorkers.incrementAndGet();
                            out.println(Protocol.TASK_CMD + " " + url);
                            logger.info("Worker [" + socket.getInetAddress() + ":" + socket.getPort() + "] recebeu link para processar: " + url);
                        } else if (isFinished()) {
                            out.println(Protocol.SHUTDOWN_CMD);
                            logger.info("Enviando SHUTDOWN para Worker [" + socket.getInetAddress() + ":" + socket.getPort() + "] - Fila vazia.");
                            break;
                        } else {
                            // Se chegou aqui mas nao achou URL, pode tentar de novo depois
                            out.println("WAIT");
                            Thread.sleep(1000);
                        }
                    } else if (msg.startsWith(Protocol.FOUND_CMD)) {
                        System.out.println("Coordenador recebeu FOUND: " + msg);
                        // Protocol: FOUND: link1,link2 FROM url CATEGORY category
                        String data = msg.substring(Protocol.FOUND_CMD.length()).trim();
                        int fromIndex = data.indexOf("FROM");
                        int categoryIndex = data.indexOf("CATEGORY");

                        String linksStr = "";
                        String sourceUrl = "desconhecida";
                        String category = "GERAL";

                        if (fromIndex != -1 && categoryIndex != -1) {
                            linksStr = data.substring(0, fromIndex).trim();
                            sourceUrl = data.substring(fromIndex + 4, categoryIndex).trim();
                            category = data.substring(categoryIndex + 8).trim();
                        } else if (fromIndex != -1) {
                            linksStr = data.substring(0, fromIndex).trim();
                            sourceUrl = data.substring(fromIndex + 4).trim();
                        }

                        int numLinks = linksStr.isEmpty() ? 0 : linksStr.split(",").length;
                        logger.info("Retorno do Worker [" + socket.getInetAddress() + ":" + socket.getPort() + "] sobre link: " + sourceUrl + " | Categoria: " + category + " | Novos links encontrados: " + numLinks);

                        if (!linksStr.isEmpty()) {
                            String[] urlList = linksStr.split(",");
                            System.out.println("Adicionando " + urlList.length + " links vindos de " + linksStr.substring(0, Math.min(20, linksStr.length())) + "...");
                            for (String link : urlList) {
                                String trimmed = link.trim();
                                if (!trimmed.isEmpty() && visitedUrls.add(trimmed)) {
                                    urlQueue.add(trimmed);
                                }
                            }
                        } else {
                            System.out.println("Nenhum link encontrado na resposta.");
                        }
                        activeWorkers.decrementAndGet();
                        System.out.println("Processamento concluído. Fila atual: " + urlQueue.size());
                    }
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Conexão com worker perdida.");
            }
        }
    }

    public static void main(String[] args) {
        new Coordinator().start();
    }
}
