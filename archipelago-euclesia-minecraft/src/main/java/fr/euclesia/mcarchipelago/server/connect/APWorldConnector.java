package fr.euclesia.mcarchipelago.server.connect;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.APConnectionOptions;
import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Establishes the Archipelago session for a world as it starts, blocking the caller until the
 * handshake succeeds or fails. Used by the connect-on-join gate so a world cannot be entered without
 * a live session. Mirrors the address handling of the client connect screen ({@code wss://} then
 * {@code ws://} fallback).
 */
public final class APWorldConnector {
    /** One attempt at a time: two sockets racing would overwrite each other in the transport. */
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    /** The join-time retry fires once per lost session; a successful connect re-arms it. */
    private static final AtomicBoolean JOIN_RETRY_ARMED = new AtomicBoolean(true);

    private APWorldConnector() {}

    /**
     * {@link #connectBlocking} on its own thread, so a 20-second handshake never stalls the tick
     * loop; {@code onResult} runs back on the server thread. Returns false, without calling
     * {@code onResult}, when another attempt is already running.
     */
    public static boolean connectAsync(MinecraftServer server, APWorldConnection connection,
                                       long timeoutMs, Consumer<Boolean> onResult) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return false;
        }
        Thread attempt = new Thread(() -> {
            boolean ok;
            try {
                ok = connectBlocking(connection, timeoutMs);
            } finally {
                IN_FLIGHT.set(false);
            }
            server.execute(() -> onResult.accept(ok));
        }, "aem-connect");
        attempt.setDaemon(true);
        attempt.start();
        return true;
    }

    /** True once per lost session: the first join after a drop gets to try the saved slot again. */
    public static boolean takeJoinRetry() {
        return JOIN_RETRY_ARMED.getAndSet(false);
    }

    /** Called when a session goes live (and when a world closes), so the next drop can retry again. */
    public static void armJoinRetry() {
        JOIN_RETRY_ARMED.set(true);
    }

    /**
     * Connects using the world's saved details, waiting up to {@code timeoutMs} for the handshake.
     * Returns whether a live session resulted (already-connected counts as success).
     */
    public static boolean connectBlocking(APWorldConnection connection, long timeoutMs) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        if (client.state().isConnected()) {
            return true;
        }
        if (!connection.hasSlot()) {
            return false;
        }

        int port;
        try {
            port = Integer.parseInt(connection.port.trim());
        } catch (NumberFormatException exception) {
            AEM.LOGGER.warn("Archipelago port is not a number: {}", connection.port);
            return false;
        }

        Deque<URI> candidates = buildCandidates(connection.address.trim(), port);
        if (candidates.isEmpty()) {
            return false;
        }

        CountDownLatch settled = new CountDownLatch(1);
        // No removeListener exists; the listener simply goes idle once the latch has fired.
        client.addListener(new APEventListener() {
            @Override
            public void onConnected(ArchipelagoClient ignored, APReceivedPacket packet) {
                settled.countDown();
            }

            @Override
            public void onConnectionRefused(ArchipelagoClient ignored, APReceivedPacket packet) {
                settled.countDown();
            }
        });

        APConnectionOptions options = APConnectionOptions.minecraft(connection.slot.trim(), connection.password);
        AEM.LOGGER.info("Connecting world to Archipelago as slot {}", connection.slot);
        attempt(candidates, options, settled);

        try {
            settled.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return client.state().isConnected();
    }

    private static void attempt(Deque<URI> candidates, APConnectionOptions options, CountDownLatch settled) {
        URI uri = candidates.poll();
        if (uri == null) {
            settled.countDown();
            return;
        }
        AEM.ARCHIPELAGO.connect(uri, options).exceptionally(throwable -> {
            if (!candidates.isEmpty()) {
                attempt(candidates, options, settled);
            } else {
                settled.countDown();
            }
            return null;
        });
    }

    private static Deque<URI> buildCandidates(String address, int port) {
        String host = address.replaceFirst("^[a-zA-Z]+://", "");
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }

        Deque<URI> uris = new ArrayDeque<>();
        if (host.isBlank()) {
            return uris;
        }
        try {
            uris.add(new URI("wss://" + host + ":" + port));
            uris.add(new URI("ws://" + host + ":" + port));
        } catch (URISyntaxException exception) {
            uris.clear();
        }
        return uris;
    }
}
