package fr.euclesia.mcarchipelago.server.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import fr.euclesia.mcarchipelago.AEM;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-wide Archipelago settings, read from {@code config/aem.json}.
 *
 * <p>Singleplayer gets its credentials from the create-world screen, which stages them into the new
 * world's folder ({@link APWorldConnection}). A dedicated server has no such screen and no way to
 * reach one, so without this an operator would have to hand-write a JSON file into the world save
 * before the server could ever connect. This is the same details in the place server operators
 * expect them, read once at startup and copied into the world on first use — after which the world
 * file is authoritative, so moving a save between hosts carries its slot with it.
 *
 * <p>A missing file is written out as a commented-out template rather than treated as an error: a
 * fresh server should boot, say what it wants, and let the operator fill it in.
 */
public final class AEMServerConfig {
    private static final String FILE_NAME = "aem.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Set to a real slot name to enable auto-connect; blank means "do nothing at startup". */
    public String slot = "";
    public String address = "archipelago.gg";
    public String port = "38281";
    public String password = "";

    /**
     * Whether to open the session as the server starts. Turn this off to boot the server idle and
     * connect by hand with {@code /aem connect}, which is the usual choice while an operator is
     * still setting a room up.
     */
    public boolean connectOnStart = true;

    public boolean hasSlot() {
        return slot != null && !slot.isBlank();
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * The config on disk, or a default instance. Never null and never throws: a server that cannot
     * read its config should still boot and complain, not die before the world loads.
     */
    public static AEMServerConfig load() {
        Path path = path();
        if (!Files.exists(path)) {
            AEMServerConfig fresh = new AEMServerConfig();
            fresh.write();
            AEM.LOGGER.info("Wrote a starter Archipelago config to {} - set \"slot\" to connect.", path);
            return fresh;
        }
        try {
            AEMServerConfig loaded = GSON.fromJson(Files.readString(path), AEMServerConfig.class);
            return loaded != null ? loaded : new AEMServerConfig();
        } catch (IOException | JsonSyntaxException exception) {
            AEM.LOGGER.warn("Could not read {} ({}); ignoring it this run.", path, exception.toString());
            return new AEMServerConfig();
        }
    }

    public void write() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Could not write {} ({}).", path, exception.toString());
        }
    }

    /** These settings as a world-scoped connection, for seeding a world that has none yet. */
    public APWorldConnection toWorldConnection() {
        APWorldConnection connection = new APWorldConnection();
        connection.address = address;
        connection.port = port;
        connection.slot = slot;
        connection.password = password;
        return connection;
    }
}
