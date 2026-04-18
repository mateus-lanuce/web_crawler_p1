package com.crawler.common;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

public class Config {
    private static final Properties props = new Properties();

    static {
        try (BufferedReader br = new BufferedReader(new FileReader("config.txt"))) {
            props.load(br);
            System.out.println("Configuracoes carregadas de config.txt");
        } catch (IOException e) {
            System.out.println("Arquivo config.txt nao encontrado. Usando variaveis de ambiente ou padroes.");
        }
    }

    public static String get(String key, String defaultValue) {
        // Variaveis de ambiente tem prioridade, depois o arquivo txt, depois o padrao
        String env = System.getenv(key);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return props.getProperty(key, defaultValue).trim();
    }

    public static int getInt(String key, int defaultValue) {
        String val = get(key, null);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
