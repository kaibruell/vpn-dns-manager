package org.wireguard.container;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class WireGuardHttpServer {
    private final HttpServer httpServer;
    private final Gson gson = new com.google.gson.GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private static final String ADDRESS = (System.getenv("WG_MANAGER_REST_URL") != null ? System.getenv("WG_MANAGER_REST_URL") : "0.0.0.0");
    private static final int PORT = (System.getenv("WG_MANAGER_REST_PORT") != null ? Integer.parseInt(System.getenv("WG_MANAGER_REST_PORT")) : 8080);

    public WireGuardHttpServer() throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress(ADDRESS, PORT), 0);

        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        httpServer.setExecutor(executor);

        // Register endpoints
        httpServer.createContext("/api/service/start", new StartServiceHandler());
        httpServer.createContext("/api/service/stop", new StopServiceHandler());
        httpServer.createContext("/api/peers", new GetPeersHandler());
        httpServer.createContext("/api/peers/add", new AddPeerHandler());
        httpServer.createContext("/api/peers/remove", new RemovePeerHandler());
    }

    public void start() {
        httpServer.start();
        System.out.println("WireGuard HTTP Server started on " + ADDRESS + ":" + PORT);
    }

    public void stop() {
        httpServer.stop(0);
    }

    /**
     * POST /api/service/start
     * Starts the WireGuard service.
     */
    private class StartServiceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed. Use POST.");
                return;
            }

            try {
                WireGuardManager.ServiceStatus status = WireGuardManager.start();
                String response = gson.toJson(new ServiceStatusResponse(status));
                sendJsonResponse(exchange, 200, response);
            } catch (Exception e) {
                System.err.println("Error starting WireGuard: " + e.getMessage());
                e.printStackTrace();
                sendError(exchange, 500, "An internal server error occurred while starting the service.");
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * POST /api/service/stop
     * Stops the WireGuard service.
     */
    private class StopServiceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed. Use POST.");
                return;
            }

            try {
                WireGuardManager.ServiceStatus status = WireGuardManager.stop();
                String response = gson.toJson(new ServiceStatusResponse(status));
                sendJsonResponse(exchange, 200, response);
            } catch (Exception e) {
                System.err.println("Error stopping WireGuard: " + e.getMessage());
                e.printStackTrace();
                sendError(exchange, 500, "An internal server error occurred while stopping the service.");
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * GET /api/peers
     * Retrieves all connected peers.
     */
    private class GetPeersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed. Use GET.");
                return;
            }

            try {
                var peers = WireGuardManager.getClients();
                String response = gson.toJson(peers);
                sendJsonResponse(exchange, 200, response);
            } catch (Exception e) {
                System.err.println("Error retrieving peers: " + e.getMessage());
                e.printStackTrace();
                sendError(exchange, 500, "An internal server error occurred while retrieving peers.");
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * POST /api/peers/add?name=peer1&ip=10.13.13.2
     * Adds a new peer to WireGuard.
     * Returns full PeerDetails including config and keys.
     */
    private class AddPeerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed. Use POST.");
                return;
            }

            try {
                String query = exchange.getRequestURI().getQuery();
                String name = getParam(query, "name");
                String ip = getParam(query, "ip");

                if (name == null || name.isEmpty()) {
                    sendError(exchange, 400, "Missing required parameter: name");
                    return;
                }

                WireGuardManager.PeerDetails peerDetails = WireGuardManager.addPeer(name, ip);
                String response = gson.toJson(peerDetails);
                sendJsonResponse(exchange, 200, response);
            } catch (RuntimeException e) {
                // Known logic errors (e.g. Peer already exists) can be returned to client
                System.err.println("Client error adding peer: " + e.getMessage());
                sendError(exchange, 400, e.getMessage());
            } catch (Exception e) {
                // Unexpected errors
                System.err.println("Unexpected error adding peer: " + e.getMessage());
                e.printStackTrace();
                sendError(exchange, 500, "An internal server error occurred while adding the peer.");
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * POST /api/peers/remove?name=peer1
     * Removes a peer from WireGuard.
     */
    private class RemovePeerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed. Use POST.");
                return;
            }

            try {
                String query = exchange.getRequestURI().getQuery();
                String name = getParam(query, "name");

                if (name == null || name.isEmpty()) {
                    sendError(exchange, 400, "Missing required parameter: name");
                    return;
                }

                WireGuardManager.removePeer(name);
                String response = "{\"success\":true}";
                sendJsonResponse(exchange, 200, response);
            } catch (RuntimeException e) {
                // Known logic errors
                System.err.println("Client error removing peer: " + e.getMessage());
                sendError(exchange, 400, e.getMessage());
            } catch (Exception e) {
                // Unexpected errors
                System.err.println("Unexpected error removing peer: " + e.getMessage());
                e.printStackTrace();
                sendError(exchange, 500, "An internal server error occurred while removing the peer.");
            } finally {
                exchange.close();
            }
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String error) throws IOException {
        String response = gson.toJson(new ErrorResponse(error));
        sendJsonResponse(exchange, statusCode, response);
    }

    private String getParam(String query, String name) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    // Response DTOs
    private static class ServiceStatusResponse {
        public String status;

        ServiceStatusResponse(WireGuardManager.ServiceStatus status) {
            this.status = status.name();
        }
    }

    private static class ErrorResponse {
        public String error;

        ErrorResponse(String error) {
            this.error = error;
        }
    }
}
