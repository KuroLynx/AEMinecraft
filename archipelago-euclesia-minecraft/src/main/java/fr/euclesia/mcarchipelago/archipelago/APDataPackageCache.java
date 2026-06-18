package fr.euclesia.mcarchipelago.archipelago;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.euclesia.mcarchipelago.AEM;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * On-disk cache of Archipelago data-package entries, keyed by game name and the server-provided
 * checksum. Data packages (especially large ones like The Witness) don't change between sessions
 * unless their game's apworld does, so caching by checksum lets a reconnect skip re-downloading
 * everything: only games whose checksum changed (or were never seen) are requested.
 *
 * <p>Best-effort: any I/O failure degrades to a cache miss / no-op so a broken cache never blocks
 * a connection.</p>
 */
public final class APDataPackageCache {
    private static final Gson GSON = new Gson();

    private final Path directoryOverride;
    private Path resolvedDirectory;

    public APDataPackageCache() {
        this(null);
    }

    /** Test/override constructor; pass {@code null} to use the default config-dir location. */
    public APDataPackageCache(Path directory) {
        this.directoryOverride = directory;
    }

    /** Returns the cached game data for this checksum, or {@code null} on a miss. */
    public JsonObject load(String game, String checksum) {
        if (checksum == null || checksum.isEmpty()) {
            return null;
        }
        Path directory = directory();
        if (directory == null) {
            return null;
        }
        Path file = directory.resolve(fileName(game, checksum));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception exception) {
            return null; // unreadable cache entry -> treat as a miss
        }
    }

    /** Stores the game data under its checksum, dropping any stale entries for the same game. */
    public void store(String game, String checksum, JsonObject gameData) {
        if (checksum == null || checksum.isEmpty()) {
            return;
        }
        Path directory = directory();
        if (directory == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            evictStale(directory, game, checksum);
            Path file = directory.resolve(fileName(game, checksum));
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(gameData, writer);
            }
        } catch (Exception exception) {
            AEM.LOGGER.warn("Failed to cache data package for game {}: {}", game, exception.toString());
        }
    }

    /** Removes prior checksum files for {@code game} so the cache keeps only one entry per game. */
    private void evictStale(Path directory, String game, String keepChecksum) {
        String prefix = sanitize(game) + ".";
        String keep = fileName(game, keepChecksum);
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(prefix) && !name.equals(keep);
            }).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            });
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private Path directory() {
        if (resolvedDirectory != null) {
            return resolvedDirectory;
        }
        if (directoryOverride != null) {
            resolvedDirectory = directoryOverride;
            return resolvedDirectory;
        }
        try {
            resolvedDirectory = FabricLoader.getInstance().getConfigDir().resolve("aem").resolve("datapackage");
        } catch (Exception exception) {
            return null; // no Fabric runtime (e.g. tests) -> caching disabled
        }
        return resolvedDirectory;
    }

    private static String fileName(String game, String checksum) {
        return sanitize(game) + "." + sanitize(checksum) + ".json";
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
