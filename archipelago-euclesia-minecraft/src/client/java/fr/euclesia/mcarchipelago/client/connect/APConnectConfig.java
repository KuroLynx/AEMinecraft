package fr.euclesia.mcarchipelago.client.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import fr.euclesia.mcarchipelago.AEM;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Last-used Archipelago connection details, persisted to {@code config/archipelago-euclesia.json}
 * so the connect screen remembers them between launches. This is a singleplayer convenience store;
 * the password is kept in plain text, matching how the standard Archipelago text clients behave.
 */
public final class APConnectConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("archipelago-euclesia.json");

    private static APConnectConfig instance;

    public String address = "archipelago.gg";
    public String port = "38281";
    public String slot = "";
    public String password = "";

    public static APConnectConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static APConnectConfig load() {
        if (Files.exists(FILE)) {
            try {
                APConnectConfig loaded = GSON.fromJson(Files.readString(FILE), APConnectConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException | JsonSyntaxException exception) {
                AEM.LOGGER.warn("Failed to read Archipelago connect config, using defaults", exception);
            }
        }
        return new APConnectConfig();
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Failed to save Archipelago connect config", exception);
        }
    }
}
