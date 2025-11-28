package org.coredns.container;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Functional interface for the blocking decision logic.
 */
@FunctionalInterface
interface BlockingDecision {
    boolean decide(String ip, String domain);
}

public class IPBlockerSocketServer {
    private static final String SOCKET_PATH = "/tmp/ipblocker.sock";
    private final BlockingDecision blockingDecision;

    public IPBlockerSocketServer(BlockingDecision blockingDecision) {
        this.blockingDecision = blockingDecision;
    }

    public void start() throws Exception {
        System.out.println("CoreDNS IPBlocker Service starting...");

        // Clean up old socket if it exists
        Files.deleteIfExists(Paths.get(SOCKET_PATH));

        // Create UNIX domain socket server
        UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(SOCKET_PATH);
        ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(socketAddress);

        System.out.println("Listening on UNIX socket: " + SOCKET_PATH);

        // Accept connections in a loop
        while (true) {
            // This is a blocking call, waits for a new client connection
            SocketChannel clientChannel = serverChannel.accept();
            // Handle client in a separate, non-blocking way (or use a thread pool for production)
            // For this small utility, direct handling is acceptable since it's a daemon thread
            handleClient(clientChannel); 
        }
    }

    private void handleClient(SocketChannel channel) throws IOException {
        // Use UTF-8 for reading and writing
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8)
        );
        BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8)
        );

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                // Expected format: "ip,domain"
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    String ip = parts[0].trim();
                    String domain = parts[1].trim();

                    // Delegate the decision to the provided logic
                    boolean isBlocked = blockingDecision.decide(ip, domain);

                    System.out.println("Query: " + ip + " -> " + domain + " : " + isBlocked);
                    writer.write(isBlocked ? "true" : "false");
                    writer.newLine();
                    writer.flush();
                } else {
                    // Send an error response for malformed input
                    System.err.println("Malformed input received: " + line);
                    writer.write("false");
                    writer.newLine();
                    writer.flush();
                }
            }
        } finally {
            // Crucial: Close the channel to release resources
            if (channel.isOpen()) {
                channel.close();
            }
        }
    }
}
