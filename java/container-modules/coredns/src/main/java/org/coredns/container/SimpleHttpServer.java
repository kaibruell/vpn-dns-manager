package org.coredns.container;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class SimpleHttpServer {
    private final HttpServer httpServer;
    private final RocksDBWrapper rocksDbWrapper;

    private static final String ADDRESS = (System.getenv("IPBLOCKER_REST_URL") != null ? System.getenv("IPBLOCKER_REST_URL") : "0.0.0.0");
    private static final int PORT = (System.getenv("IPBLOCKER_REST_PORT") != null ? Integer.parseInt(System.getenv("IPBLOCKER_REST_PORT")) : 8080);


    public SimpleHttpServer(RocksDBWrapper rocksDbWrapper) throws IOException {
        this.rocksDbWrapper = rocksDbWrapper;
        // Bind to 0.0.0.0 to listen on all interfaces
        this.httpServer = HttpServer.create(new InetSocketAddress(ADDRESS, PORT), 0);

        // Use a fixed-size thread pool for handling requests
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        httpServer.setExecutor(executor);

        // Register endpoints
        httpServer.createContext("/api/list/upload", new UploadListHandler());
        // Updated handler for list assignment/reassignment (replaces old ListAssignmentHandler)
        httpServer.createContext("/api/list/assignment", new ListReassignmentHandler()); 
        httpServer.createContext("/api/list/cleanup", new CleanupHandler());
        httpServer.createContext("/api/domain/check", new CheckDomainHandler());
    }

    public void start() {
        httpServer.start();
        System.out.println("HTTP Server started on port " + PORT);
    }

    public void stop() {
        // Stop immediately
        httpServer.stop(0);
    }

    /**
     * POST /api/list/upload?listId=us_ads&ip=192.168.1.1&listType=BLOCK
     * Imports a list and assigns it to an IP with a specified type.
     */
    private class UploadListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            try (InputStream requestBody = exchange.getRequestBody()) {
                String query = exchange.getRequestURI().getQuery();
                String listId = getParam(query, "listId");
                String ip = getParam(query, "ip");
                String typeStr = getParam(query, "listType");

                if (listId == null || listId.isEmpty() || ip == null || ip.isEmpty() || typeStr == null || typeStr.isEmpty()) {
                    sendError(exchange, 400, "Missing parameters: listId, ip, and listType are required");
                    return;
                }
                
                ListType listType;
                try {
                    listType = ListType.valueOf(typeStr.toUpperCase());
                    if (listType == ListType.NONE) {
                        sendError(exchange, 400, "listType cannot be 'NONE' for upload. Use 'BLOCK' or 'WHITE'.");
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    sendError(exchange, 400, "Invalid listType. Must be 'BLOCK' or 'WHITE'.");
                    return;
                }

                // The request body contains the file stream
                int importedCount = rocksDbWrapper.importListFromStreamAndAssign(listId, requestBody, ip, listType);
                
                String response = String.format(
                    "List '%s' (%s) successfully imported (%d domains) and assigned to IP %s.",
                    listId, listType.name(), importedCount, ip
                );
                sendResponse(exchange, 200, response);
            } catch (Exception e) {
                System.err.println("Error during upload: " + e.getMessage());
                e.printStackTrace();
                sendError(exchange, 500, "Error during upload: " + e.getMessage());
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * PUT /api/list/assignment?listId=us_ads&ip=192.168.1.1&listType=BLOCK
     * Assigns or updates a list to/from an IP with a specific type (BLOCK or WHITE).
     *
     * DELETE /api/list/assignment?listId=us_ads&ip=192.168.1.1
     * Unassigns a list from an IP.
     */
    private class ListReassignmentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if ("PUT".equals(method)) {
                handlePutAssignment(exchange);
            } else if ("DELETE".equals(method)) {
                handleDeleteAssignment(exchange);
            } else {
                sendError(exchange, 405, "Method not allowed. Use PUT for assignment or DELETE for unassignment.");
            }
        }

        private void handlePutAssignment(HttpExchange exchange) throws IOException {
            try {
                String query = exchange.getRequestURI().getQuery();
                String listId = getParam(query, "listId");
                String ip = getParam(query, "ip");
                String typeStr = getParam(query, "listType");

                // PUT requires listId, ip, and listType (BLOCK or WHITE)
                if (listId == null || listId.isEmpty() || ip == null || ip.isEmpty() || typeStr == null || typeStr.isEmpty()) {
                    sendError(exchange, 400, "PUT requires parameters: listId, ip, and listType (BLOCK or WHITE)");
                    return;
                }

                ListType listType;
                try {
                    listType = ListType.valueOf(typeStr.toUpperCase());
                    if (listType == ListType.NONE) {
                        sendError(exchange, 400, "PUT: listType cannot be 'NONE'. Use DELETE to unassign.");
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    sendError(exchange, 400, "Invalid listType. Must be 'BLOCK' or 'WHITE'.");
                    return;
                }

                ListAssignmentCache cache = rocksDbWrapper.getAssignmentCache();
                boolean changed = cache.reassignList(listId, ip, listType);

                if (changed) {
                    String response = String.format("List '%s' successfully assigned to IP %s with type %s.", listId, ip, listType.name());
                    sendResponse(exchange, 200, response);
                } else {
                    String response = String.format("List '%s' already assigned to IP %s with type %s (no change needed).", listId, ip, listType.name());
                    sendResponse(exchange, 200, response);
                }

            } catch (Exception e) {
                System.err.println("Error during PUT assignment: " + e.getMessage());
                sendError(exchange, 500, "Error during PUT assignment: " + e.getMessage());
            } finally {
                exchange.close();
            }
        }

        private void handleDeleteAssignment(HttpExchange exchange) throws IOException {
            try {
                String query = exchange.getRequestURI().getQuery();
                String listId = getParam(query, "listId");
                String ip = getParam(query, "ip");

                // DELETE requires listId and ip only
                if (listId == null || listId.isEmpty() || ip == null || ip.isEmpty()) {
                    sendError(exchange, 400, "DELETE requires parameters: listId and ip");
                    return;
                }

                ListAssignmentCache cache = rocksDbWrapper.getAssignmentCache();
                boolean changed = cache.reassignList(listId, ip, ListType.NONE);

                if (changed) {
                    String response = String.format("List '%s' successfully unassigned from IP %s.", listId, ip);
                    sendResponse(exchange, 200, response);
                } else {
                    String response = String.format("List '%s' was not assigned to IP %s (no change needed).", listId, ip);
                    sendResponse(exchange, 200, response);
                }

            } catch (Exception e) {
                System.err.println("Error during DELETE assignment: " + e.getMessage());
                sendError(exchange, 500, "Error during DELETE assignment: " + e.getMessage());
            } finally {
                exchange.close();
            }
        }
    }


    // POST /api/list/cleanup
    private class CleanupHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            try {
                // This call is synchronized in RocksDBWrapper
                rocksDbWrapper.cleanupUnassignedDomains();
                String response = "Cleanup of unassigned domains completed.";
                sendResponse(exchange, 200, response);
            } catch (Exception e) {
                System.err.println("Error during cleanup: " + e.getMessage());
                sendError(exchange, 500, "Error during cleanup: " + e.getMessage());
            } finally {
                exchange.close();
            }
        }
    }

    // GET /api/domain/check?domain=badsite.com&ip=192.168.1.1
    private class CheckDomainHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            try {
                String query = exchange.getRequestURI().getQuery();
                String domain = getParam(query, "domain");
                String ip = getParam(query, "ip");

                // Even if parameters are missing, we must respond valid JSON and not crash
                if (domain == null || domain.isEmpty() || ip == null || ip.isEmpty()) {
                    sendJsonResponse(exchange, 200, "false");
                    return;
                }

                boolean isBlocked = rocksDbWrapper.isBlocked(domain, ip);
                sendJsonResponse(exchange, 200, String.valueOf(isBlocked));
            } catch (Exception e) {
                System.err.println("Error during checkDomain: " + e.getMessage());
                // Crucial: Respond 'false' on error to fail open (do not block)
                sendJsonResponse(exchange, 200, "false");
            } finally {
                exchange.close();
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String error) throws IOException {
        sendResponse(exchange, statusCode, error);
    }

    private String getParam(String query, String name) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try {
                    // Use StandardCharsets for decoding
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    return kv[1]; // Fallback, though should not happen with UTF-8
                }
            }
        }
        return null;
    }
}
