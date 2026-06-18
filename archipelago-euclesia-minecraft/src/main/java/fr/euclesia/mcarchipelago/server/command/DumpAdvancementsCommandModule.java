package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /aem dump-advancements} — walks every advancement registered in the running game (vanilla +
 * mods + datapacks) and writes a normalised manifest the apworld ingests as a content pack. This is
 * the in-game counterpart of the offline {@code tools/extract_manifest.py}; both target the SAME
 * schema, so the apworld treats vanilla, mods and datapacks uniformly:
 *
 * <pre>
 * { "&lt;id&gt;": { "parent": &lt;id|null&gt;, "tab": &lt;str&gt;, "frame": "task|goal|challenge",
 *               "requirements": [[ "&lt;criterion&gt;", ... ], ...],
 *               "criteria": { "&lt;name&gt;": { "trigger": "&lt;id&gt;", "conditions": { ... } } } } }
 * </pre>
 *
 * Each advancement is re-encoded through {@link Advancement#CODEC} (which serialises criteria and
 * requirements back to their vanilla JSON form), then reshaped. Recipe advancements are skipped —
 * they are never randomised checks.
 */
public final class DumpAdvancementsCommandModule implements AEMCommandModule {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("dump-advancements")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> dump(context.getSource())));
    }

    private static int dump(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());

        // TreeMap → deterministic id-sorted output, matching tools/extract_manifest.py for diffing.
        Map<String, JsonObject> records = new TreeMap<>();
        int skipped = 0;
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            Identifier id = holder.id();
            String path = id.getPath();
            if (path.startsWith("recipes/")) {
                skipped++;
                continue;
            }
            JsonObject full = Advancement.CODEC.encodeStart(ops, holder.value())
                    .result()
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                    .orElse(null);
            if (full == null) {
                skipped++;
                continue;
            }
            records.put(id.toString(), reshape(full, path));
        }

        JsonObject manifest = new JsonObject();
        records.forEach(manifest::add);

        Path out = FabricLoader.getInstance().getGameDir().resolve("aem").resolve("manifest.json");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, GSON.toJson(manifest));
        } catch (IOException exception) {
            source.sendFailure(Component.literal("Failed to write manifest: " + exception.getMessage()));
            return 0;
        }

        int written = records.size();
        int skippedRecipes = skipped;
        source.sendSuccess(() -> Component.literal(
                "Dumped " + written + " advancements (" + skippedRecipes + " recipes skipped) to " + out), false);
        return 1;
    }

    /** Pull the manifest fields out of a codec-encoded advancement JSON. */
    private static JsonObject reshape(JsonObject full, String path) {
        JsonObject record = new JsonObject();
        record.add("parent", full.has("parent") ? full.get("parent") : JsonNull.INSTANCE);
        record.addProperty("tab", path.contains("/") ? path.substring(0, path.indexOf('/')) : path);

        String frame = "task";
        if (full.has("display")) {
            JsonObject display = full.getAsJsonObject("display");
            if (display.has("frame")) {
                frame = display.get("frame").getAsString();
            }
        }
        record.addProperty("frame", frame);
        record.add("requirements", full.has("requirements") ? full.get("requirements") : new JsonArray());
        record.add("criteria", full.has("criteria") ? full.get("criteria") : new JsonObject());
        return record;
    }
}
