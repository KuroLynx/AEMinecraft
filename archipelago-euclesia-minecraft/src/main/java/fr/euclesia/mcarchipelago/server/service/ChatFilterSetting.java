package fr.euclesia.mcarchipelago.server.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.ChatFilterPreference;
import fr.euclesia.mcarchipelago.server.connect.APWorldPaths;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers the "hide multiworld noise" chat filter's on/off state in the world folder, as
 * {@code archipelago/chatfilter.json}. Same file-based shape as {@link DeathLinkSetting} and for
 * the same reason: this is a run-wide setting shared by everyone on the server, not a per-player
 * one, and there is no need for a tri-state — an absent file simply means off.
 */
public final class ChatFilterSetting {
    private static final String FILE_NAME = "chatfilter.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Stored {
        boolean enabled;
    }

    private ChatFilterSetting() {}

    /** Applies this world's stored setting (or off, if it has none). Unconditional on purpose. */
    public static void load(Path worldDir) {
        boolean stored = read(APWorldPaths.readPath(worldDir, FILE_NAME));
        ChatFilterPreference.restore(stored);
    }

    /** Saves {@code enabled} for this world. */
    public static void save(MinecraftServer server, boolean enabled) {
        try {
            Stored stored = new Stored();
            stored.enabled = enabled;
            Files.writeString(APWorldPaths.writePath(server, FILE_NAME), GSON.toJson(stored));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Failed to save {} for this world", FILE_NAME, exception);
        }
    }

    private static boolean read(Path file) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            Stored stored = GSON.fromJson(Files.readString(file), Stored.class);
            return stored != null && stored.enabled;
        } catch (IOException | JsonSyntaxException exception) {
            AEM.LOGGER.warn("Failed to read {} for this world", FILE_NAME, exception);
            return false;
        }
    }
}
