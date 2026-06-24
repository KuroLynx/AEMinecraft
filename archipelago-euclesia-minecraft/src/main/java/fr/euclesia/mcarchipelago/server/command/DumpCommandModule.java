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
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code /aem dump <what>} — exports the data-driven content-pack files from the RUNNING game
 * (vanilla + mods + datapacks), the in-game counterpart of the offline {@code tools/build_*.py}.
 * Each subcommand targets one pack file and writes the SAME schema its Python tool does, so the
 * apworld ingests either source identically. With no argument it dumps the whole pack.
 *
 * <ul>
 *   <li>{@code pack}         — every file below (the default).</li>
 *   <li>{@code advancements} — {@code manifest.json} (was {@code dump-advancements}).</li>
 *   <li>{@code tags}         — {@code tags.json} (item / block / entity_type tags, flattened).</li>
 *   <li>{@code meta}         — {@code meta.json} (pack name / namespace / mc_version).</li>
 * </ul>
 *
 * Files land in {@code <gameDir>/aem/}. ({@code structures}, {@code acquisition}, {@code block_mining}
 * and {@code brewing} are added alongside these; until then {@code pack} dumps what is available.)
 */
public final class DumpCommandModule implements AEMCommandModule {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("dump")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> dumpPack(context.getSource()))            // /aem dump -> whole pack
                .then(Commands.literal("pack").executes(c -> dumpPack(c.getSource())))
                .then(Commands.literal("advancements").executes(c -> dumpAdvancements(c.getSource())))
                .then(Commands.literal("tags").executes(c -> dumpTags(c.getSource())))
                .then(Commands.literal("meta").executes(c -> dumpMeta(c.getSource()))));
    }

    // -- pack ---------------------------------------------------------------

    private static int dumpPack(CommandSourceStack source) {
        int ok = 0;
        ok += dumpAdvancements(source);
        ok += dumpTags(source);
        ok += dumpMeta(source);
        source.sendSuccess(() -> Component.literal("Pack dump complete (" + outDir() + ")."), true);
        return ok;
    }

    // -- advancements -> manifest.json --------------------------------------

    private static int dumpAdvancements(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());

        Map<String, JsonObject> records = new TreeMap<>();  // id-sorted, matches extract_manifest.py
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
            records.put(id.toString(), reshapeAdvancement(full, path));
        }

        JsonObject manifest = new JsonObject();
        records.forEach(manifest::add);
        if (!write(source, "manifest.json", manifest)) {
            return 0;
        }
        int written = records.size();
        int skippedRecipes = skipped;
        source.sendSuccess(() -> Component.literal(
                "  advancements: " + written + " (" + skippedRecipes + " recipes skipped)"), false);
        return 1;
    }

    /** Pull the manifest fields out of a codec-encoded advancement JSON. */
    private static JsonObject reshapeAdvancement(JsonObject full, String path) {
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

    // -- tags -> tags.json --------------------------------------------------

    private static int dumpTags(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        JsonObject table = new JsonObject();
        table.add("item", tagsFor(server, Registries.ITEM));
        table.add("entity_type", tagsFor(server, Registries.ENTITY_TYPE));
        table.add("block", tagsFor(server, Registries.BLOCK));
        if (!write(source, "tags.json", table)) {
            return 0;
        }
        int item = table.getAsJsonObject("item").size();
        int entity = table.getAsJsonObject("entity_type").size();
        int block = table.getAsJsonObject("block").size();
        source.sendSuccess(() -> Component.literal(
                "  tags: item " + item + " | entity_type " + entity + " | block " + block), false);
        return 1;
    }

    /** Each tag in a registry flattened to its concrete member ids (the registry resolves nesting). */
    private static <T> JsonObject tagsFor(MinecraftServer server, ResourceKey<Registry<T>> registryKey) {
        Registry<T> registry = server.registryAccess().lookupOrThrow(registryKey);
        Map<String, JsonArray> sorted = new TreeMap<>();
        for (HolderSet.Named<T> named : (Iterable<HolderSet.Named<T>>) registry.getTags()::iterator) {
            TreeSet<String> members = new TreeSet<>();
            for (Holder<T> holder : named) {
                Identifier id = registry.getKey(holder.value());
                if (id != null) {
                    members.add(id.toString());
                }
            }
            JsonArray array = new JsonArray();
            members.forEach(array::add);
            sorted.put(named.key().location().toString(), array);
        }
        JsonObject out = new JsonObject();
        sorted.forEach(out::add);
        return out;
    }

    // -- meta -> meta.json --------------------------------------------------

    private static int dumpMeta(CommandSourceStack source) {
        String version = SharedConstants.getCurrentVersion().name();
        JsonObject meta = new JsonObject();
        meta.addProperty("name", "vanilla_dump");
        meta.addProperty("source", "dump");
        meta.addProperty("namespace", "minecraft");
        meta.addProperty("mc_version", version);
        meta.addProperty("description", "Dumped from the running game (MC " + version + ").");
        if (!write(source, "meta.json", meta)) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("  meta: MC " + version), false);
        return 1;
    }

    // -- io -----------------------------------------------------------------

    private static Path outDir() {
        return FabricLoader.getInstance().getGameDir().resolve("aem");
    }

    private static boolean write(CommandSourceStack source, String fileName, JsonElement json) {
        Path out = outDir().resolve(fileName);
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, GSON.toJson(json));
            return true;
        } catch (IOException exception) {
            source.sendFailure(Component.literal("Failed to write " + fileName + ": " + exception.getMessage()));
            return false;
        }
    }
}
