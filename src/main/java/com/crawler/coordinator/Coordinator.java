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

public class Coordinator {
    private final BlockingQueue<String> urlQueue = new LinkedBlockingQueue<>();
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    public Coordinator() {
        // Inicializar com uma semente
        String seedUrl = "page1.com";
        urlQueue.add(seedUrl);
        visitedUrls.add(seedUrl);
        System.out.println("Semente adicionada: " + seedUrl);
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
                        } else if (isFinished()) {
                            out.println(Protocol.SHUTDOWN_CMD);
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
                        if (fromIndex != -1) {
                            String linksStr = data.substring(0, fromIndex).trim();
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
