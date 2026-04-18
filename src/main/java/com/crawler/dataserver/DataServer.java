package com.crawler.dataserver;

import com.crawler.common.Config;
import com.crawler.common.Protocol;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DataServer {

    public static class PageData {
        public final String text;
        public final String links;

        public PageData(String text, String links) {
            this.text = text;
            this.links = links;
        }
    }

    private static final Map<String, PageData> internetMock = new HashMap<>();

    public static void main(String[] args) {
        loadDataFromCsv("mock_internet.csv");

        int port = Config.getInt("DATA_PORT", 8081);
        System.out.println("Iniciando DataServer na porta " + port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            ExecutorService executor = Executors.newCachedThreadPool();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(new DataWorker(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadDataFromCsv(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("Arquivo de mock não encontrado: " + filePath);
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Remover BOM (causado pelo powershell que coloca caracteres invisiveis no
                // inicio do arquivo) se presente
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                String[] parts = line.split(";", -1); // -1 to keep trailing empty strings
                if (parts.length >= 2) {
                    String id = parts[0].trim();
                    String text = parts[1].trim();
                    String links = parts.length > 2 ? parts[2].trim() : "";
                    internetMock.put(id, new PageData(text, links));
                }
            }
            System.out.println("Dados mockados carregados com sucesso. Total de paginas: " + internetMock.size());
        } catch (IOException e) {
            System.err.println("Erro ao ler arquivo de mock: " + e.getMessage());
        }
    }

    private static class DataWorker implements Runnable {
        private final Socket socket;

        public DataWorker(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                String request = in.readLine();
                if (request != null && request.startsWith(Protocol.GET_CMD)) {
                    // Protocol: GET google.com
                    String[] parts = request.split(" ");
                    if (parts.length > 1) {
                        String url = parts[1].trim();
                        PageData data = internetMock.getOrDefault(url, new PageData("", ""));
                        // Resposta: LINKS: l1,l2 TEXT: text
                        out.println(Protocol.LINKS_RESP + " " + data.links + " TEXT: " + data.text);
                        System.out.println("DataServer atendeu GET " + url);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
