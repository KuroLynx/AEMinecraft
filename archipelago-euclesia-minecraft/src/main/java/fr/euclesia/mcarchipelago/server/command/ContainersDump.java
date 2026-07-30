package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import fr.euclesia.mcarchipelago.AEM;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.RecipeCraftingHolder;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds {@code containers.json} — the data-driven registry of every block that opens a GUI or holds
 * items, the source the apworld generates its station/container Knowledge rows from (see
 * {@code content/knowledges.csv}). Same principle as {@link EntitiesDump}: read the RUNNING game so
 * vanilla + mods dump uniformly, instead of hand-listing blocks that shift between versions.
 *
 * <p>Each block is classified {@code station} (makes or transforms items) or {@code container} (stores
 * them), derived from what the block actually is:
 * <ul>
 *   <li>its block entity implements {@link RecipeCraftingHolder} (furnace family) or
 *       {@link CraftingContainer} (crafter) — it runs recipes, so: station;</li>
 *   <li>its state offers a {@link MenuProvider} (crafting table, anvil, smithing table, grindstone,
 *       stonecutter, loom, cartography table — and, once placed, the enchanting table), or its block
 *       entity is a {@link MenuProvider} that is not a {@link Container} (beacon) — a GUI with no
 *       storage, so: station;</li>
 *   <li>its block entity is a {@link Container} (chest, barrel, shulker box, hopper, dispenser,
 *       chiseled bookshelf, decorated pot, …) — storage, so: container.</li>
 * </ul>
 * Three things resist derivation and are listed below as game knowledge, the same way
 * {@link EntitiesDump} carries its boss/fallback sets: {@link #STATION_HOLDOUTS},
 * {@link #CONTAINER_HOLDOUTS} and {@link #RECIPE_STATIONS}.
 *
 * <p>Dyed variants collapse onto one Knowledge via {@code knowledge_group}: a leading dye colour is
 * stripped when the remainder is itself a registered block, so the 17 shulker boxes share
 * {@code minecraft:shulker_box} instead of minting 17 Knowledge items.
 */
public final class ContainersDump {
    private ContainersDump() {}

    /** Anchor position for the block-entity probe (never placed in the world, so any pos will do). */
    private static final BlockPos PROBE = BlockPos.ZERO;

    /** Flags for the place-and-probe below: no neighbour/client updates, no drops, no on-place hooks —
     *  the block exists for one method call and is put back before anything can observe it. */
    private static final int PROBE_FLAGS = Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_ON_PLACE;

    /** Stations whose block entity looks exactly like a storage one (a {@code BaseContainerBlockEntity}
     *  with no recipe interface), so nothing in the class tells us it brews. */
    private static final Set<String> STATION_HOLDOUTS = Set.of("minecraft:brewing_stand");

    /** GUI blocks that are storage despite classifying as stations: the ender chest's block entity is
     *  neither a Container nor a MenuProvider (the block opens the player's own ender inventory), and
     *  the lectern is a MenuProvider that holds a book without transforming it. */
    private static final Set<String> CONTAINER_HOLDOUTS =
            Set.of("minecraft:ender_chest", "minecraft:lectern");

    /** Block -> the {@code station} key its recipes carry in {@code acquisition.json}, which is what
     *  lets the apworld require a station's Knowledge for every recipe that needs it. The recipe
     *  registry knows the recipe types but not which block runs them, so this mapping is game
     *  knowledge. Campfires are here for that reason alone — they cook without opening any GUI, so the
     *  derivation above would never see them. */
    private static final Map<String, String> RECIPE_STATIONS = Map.ofEntries(
            Map.entry("minecraft:crafting_table", "crafting"),
            Map.entry("minecraft:crafter", "crafting"),
            Map.entry("minecraft:furnace", "smelting"),
            Map.entry("minecraft:blast_furnace", "blasting"),
            Map.entry("minecraft:smoker", "smoking"),
            Map.entry("minecraft:campfire", "campfire_cooking"),
            Map.entry("minecraft:soul_campfire", "campfire_cooking"),
            Map.entry("minecraft:stonecutter", "stonecutting"),
            Map.entry("minecraft:smithing_table", "smithing_transform"));

    /**
     * Build the containers array, sorted by block id so the file is stable across dumps.
     */
    public static JsonArray build(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos scratch = scratchPos(level);
        Map<String, JsonObject> records = new TreeMap<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            BlockState state = block.defaultBlockState();
            BlockEntity blockEntity = probeBlockEntity(block, state);
            boolean stateMenu = probeStateMenu(state, level, PROBE);

            // A block whose menu provider reads the block entity AT the position (the enchanting table,
            // and any mod block shaped like it) answers null above, because we probed empty air — it
            // would drop out of the dump entirely. Only those need the expensive path: place the block
            // in the world for the length of one call, then put back what was there.
            if (!stateMenu && blockEntity != null && !(blockEntity instanceof MenuProvider)
                    && !(blockEntity instanceof Container) && scratch != null) {
                stateMenu = probePlacedMenu(level, scratch, state);
            }

            String kind = classify(blockId, blockEntity, stateMenu);
            if (kind == null) {
                continue;  // neither a GUI nor a holder of items
            }

            JsonObject record = new JsonObject();
            record.addProperty("block", blockId);
            record.addProperty("kind", kind);
            record.addProperty("knowledge_group", knowledgeGroup(blockId));
            record.addProperty("menu", stateMenu || blockEntity instanceof MenuProvider);
            record.addProperty("container", blockEntity instanceof Container);
            if (blockEntity != null) {
                record.addProperty("block_entity", blockEntity.getClass().getSimpleName());
            }
            String recipeStation = RECIPE_STATIONS.get(blockId);
            if (recipeStation != null) {
                record.addProperty("recipe_station", recipeStation);
            }
            records.put(blockId, record);
        }

        // Recipe stations that open no GUI and hold nothing (campfires) are still stations the logic has
        // to gate, so make sure every mapped block made it into the file.
        for (Map.Entry<String, String> entry : RECIPE_STATIONS.entrySet()) {
            if (records.containsKey(entry.getKey()) || !BuiltInRegistries.BLOCK.containsKey(id(entry.getKey()))) {
                continue;
            }
            JsonObject record = new JsonObject();
            record.addProperty("block", entry.getKey());
            record.addProperty("kind", "station");
            record.addProperty("knowledge_group", knowledgeGroup(entry.getKey()));
            record.addProperty("menu", false);
            record.addProperty("container", false);
            record.addProperty("recipe_station", entry.getValue());
            records.put(entry.getKey(), record);
        }

        JsonArray array = new JsonArray();
        records.values().forEach(array::add);
        return array;
    }

    /** {@code station} / {@code container} / {@code null} when the block is neither. */
    private static String classify(String blockId, BlockEntity blockEntity, boolean stateMenu) {
        if (STATION_HOLDOUTS.contains(blockId)) {
            return "station";
        }
        if (CONTAINER_HOLDOUTS.contains(blockId)) {
            return "container";
        }
        if (blockEntity instanceof RecipeCraftingHolder || blockEntity instanceof CraftingContainer) {
            return "station";  // runs recipes
        }
        if (blockEntity instanceof Container) {
            return "container";  // stores items
        }
        if (stateMenu || blockEntity instanceof MenuProvider) {
            return "station";  // a GUI with no storage behind it
        }
        return null;
    }

    /**
     * The Knowledge a block shares: its own id, unless it is a dyed variant of another registered block
     * (the shulker boxes), in which case the undyed block. Derived from {@link DyeColor} rather than a
     * name list so a new colour or a modded dyed container collapses on its own.
     */
    private static String knowledgeGroup(String blockId) {
        Identifier identifier = id(blockId);
        if (identifier == null) {
            return blockId;
        }
        String path = identifier.getPath();
        for (DyeColor colour : DyeColor.values()) {
            String prefix = colour.getName() + "_";
            if (!path.startsWith(prefix)) {
                continue;
            }
            Identifier undyed = Identifier.fromNamespaceAndPath(identifier.getNamespace(),
                    path.substring(prefix.length()));
            if (BuiltInRegistries.BLOCK.containsKey(undyed)) {
                return undyed.toString();
            }
        }
        return blockId;
    }

    /**
     * A throwaway block entity for the class probes. Some blocks build theirs from state that an empty
     * probe position can't supply, so a failure just means "no block entity to inspect".
     */
    private static BlockEntity probeBlockEntity(Block block, BlockState state) {
        if (!(block instanceof EntityBlock entityBlock)) {
            return null;
        }
        try {
            return entityBlock.newBlockEntity(PROBE, state);
        } catch (Exception exception) {
            return null;
        }
    }

    /** Whether the state offers a menu at {@code pos} (true for the non-block-entity workstations). */
    private static boolean probeStateMenu(BlockState state, ServerLevel level, BlockPos pos) {
        try {
            return state.getMenuProvider(level, pos) != null;
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Places {@code state} at {@code pos} just long enough to ask it for a menu, then restores what was
     * there. Needed for blocks whose menu provider resolves the block entity at the position — against
     * empty air they answer null, and the enchanting table (one of the two gates that already existed)
     * silently dropped out of the dump because of it.
     */
    private static boolean probePlacedMenu(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState previous = level.getBlockState(pos);
        try {
            level.setBlock(pos, state, PROBE_FLAGS);
            return probeStateMenu(state, level, pos);
        } catch (Exception exception) {
            return false;
        } finally {
            try {
                level.setBlock(pos, previous, PROBE_FLAGS);
            } catch (Exception exception) {
                AEM.LOGGER.warn("Containers dump: could not restore {} at {}", previous, pos, exception);
            }
        }
    }

    /**
     * A spot to place the probe blocks: just under the build ceiling at the origin, which is empty air
     * in any normal world. The chunk is loaded up front so the placement can't silently no-op. Returns
     * null if that isn't possible, which just means the placed probe is skipped.
     */
    private static BlockPos scratchPos(ServerLevel level) {
        BlockPos pos = new BlockPos(0, level.getMaxY() - 10, 0);
        if (!level.isInsideBuildHeight(pos)) {
            return null;
        }
        try {
            level.getChunk(pos);
            return pos;
        } catch (Exception exception) {
            AEM.LOGGER.warn("Containers dump: no scratch chunk, block-entity menu probe skipped", exception);
            return null;
        }
    }

    private static Identifier id(String blockId) {
        try {
            return Identifier.parse(blockId);
        } catch (Exception exception) {
            return null;
        }
    }
}
