package fr.euclesia.mcarchipelago.server.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.DeathLinkPreference;
import fr.euclesia.mcarchipelago.server.connect.APWorldPaths;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers an operator's {@code /aem deathlink} settings (on/off, and the {@code mode}: one random
 * player or all of them) in the world folder, as {@code archipelago/deathlink.json}.
 *
 * <p>A plain file rather than level {@link net.minecraft.world.level.saveddata.SavedData} because of
 * when it has to be read: the server connects to Archipelago during SERVER_STARTING, and the tag sent
 * on connect decides whether other worlds' deaths reach this one. That is before the levels exist, so
 * saved data would arrive too late to be honoured — the run would come up receiving deathlinks the
 * operator had switched off.
 *
 * <p>No file means no override, i.e. the slot's own {@code death_link} decides, in random mode.
 * Clearing both writes no empty marker; it deletes.
 */
public final class DeathLinkSetting {
    private static final String FILE_NAME = "deathlink.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** On-disk shape. Boxed: null (or an absent file) means "no override". */
    private static final class Stored {
        Boolean enabled;
        Boolean killAll;
    }

    private DeathLinkSetting() {}

    /**
     * Applies this world's stored setting, or clears any override if it has none.
     *
     * <p>Unconditional on purpose: it also runs when there is no file, so opening a second world in
     * one session cannot inherit the first one's setting.
     */
    public static void load(Path worldDir) {
        Stored stored = read(APWorldPaths.readPath(worldDir, FILE_NAME));
        DeathLinkPreference.restore(stored.enabled);
        DeathLinkService.setKillAll(Boolean.TRUE.equals(stored.killAll));
        if (stored.enabled != null) {
            AEM.LOGGER.info("[AEM] DeathLink {} for this world (/aem deathlink)", stored.enabled ? "ON" : "OFF");
        }
    }

    /** This world's stored on/off, or {@code null} if it has none. */
    public static Boolean stored(MinecraftServer server) {
        return read(APWorldPaths.readPath(server, FILE_NAME)).enabled;
    }

    /** Saves {@code enabled}, {@code null} meaning back to the slot's own. */
    public static void save(MinecraftServer server, Boolean enabled) {
        Stored stored = read(APWorldPaths.readPath(server, FILE_NAME));
        stored.enabled = enabled;
        write(server, stored);
    }

    /** Saves the mode: every living player ({@code true}) or one random one. */
    public static void saveKillAll(MinecraftServer server, boolean killAll) {
        Stored stored = read(APWorldPaths.readPath(server, FILE_NAME));
        stored.killAll = killAll ? true : null;
        write(server, stored);
    }

    /** Writes {@code stored}, or deletes the file when it holds nothing. */
    private static void write(MinecraftServer server, Stored stored) {
        try {
            if (stored.enabled == null && stored.killAll == null) {
                // Twice: readPath prefers the archipelago/ copy and falls back to the pre-relocation
                // one in the world root, and a world part-way through that move can hold both.
                Files.deleteIfExists(APWorldPaths.readPath(server, FILE_NAME));
                Files.deleteIfExists(APWorldPaths.readPath(server, FILE_NAME));
                return;
            }
            Files.writeString(APWorldPaths.writePath(server, FILE_NAME), GSON.toJson(stored));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Failed to save {} for this world", FILE_NAME, exception);
        }
    }

    /** The file's settings; an absent or unreadable file reads as no override at all. */
    private static Stored read(Path file) {
        if (!Files.exists(file)) {
            return new Stored();
        }
        try {
            Stored stored = GSON.fromJson(Files.readString(file), Stored.class);
            return stored == null ? new Stored() : stored;
        } catch (IOException | JsonSyntaxException exception) {
            AEM.LOGGER.warn("Failed to read {} for this world", FILE_NAME, exception);
            return new Stored();
        }
    }
}
