package fr.euclesia.mcarchipelago.client.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.APConnectionOptions;
import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletionException;
import java.util.stream.StreamSupport;

/**
 * Client-side driver for the connect screen. It can run from the main menu (no Minecraft server
 * yet) because the Archipelago link is just a WebSocket — the session it establishes survives into
 * the world that loads next, so {@code StartDimensionService} sees a connected session on join.
 *
 * <p>Connection happens in two phases that report back asynchronously: the WebSocket open (handled
 * by the transport future, with a {@code wss://} then {@code ws://} fallback) and the Archipelago
 * handshake ({@link #onConnected} / {@link #onConnectionRefused}). The screen polls {@link #status}.
 */
public final class APConnectController implements APEventListener {

    public enum Status { IDLE, CONNECTING, CONNECTED, FAILED }

    /** Status-indicator colour state: never connected, currently connected, or connection lost. */
    public enum Indicator { GRAY, GREEN, RED }

    public static final APConnectController INSTANCE = new APConnectController();

    private volatile Status status = Status.IDLE;
    private volatile String message = "";
    private volatile boolean everConnected;

    private APConnectController() {}

    /** Registers the controller as a session listener; call once during client init. */
    public static void init() {
        AEM.ARCHIPELAGO.client().addListener(INSTANCE);
    }

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    /**
     * Indicator colour: green while the session is live, red once a previously live session has
     * dropped, grey before any successful connection.
     */
    public Indicator indicator() {
        if (AEM.ARCHIPELAGO.client().state().isConnected()) {
            return Indicator.GREEN;
        }
        return everConnected ? Indicator.RED : Indicator.GRAY;
    }

    /** Reflects an already-live session when the screen opens without a fresh connect attempt. */
    public void syncFromSession() {
        if (status != Status.CONNECTING && AEM.ARCHIPELAGO.client().state().isConnected()) {
            status = Status.CONNECTED;
            message = "Connected";
        }
    }

    public synchronized void connect(String address, String port, String slot, String password) {
        if (slot.isBlank()) {
            fail("Slot name is required");
            return;
        }

        int portNumber;
        try {
            portNumber = Integer.parseInt(port.trim());
        } catch (NumberFormatException exception) {
            fail("Port must be a number");
            return;
        }

        Deque<URI> candidates = buildCandidates(address.trim(), portNumber);
        if (candidates.isEmpty()) {
            fail("Invalid address");
            return;
        }

        status = Status.CONNECTING;
        message = "Connecting…";
        APConnectionOptions options = APConnectionOptions.minecraft(slot.trim(), password);
        attempt(candidates, options);
    }

    private void attempt(Deque<URI> candidates, APConnectionOptions options) {
        URI uri = candidates.poll();
        AEM.LOGGER.info("Attempting Archipelago connection to {}", uri);
        AEM.ARCHIPELAGO.connect(uri, options).exceptionally(throwable -> {
            if (!candidates.isEmpty()) {
                attempt(candidates, options);
            } else if (status == Status.CONNECTING) {
                fail("Could not reach server: " + rootMessage(throwable));
            }
            return null;
        });
    }

    /** Tries {@code wss://} first then {@code ws://}; any scheme/port the user typed is ignored. */
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

    private void fail(String reason) {
        status = Status.FAILED;
        message = reason;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    @Override
    public void onConnected(ArchipelagoClient client, APReceivedPacket packet) {
        status = Status.CONNECTED;
        everConnected = true;
        message = "Connected as slot " + client.state().slot();
    }

    @Override
    public void onConnectionRefused(ArchipelagoClient client, APReceivedPacket packet) {
        JsonElement errors = packet.payload().get("errors");
        String detail = errors != null && errors.isJsonArray() ? joinErrors(errors.getAsJsonArray()) : "refused";
        status = Status.FAILED;
        message = "Refused: " + detail;
    }

    private static String joinErrors(JsonArray errors) {
        return StreamSupport.stream(errors.spliterator(), false)
                .map(JsonElement::getAsString)
                .reduce((a, b) -> a + ", " + b)
                .orElse("refused");
    }
}
