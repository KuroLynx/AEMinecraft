package fr.euclesia.mcarchipelago.protocol.transport;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

public interface APTransport extends AutoCloseable {
    CompletableFuture<Void> connect(URI uri, APTransportListener listener);

    CompletableFuture<Void> send(String message);

    boolean isOpen();

    @Override
    void close();
}
