package fr.euclesia.mcarchipelago.engine.ap;

import fr.euclesia.mcarchipelago.archipelago.APConnectionOptions;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.transport.JavaWebSocketAPTransport;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

public final class ArchipelagoService {
    private final ArchipelagoClient client;
    private final ArchipelagoGateway gateway;

    private ArchipelagoService(ArchipelagoClient client, ArchipelagoGateway gateway) {
        this.client = client;
        this.gateway = gateway;
    }

    public static ArchipelagoService createDefault() {
        // Uses the Java-WebSocket transport (permessage-deflate). The JDK-based
        // JavaNetWebSocketAPTransport remains as an uncompressed fallback if needed.
        ArchipelagoClient client = new ArchipelagoClient(new JavaWebSocketAPTransport());
        return new ArchipelagoService(client, new APArchipelagoGateway(client));
    }

    public CompletableFuture<Void> connect(URI uri, APConnectionOptions options) {
        return client.connect(uri, options);
    }

    public ArchipelagoClient client() {
        return client;
    }

    public ArchipelagoGateway gateway() {
        return gateway;
    }
}
