package fr.euclesia.mcarchipelago.server.connect;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.APConnectionOptions;
import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Establishes the Archipelago session for a world as it starts, blocking the caller until the
 * handshake succeeds or fails. Used by the connect-on-join gate so a world cannot be entered without
 * a live session. Mirrors the address handling of the client connect screen ({@code wss://} then
 * {@code ws://} fallback).
 */
public final class APWorldConnector {
    private APWorldConnector() {}

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
