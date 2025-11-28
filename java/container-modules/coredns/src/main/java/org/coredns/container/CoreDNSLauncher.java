package org.coredns.container;

import org.rocksdb.RocksDBException;

public class CoreDNSLauncher {
    private static RocksDBWrapper rocksDbWrapper;
    private static SimpleHttpServer httpServer;

    public static void main(String[] args) {
        try {
            // Initialize RocksDB
            initializeRocksDB();

            // Start HTTP Server
            startHttpServer();

            // Start Unix Socket
            startSocketServer();

            // Keep the application running and ensure proper shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down gracefully");
                shutdown();
            }));

            // Wait forever
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void initializeRocksDB() throws RocksDBException {
        // Read path from environment variable or use default
        String dbPath = System.getenv("IPBLOCKER_DB_PATH");
        if (dbPath == null || dbPath.trim().isEmpty()) {
            dbPath = "/etc/coredns/ipblocker_db";
        }
        rocksDbWrapper = new RocksDBWrapper(dbPath);
    }

    private static void startHttpServer() throws Exception {
        // SimpleHttpServer constructor can throw IOException
        httpServer = new SimpleHttpServer(rocksDbWrapper);
        httpServer.start();
    }

    private static void startSocketServer() {

        // Create blocking decision logic that uses the RocksDB database
        BlockingDecision decision = (ip, domain) -> {
            try {
                // isBlocked handles RocksDB lookup and Cache check
                return rocksDbWrapper.isBlocked(domain, ip);
            } catch (Exception e) {
                System.err.println("ERROR: Exception during blocking decision for domain '" + domain
                        + "' and IP '" + ip + "': " + e.getMessage());
                return false; // Fail-open: do not block on error
            }
        };

        // Start socket server in background (daemon) thread, for communicating with the CoreDNS-ipblocker plugin.
        Thread socketThread = new Thread(() -> {
            try {
                new IPBlockerSocketServer(decision).start();
            } catch (Exception e) {
                System.err.println("ERROR: Failed to start socket server: " + e.getMessage());
                System.exit(1);
            }
        }, "IPBlockerSocketServerThread");
        
        socketThread.setDaemon(true); // Daemon thread exits when main thread finishes
        socketThread.start();
    }

    private static void shutdown() {
        try {
            if (httpServer != null) {
                httpServer.stop();
            }
            if (rocksDbWrapper != null) {
                // This also saves the cache file
                rocksDbWrapper.close(); 
            }
        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
        }
    }
}
