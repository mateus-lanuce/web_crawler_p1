package com.crawler.common;

public class Protocol {
    // Data Server Commands
    public static final String GET_CMD = "GET";
    public static final String LINKS_RESP = "LINKS:";

    // Coordinator Commands
    public static final String REGISTER_CMD = "REGISTER";
    public static final String WORKER_READY = "READY";
    public static final String TASK_CMD = "TASK";
    public static final String FOUND_CMD = "FOUND:";
    public static final String SHUTDOWN_CMD = "SHUTDOWN";
}
