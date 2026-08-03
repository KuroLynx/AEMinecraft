package fr.euclesia.mcarchipelago.client.dump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.gui.DumpScreen;
import fr.euclesia.mcarchipelago.server.command.ContainersDump;
import fr.euclesia.mcarchipelago.server.command.EntitiesDump;
import fr.euclesia.mcarchipelago.server.command.PackDump;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Dumps the world-dependent registry files ({@code entities.json}, {@code containers.json}) from the
 * title menu without an existing world. Both need a live {@link MinecraftServer} that
 * {@link DumpDataSource}'s world-free resource manager can't provide — {@link EntitiesDump}
 * instantiates a throwaway entity per type to read runtime class behaviour
 * (tameable/leashable/breedable), and {@link ContainersDump} probes each block's menu provider against
 * a real {@link net.minecraft.server.level.ServerLevel} — so this spins up a disposable singleplayer
 * world, dumps on {@code SERVER_STARTED}, leaves it, and deletes the save. Requesting both dumps them
 * in the same temp world, so the world is only created once.
 *
 * <p>Why a real world rather than a faked {@code Level}: mob entity <em>types</em> come from mod code
 * ({@code BuiltInRegistries.ENTITY_TYPE}, populated at mod-init) so any fresh world already contains
 * every modded mob; a datapack cannot add an entity type in vanilla. The temp world is created with
 * the user's dropped datapacks ({@code aem-datapacks/}) folded in so the derived {@code region} field
 * — read from biome {@code spawners} ({@link EntitiesDump}) — reflects datapack-altered spawns too.
 * The complete dropped-pack set is always enabled (folder datapacks default to disabled); for the
 * authoritative entity/region scan we want all spawn data, and extra packs can only add regions, never
 * phantom mobs.
 *
 * <p>Lifecycle (one in-flight dump at a time, guarded by {@link #isRunning()}):
 * <ol>
 *   <li>{@link #request} (client thread): seed {@code <save>/datapacks/}, create + start the world.</li>
 *   <li>{@link #onServerStarted} (server thread): write each requested file into the base pack folder.</li>
 *   <li>{@link #clientTick}: once fully joined, disconnect back to a fresh {@link DumpScreen} showing
 *       the result, then delete the temp save when teardown finishes.</li>
 * </ol>
 */
public final class HeadlessWorldDump {

    private HeadlessWorldDump() {}

    /** Output matches the offline tools and {@code /aem dump entities} (pretty + serializeNulls). */
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    /** Dump targets, matching the {@code /aem dump <what>} subcommands and the DumpScreen checkboxes. */
    public static final String ENTITIES = "entities";
    public static final String CONTAINERS = "containers";

    private static final String SAVE_NAME = "aem_entities_dump";
    /** Safety net: if the world never reaches SERVER_STARTED (e.g. datapack load failed), give up. */
    private static final long TIMEOUT_MS = 60_000L;

    // Single in-flight dump. All access is on the client thread except onServerStarted's writes (which
    // only set `result`/`dumped`, read by the client tick after), so volatile is enough.
    private static volatile boolean armed;
    private static volatile boolean dumped;     // server wrote (or failed to write) the file
    private static volatile boolean leaving;    // disconnect already issued
    private static volatile long armedAt;
    private static volatile Path saveDir;
    private static volatile Screen returnParent;
    private static volatile String result = "";
    private static volatile Set<String> targets = Set.of();

    public static boolean isRunning() {
        return armed;
    }

    /**
     * Whether {@code levelId} is the throwaway world this dump spins up. It is never bound to an
     * Archipelago slot, so the connect-on-join gate must let it through rather than block it.
     */
    public static boolean isDumpWorld(String levelId) {
        return SAVE_NAME.equals(levelId);
    }

    /**
     * Kick off a headless dump. Must run on the client thread (it swaps screens).
     *
     * @param returnTo    the screen the rebuilt {@link DumpScreen} returns to on Back
     * @param datapackDir {@code aem-datapacks/}: dropped world-datapacks folded into the temp world
     * @param what        which files to write ({@link #ENTITIES} / {@link #CONTAINERS}); both share the
     *                    one temp world
     */
    public static synchronized void request(Screen returnTo, Path datapackDir, Set<String> what) {
        if (armed || what.isEmpty()) {
            return;
        }
        targets = Set.copyOf(what);
        armed = true;
        dumped = false;
        leaving = false;
        armedAt = System.currentTimeMillis();
        returnParent = returnTo;
        result = "";
        AEM.LOGGER.info("Headless dump: requested {}, creating temp world '{}'", targets, SAVE_NAME);
        try {
            createTempWorld(datapackDir);
            AEM.LOGGER.info("Headless dump: temp world creation kicked off, awaiting server start");
        } catch (Exception exception) {
            AEM.LOGGER.warn("Headless dump: could not start temp world", exception);
            // Nothing started — clean up synchronously and report on a fresh dump screen.
            deleteSaveQuietly();
            finishWith("dump failed: " + exception);
        }
    }

    private static void createTempWorld(Path datapackDir) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        LevelStorageSource source = mc.getLevelSource();
        saveDir = source.getBaseDir().resolve(SAVE_NAME);

        // Fresh start every time: drop any leftover from a previous (e.g. crashed) run.
        deleteSaveQuietly();
        Path datapacks = saveDir.resolve("datapacks");
        Files.createDirectories(datapacks);
        copyDatapacks(datapackDir, datapacks);

        // Folder datapacks default to disabled, so explicitly enable everything the temp world can see
        // (vanilla + bundled mod packs + the copies above). Open a throwaway access just to read the
        // ids, then release its lock before createFreshLevel reacquires it.
        List<String> enabled;
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess(SAVE_NAME)) {
            PackRepository repository = ServerPacksSource.createPackRepository(access);
            repository.reload();
            enabled = new ArrayList<>(repository.getAvailableIds());
        }

        WorldDataConfiguration dataConfig = new WorldDataConfiguration(
                new DataPackConfig(enabled, List.of()),
                WorldDataConfiguration.DEFAULT.enabledFeatures());
        LevelSettings settings = new LevelSettings(
                "AEM Entities Dump",
                GameType.SPECTATOR,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true, // allowCommands
                dataConfig);
        // Flat + no structures = fastest spawn-area generation; the region scan reads biome JSON from
        // the resource manager, not the actual generated world, so the generator choice is irrelevant.
        WorldOptions options = new WorldOptions(0L, false, false);

        mc.createWorldOpenFlows().createFreshLevel(
                SAVE_NAME, settings, options, WorldPresets::createFlatWorldDimensions,
                new DumpScreen(returnParent)); // shown if creation itself fails before the server starts
    }

    /** Server thread: dump the running game's entities, then signal the client tick to leave. */
    public static void onServerStarted(MinecraftServer server) {
        // LevelResource.ROOT has id "." so getWorldPath(ROOT) ends in "/." — normalize() collapses that
        // back to the actual save folder (without it, getFileName() is "." and the guard never matches).
        String worldName = server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
        AEM.LOGGER.info("Headless dump: SERVER_STARTED world='{}' armed={} dumped={}",
                worldName, armed, dumped);
        if (!armed || dumped) {
            return;
        }
        // Only ever act on OUR temp world. If world creation failed asynchronously, `armed` can still be
        // set when the user loads a real world; without this guard we'd dump off it and then disconnect
        // them. The teardown (clientTick) only triggers once `dumped` is set here, so this gate protects it.
        if (!SAVE_NAME.equals(worldName)) {
            AEM.LOGGER.warn("Headless dump: world name '{}' != '{}', not our temp world; skipping",
                    worldName, SAVE_NAME);
            return;
        }
        // These are whole-game registries: they go in the base (vanilla) pack folder (e.g.
        // aem/minecraft_26_1_2/) alongside the rest of vanilla's files, per the per-pack layout.
        Path packDir = FabricLoader.getInstance().getGameDir().resolve("aem")
                .resolve(PackDump.basePackFolder());
        List<String> summary = new ArrayList<>();
        try {
            if (targets.contains(ENTITIES)) {
                summary.add(write(packDir, "entities.json", EntitiesDump.build(server), "entities"));
            }
            if (targets.contains(CONTAINERS)) {
                summary.add(write(packDir, "containers.json", ContainersDump.build(server), "containers"));
            }
            result = String.join(", ", summary) + " -> " + packDir;
        } catch (Exception exception) {
            AEM.LOGGER.warn("Headless dump: build failed", exception);
            result = "dump failed: " + exception;
        } finally {
            dumped = true; // proceed to teardown either way
        }
    }

    /** Writes one registry array and returns a "<n> <label>" fragment for the status line. */
    private static String write(Path packDir, String fileName, JsonArray array, String label)
            throws Exception {
        Path file = packDir.resolve(fileName);
        Files.createDirectories(packDir);
        Files.writeString(file, GSON.toJson(array) + "\n");
        AEM.LOGGER.info("Headless dump: wrote {} {} to {}", array.size(), label, file);
        return array.size() + " " + label;
    }

    /** Client thread: leave the temp world once joined, then delete it once teardown completes. */
    public static void clientTick(Minecraft mc) {
        if (!armed) {
            return;
        }

        // Wait until the client has fully joined (level present) before leaving — the cleanest state to
        // tear down from. disconnect() stops the integrated server and returns to a fresh dump screen.
        if (dumped && !leaving && mc.level != null) {
            leaving = true;
            DumpScreen.pendingStatus = result;
            AEM.LOGGER.info("Headless dump: dumped, leaving temp world");
            mc.disconnect(new DumpScreen(returnParent), false);
            return;
        }

        // Server fully stopped and level cleared: the save lock is released, safe to delete and reset.
        if (leaving && mc.getSingleplayerServer() == null && mc.level == null) {
            deleteSaveQuietly();
            reset();
            return;
        }

        // The world never started (datapack error, etc.): give up so a later real world load can't be
        // mistaken for this dump. Only safe to clean up once no integrated server is running.
        if (!dumped && !leaving && System.currentTimeMillis() - armedAt > TIMEOUT_MS
                && mc.getSingleplayerServer() == null) {
            AEM.LOGGER.warn("Headless dump: timed out before the temp world started");
            deleteSaveQuietly();
            finishWith("dump failed: temp world did not start");
        }
    }

    private static void finishWith(String message) {
        DumpScreen.pendingStatus = message;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DumpScreen) {
            mc.setScreen(new DumpScreen(returnParent)); // re-init to pick up pendingStatus
        }
        reset();
    }

    private static void reset() {
        armed = false;
        dumped = false;
        leaving = false;
        saveDir = null;
        returnParent = null;
        targets = Set.of();
    }

    // -- datapacks ----------------------------------------------------------

    /** Copy every dropped world-datapack (file or folder) into the temp world's {@code datapacks/}. */
    private static void copyDatapacks(Path from, Path into) throws Exception {
        if (from == null || !Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> entries = Files.list(from)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                copyRecursively(entry, into.resolve(entry.getFileName().toString()));
            }
        }
    }

    private static void copyRecursively(Path src, Path dst) throws Exception {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void deleteSaveQuietly() {
        Path dir = saveDir;
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (Exception ignored) {
                    // best-effort; a locked temp save is reaped on the next run's pre-clean
                }
            });
        } catch (Exception exception) {
            AEM.LOGGER.warn("Headless dump: could not delete temp save {}", dir, exception);
        }
    }
}
