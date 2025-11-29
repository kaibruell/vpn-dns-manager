package org.wireguard.container;

public class WireGuardLauncher {

    public static void main(String[] args) throws Exception {
        // Check WG_AUTOSTART environment variable
        String autostart = System.getenv("WG_AUTOSTART");
        if ("true".equalsIgnoreCase(autostart)) {
            System.out.println("WG_AUTOSTART enabled. Starting WireGuard...");
            WireGuardManager.ServiceStatus status = WireGuardManager.start();
            System.out.println("WireGuard start result: " + status);
        }

        System.out.println("Starting WireGuard Manager REST API...");

        WireGuardHttpServer server = new WireGuardHttpServer();
        server.start();

        Thread.sleep(Long.MAX_VALUE); // Keep process alive
    }
}
