package fr.euclesia.mcarchipelago.protocol.transport;

import fr.euclesia.mcarchipelago.AEMDebug;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.IExtension;
import org.java_websocket.extensions.permessage_deflate.PerMessageDeflateExtension;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.IProtocol;
import org.java_websocket.protocols.Protocol;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * WebSocket transport backed by the Java-WebSocket library. Unlike {@code java.net.http.WebSocket}
 * it negotiates the {@code permessage-deflate} extension, so the AP server can compress traffic
 * (it falls back to uncompressed automatically when the server doesn't agree to the extension).
 * The library also queues outgoing frames internally, so {@link #send} is safe to call from any
 * thread without the one-send-at-a-time restriction of the JDK client.
 */
public final class JavaWebSocketAPTransport implements APTransport {
    private volatile WebSocketClient client;

    @Override
    public CompletableFuture<Void> connect(URI uri, APTransportListener listener) {
        AEMDebug.log("transport.connect uri={} scheme={}", uri, uri.getScheme());
        CompletableFuture<Void> opened = new CompletableFuture<>();

        // Offer permessage-deflate; keep the default empty subprotocol so the handshake matches a
        // server that advertises no subprotocol (the same behaviour as the bare Draft_6455()).
        List<IExtension> extensions = List.of(new PerMessageDeflateExtension());
        List<IProtocol> protocols = List.of(new Protocol(""));
        Draft_6455 draft = new Draft_6455(extensions, protocols);

        WebSocketClient socket = new WebSocketClient(uri, draft) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                AEMDebug.log("transport.onOpen status={} {}", handshake.getHttpStatus(), uri);
                listener.onOpen();
                opened.complete(null);
            }

            @Override
            public void onMessage(String message) {
                AEMDebug.log("transport.recv {} chars: {}", message.length(), AEMDebug.truncate(message));
                listener.onText(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                AEMDebug.log("transport.onClose code={} reason='{}' remote={} opened={}",
                        code, reason, remote, opened.isDone());
                // A close before we ever opened fails the connect future so the caller can fall back
                // (wss:// -> ws://). Once opened, this is a normal disconnect handled by the listener.
                if (!opened.isDone()) {
                    opened.completeExceptionally(new IllegalStateException(
                            "AP WebSocket closed before opening: " + code + " " + reason));
                }
                listener.onClose(code, reason);
            }

            @Override
            public void onError(Exception exception) {
                AEMDebug.log("transport.onError {}", exception.toString());
                opened.completeExceptionally(exception);
                listener.onError(exception);
            }
        };

        // For wss:// use the default SSL context (validates certificates, matching the previous
        // transport): an invalid cert fails the connect future and the caller falls back to ws://.
        if ("wss".equalsIgnoreCase(uri.getScheme())) {
            try {
                socket.setSocketFactory(SSLContext.getDefault().getSocketFactory());
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        this.client = socket;
        socket.connect();
        return opened;
    }

    @Override
    public CompletableFuture<Void> send(String message) {
        WebSocketClient socket = this.client;
        if (socket == null || !socket.isOpen()) {
            AEMDebug.log("transport.send dropped (not connected): {}", AEMDebug.truncate(message));
            return CompletableFuture.failedFuture(new IllegalStateException("AP WebSocket is not connected"));
        }
        try {
            AEMDebug.log("transport.send {} chars: {}", message.length(), AEMDebug.truncate(message));
            socket.send(message);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException exception) {
            AEMDebug.log("transport.send failed: {}", exception.toString());
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public boolean isOpen() {
        WebSocketClient socket = this.client;
        return socket != null && socket.isOpen();
    }

    @Override
    public void close() {
        WebSocketClient socket = this.client;
        if (socket != null) {
            socket.close();
        }
    }
}
