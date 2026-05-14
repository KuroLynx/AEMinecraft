package fr.euclesia.mcarchipelago.engine.ap;

import fr.euclesia.mcarchipelago.archipelago.APConnectionOptions;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.transport.JavaNetWebSocketAPTransport;

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
        ArchipelagoClient client = new ArchipelagoClient(new JavaNetWebSocketAPTransport());
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
