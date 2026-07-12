package fr.euclesia.mcarchipelago.server.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import fr.euclesia.mcarchipelago.AEM;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-world Archipelago connection details, stored as {@code archipelago/archipelago_connection.json}
 * inside the world's save folder (see {@link APWorldPaths}). Entered on the Archipelago tab of the
 * create-world screen and used to connect when the world is joined (see the connect-on-join gate).
 * One world == one Archipelago slot.
 *
 * <p>The password is kept in plain text, matching how the standard Archipelago text clients behave.
 */
public final class APWorldConnection {
    private static final String FILE_NAME = "archipelago_connection.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Filled in by the create-world screen (client) and written into the new world's folder when its
    // server starts; cleared once consumed so it never leaks into a later world.
    private static volatile APWorldConnection pending;

    public String address = "archipelago.gg";
    public String port = "38281";
    public String slot = "";
    public String password = "";

    public static void setPending(APWorldConnection connection) {
        pending = connection;
    }

    public static APWorldConnection takePending() {
        APWorldConnection taken = pending;
        pending = null;
        return taken;
    }

    /**
     * Returns the staged connection without consuming it. The client pre-flight (WorldOpenFlowsMixin)
     * needs it for a brand-new world, whose connection file isn't written to disk until the server
     * starts; {@code takePending} still runs at server start to persist it.
     */
    public static APWorldConnection peekPending() {
        return pending;
    }

    /** Reads the connection saved in a world folder, or {@code null} if none/unreadable. */
    public static APWorldConnection read(Path worldDir) {
        Path file = APWorldPaths.readPath(worldDir, FILE_NAME);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(file), APWorldConnection.class);
        } catch (IOException | JsonSyntaxException exception) {
            AEM.LOGGER.warn("Failed to read {} for this world", FILE_NAME, exception);
            return null;
        }
    }

    /** Writes this connection into a world folder's {@code archipelago/} subfolder. */
    public void write(Path worldDir) {
        try {
            Files.writeString(APWorldPaths.writePath(worldDir, FILE_NAME), GSON.toJson(this));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Failed to write {} for this world", FILE_NAME, exception);
        }
    }

    /** A connection is usable only with a slot name; address/port fall back to sensible defaults. */
    public boolean hasSlot() {
        return slot != null && !slot.isBlank();
    }
}
