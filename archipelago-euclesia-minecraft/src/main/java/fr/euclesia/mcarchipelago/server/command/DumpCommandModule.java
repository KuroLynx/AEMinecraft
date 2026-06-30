package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * {@code /aem dump <what>} — exports the data-driven content-pack files from the RUNNING game
 * (vanilla + mods + datapacks) to {@code <gameDir>/aem/}, the in-game counterpart of the offline
 * {@code tools/build_*.py}. All the work lives in {@link PackDump} (reads raw datapack resources), so
 * the command and the title-menu dump UI share one implementation. With no argument it dumps the
 * whole pack.
 *
 * <p>Subcommands mirror {@link PackDump#FILES}: {@code pack} (default), {@code advancements},
 * {@code structures}, {@code acquisition}, {@code block_mining}, {@code tags}, {@code meta}; plus
 * {@code entities} (the data-driven mob registry, {@link EntitiesDump}), which is NOT part of
 * {@link PackDump} because it reads runtime entity behaviour and so needs a loaded world — this command
 * reads the world it runs in; the title-menu UI dumps it too, off a disposable world
 * ({@code HeadlessEntitiesDump}). {@code brewing.json} stays curated (MC brewing is hard-coded), like
 * {@code items.csv}.
 */
public final class DumpCommandModule implements AEMCommandModule {

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        LiteralArgumentBuilder<CommandSourceStack> dump = Commands.literal("dump")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> run(context.getSource(), Set.copyOf(PackDump.FILES)))  // bare -> pack
                .then(Commands.literal("pack").executes(c -> run(c.getSource(), Set.copyOf(PackDump.FILES))));
        for (String file : PackDump.FILES) {
            dump.then(Commands.literal(file).executes(c -> run(c.getSource(), Set.of(file))));
        }
        // mob registry: runtime entity behaviour -> needs a world, so its own (non-PackDump) path.
        dump.then(Commands.literal("entities").executes(c -> runEntities(c.getSource())));
        // opt-in verbatim datapack copy (not part of the default "pack" set)
        dump.then(Commands.literal(PackDump.RAW_DATAPACK)
                .executes(c -> run(c.getSource(), Set.of(PackDump.RAW_DATAPACK))));
        root.then(dump);
    }

    private static int run(CommandSourceStack source, Set<String> files) {
        Path outDir = FabricLoader.getInstance().getGameDir().resolve("aem");
        String summary = PackDump.run(source.getServer().getResourceManager(), outDir, files);
        source.sendSuccess(() -> Component.literal("Dumped to " + outDir + ": " + summary), true);
        return 1;
    }

    private static int runEntities(CommandSourceStack source) {
        // entities.json is the whole-game mob registry: it lives in the base (vanilla) pack folder
        // alongside the rest of vanilla's dumped files, not flat in aem/.
        Path packDir = FabricLoader.getInstance().getGameDir().resolve("aem").resolve(PackDump.basePackFolder());
        Path file = packDir.resolve("entities.json");
        try {
            var array = EntitiesDump.build(source.getServer());
            Files.createDirectories(packDir);
            Files.writeString(file, GSON.toJson(array) + "\n");
            int count = array.size();
            source.sendSuccess(() -> Component.literal(
                    "Dumped " + count + " entities to " + file + "."), true);
        } catch (Exception exception) {
            source.sendFailure(Component.literal("entities dump failed: " + exception));
            return 0;
        }
        return 1;
    }
}
