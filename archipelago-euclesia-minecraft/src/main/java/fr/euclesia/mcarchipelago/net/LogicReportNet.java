package fr.euclesia.mcarchipelago.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.server.connect.APWorldPaths;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Writes logic reports into {@code <world>/archipelago/logic_reports/}, a folder a player can zip and
 * send as it is:
 * <ul>
 *   <li>{@code slot_data.json} — the slot data (it carries the YAML options) plus the mod and game
 *       versions; rewritten on every report, since it is fixed for the life of the slot.</li>
 *   <li>{@code latest.log} — a copy of the game log at the time of the latest report.</li>
 *   <li>one {@code report_<time>_<advancement>.json} per report — the tile, what the player says is
 *       wrong, what their tracker showed, and every item received so far, which is the state logic
 *       was judged against.</li>
 * </ul>
 * Written server-side because the slot data lives there; on a dedicated server the folder is in the
 * server's world, for its admin to collect.
 */
public final class LogicReportNet {
    public static final int MAX_NOTE_LENGTH = 1000;
    public static final Set<String> KINDS = Set.of("wrong_logic", "should_not_be_accessible");
    private static final String DIR_NAME = "logic_reports";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private LogicReportNet() {}

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(LogicReportPayload.TYPE, LogicReportPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(LogicReportPayload.TYPE,
                (payload, context) -> report(context.player(), payload));
    }

    private static void report(ServerPlayer player, LogicReportPayload payload) {
        Identifier id = Identifier.tryParse(payload.advancementId());
        if (id == null || !KINDS.contains(payload.kind())) {
            return;
        }
        try {
            Path dir = Files.createDirectories(APWorldPaths.dir(player.level().getServer()).resolve(DIR_NAME));
            ArchipelagoClient client = AEM.ARCHIPELAGO.client();

            JsonObject slot = new JsonObject();
            slot.addProperty("mod_version", modVersion());
            slot.addProperty("minecraft_version", SharedConstants.getCurrentVersion().name());
            slot.addProperty("team", client.state().team());
            slot.addProperty("slot", client.state().slot());
            slot.addProperty("player_name", client.state().playerName(client.state().slot()));
            slot.add("slot_data", client.state().slotData());
            Files.writeString(dir.resolve("slot_data.json"), GSON.toJson(slot));

            Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
            if (Files.exists(log)) {
                Files.copy(log, dir.resolve("latest.log"), StandardCopyOption.REPLACE_EXISTING);
            }

            String stamp = LocalDateTime.now().format(STAMP);
            Path file = dir.resolve("report_" + stamp + "_" + id.getPath().replaceAll("[^a-z0-9_.-]", "_") + ".json");
            Files.writeString(file, GSON.toJson(reportJson(player, id, payload, stamp)));

            AEM.LOGGER.info("Logic report on {} saved to {}", id, file);
            player.sendSystemMessage(Component.translatable("gui.aem.logic_report.saved",
                    advancementTitle(player, id), dir.toAbsolutePath().toString()));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Could not save the logic report on {}: {}", id, exception.toString());
            player.sendSystemMessage(Component.translatable("gui.aem.logic_report.failed", exception.toString()));
        }
    }

    private static JsonObject reportJson(ServerPlayer player, Identifier id, LogicReportPayload payload, String stamp) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        var registries = client.registries();

        JsonObject report = new JsonObject();
        report.addProperty("time", stamp);
        report.addProperty("advancement", id.toString());
        report.addProperty("title", advancementTitle(player, id));
        registries.apLocations().idForGameId(id.toString()).ifPresent(locationId -> {
            report.addProperty("location_id", locationId);
            registries.apLocations().nameForId(locationId).ifPresent(name -> report.addProperty("location", name));
        });
        report.addProperty("kind", payload.kind());
        report.addProperty("tracker_state", payload.logicState());
        report.addProperty("note", payload.note());
        report.addProperty("dimension", player.level().dimension().identifier().toString());

        JsonArray received = new JsonArray();
        for (long itemId : registries.apItems().receivedOrder()) {
            received.add(registries.apItems().name(itemId).orElse(String.valueOf(itemId)));
        }
        report.add("received_items", received);

        JsonArray checked = new JsonArray();
        for (long locationId : client.state().checkedLocations()) {
            checked.add(registries.apLocations().nameForId(locationId).orElse(String.valueOf(locationId)));
        }
        report.add("checked_locations", checked);
        return report;
    }

    private static String advancementTitle(ServerPlayer player, Identifier id) {
        AdvancementHolder holder = player.level().getServer().getAdvancements().get(id);
        if (holder == null) {
            return id.toString();
        }
        return holder.value().display().map(display -> display.getTitle().getString()).orElse(id.toString());
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(AEM.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
