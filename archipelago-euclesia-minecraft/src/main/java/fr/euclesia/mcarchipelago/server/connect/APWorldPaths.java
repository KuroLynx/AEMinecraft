package fr.euclesia.mcarchipelago.server.connect;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the per-world {@code archipelago/} subfolder that holds every world-scoped file this mod
 * writes (connection details, progress markers, …), keeping them out of the world root.
 *
 * <p>Reads fall back to the legacy world-root location so worlds created before this relocation keep
 * working; writes always target the subfolder, so a world's files migrate the next time each is saved.
 */
public final class APWorldPaths {
    private static final String DIR_NAME = "archipelago";

    private APWorldPaths() {}

    /** The {@code archipelago/} subfolder inside the given world root (not created). */
    public static Path dir(Path worldRoot) {
        return worldRoot.resolve(DIR_NAME);
    }

    /** The {@code archipelago/} subfolder for a running server's world (not created). */
    public static Path dir(MinecraftServer server) {
        return dir(server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * The path to write {@code fileName} to (inside the subfolder), ensuring the subfolder exists.
     * @throws IOException if the subfolder cannot be created
     */
    public static Path writePath(Path worldRoot, String fileName) throws IOException {
        return Files.createDirectories(dir(worldRoot)).resolve(fileName);
    }

    /** @see #writePath(Path, String) */
    public static Path writePath(MinecraftServer server, String fileName) throws IOException {
        return writePath(server.getWorldPath(LevelResource.ROOT), fileName);
    }

    /**
     * The path to read {@code fileName} from: the subfolder copy if it exists, otherwise the legacy
     * world-root copy. When neither exists, returns the subfolder path (so {@code Files.exists} checks
     * on the result read {@code false}).
     */
    public static Path readPath(Path worldRoot, String fileName) {
        Path current = dir(worldRoot).resolve(fileName);
        if (Files.exists(current)) {
            return current;
        }
        Path legacy = worldRoot.resolve(fileName);
        return Files.exists(legacy) ? legacy : current;
    }

    /** @see #readPath(Path, String) */
    public static Path readPath(MinecraftServer server, String fileName) {
        return readPath(server.getWorldPath(LevelResource.ROOT), fileName);
    }
}
