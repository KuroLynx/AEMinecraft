package fr.euclesia.mcarchipelago.protocol.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class JavaNetWebSocketAPTransport implements APTransport {
    private final HttpClient httpClient;
    private volatile WebSocket webSocket;

    // java.net.http.WebSocket allows only ONE outstanding sendText: starting another before the
    // previous future completes throws IllegalStateException. Location checks fire in bursts from
    // the server thread (e.g. the on-connect advancement scan), so we serialise every send through
    // this tail — each send is chained onto the completion of the one before it. The tail always
    // resolves successfully (failures are swallowed here) so one failed send can't wedge the queue.
    private final Object sendLock = new Object();
    private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);

    public JavaNetWebSocketAPTransport() {
        this(HttpClient.newHttpClient());
    }

    public JavaNetWebSocketAPTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public CompletableFuture<Void> connect(URI uri, APTransportListener listener) {
        return httpClient.newWebSocketBuilder()
                .buildAsync(uri, new Listener(listener))
                .thenAccept(socket -> this.webSocket = socket);
    }

    @Override
    public CompletableFuture<Void> send(String message) {
        synchronized (sendLock) {
            CompletableFuture<Void> result = sendTail.thenCompose(ignored -> {
                WebSocket socket = this.webSocket;
                if (socket == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("AP WebSocket is not connected"));
                }
                return socket.sendText(message, true).thenApply(sent -> null);
            });
            // Advance the tail with a never-failing view so a single send failure doesn't block the rest.
            sendTail = result.exceptionally(ignored -> null);
            return result;
        }
    }

    @Override
    public boolean isOpen() {
        return webSocket != null && !webSocket.isInputClosed() && !webSocket.isOutputClosed();
    }

    @Override
    public void close() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing Archipelago transport");
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final APTransportListener listener;
        private final StringBuilder textBuffer = new StringBuilder();

        private Listener(APTransportListener listener) {
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            listener.onOpen();
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                listener.onText(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            listener.onError(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            listener.onClose(statusCode, reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }
}
