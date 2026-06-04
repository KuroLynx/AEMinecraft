package fr.euclesia.mcarchipelago.server.gameplay;

import com.google.gson.Gson;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData.FillerGrant;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Applies the one-shot effects of received filler and trap items (filler grants its Minecraft items;
 * traps fire a {@link TrapEffects} effect). Definitions come from {@code slot_data} (see
 * {@code minecraft/filler.py}) so the apworld stays the source of truth.
 *
 * <p>Archipelago replays the whole item history on every reconnect (the index-0 resync), so a naive
 * "apply on receipt" would duplicate filler each time. Instead we keep a persisted high-water mark in
 * {@code archipelago_received.json} in the world folder: the number of received items already applied.
 * On each item batch (and on join, for items received while offline) we apply only the items past that
 * mark — against the ordered history in {@link fr.euclesia.mcarchipelago.registry.APItemRegistry} — and
 * advance it. All work runs on the server thread.
 */
public final class FillerTrapService {
    private static final String FILE_NAME = "archipelago_received.json";
    private static final Gson GSON = new Gson();
    /** Largest stack size rolled for a "Random Bullshit" item (kept modest, ignores per-item maxima). */
    private static final int RANDOM_STACK_MAX = 16;

    /** On-disk persistence shape: how many received items have already had their effect applied. */
    private static final class Progress {
        int appliedItemCount;
    }

    private FillerTrapService() {}

    /** Applies pending filler/trap effects to the first online player (after an item batch arrives). */
    public static void applyPendingToAny(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            applyPending(players.get(0));
        }
    }

    /** Applies every received item not yet applied (per the persisted mark) to {@code player}. */
    public static void applyPending(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return;
        }
        List<Long> order = AEM.ARCHIPELAGO.client().registries().apItems().receivedOrder();
        int applied = Math.min(readApplied(server), order.size());
        if (applied >= order.size()) {
            return;
        }
        APSlotData slot = AEM.ARCHIPELAGO.client().state().parsedSlotData();
        for (int i = applied; i < order.size(); i++) {
            applyOne(player, slot, order.get(i));
        }
        writeApplied(server, order.size());
    }

    private static void applyOne(ServerPlayer player, APSlotData slot, long itemId) {
        FillerGrant grant = slot.fillerItems().get(itemId);
        if (grant != null) {
            if (grant.isRandom()) {
                giveRandom(player, grant.randomStacks());
            } else {
                giveItem(player, grant.item(), grant.count());
            }
            return;
        }
        String trap = slot.trapItems().get(itemId);
        if (trap != null) {
            TrapEffects.run(trap, player);
        }
    }

    private static void giveItem(ServerPlayer player, String itemId, int count) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        if (item != null) {
            give(player, new ItemStack(item, count));
        }
    }

    private static void giveRandom(ServerPlayer player, int stacks) {
        RandomSource rng = player.level().getRandom();
        for (int i = 0; i < stacks; i++) {
            BuiltInRegistries.ITEM.getRandom(rng)
                    .ifPresent(holder -> give(player, new ItemStack(holder.value(), 1 + rng.nextInt(RANDOM_STACK_MAX))));
        }
    }

    /** Adds a stack to the inventory (it distributes large counts across slots); drops the overflow. */
    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    private static int readApplied(MinecraftServer server) {
        Path file = progressFile(server);
        if (!Files.exists(file)) {
            return 0;
        }
        try {
            Progress progress = GSON.fromJson(Files.readString(file), Progress.class);
            return progress == null ? 0 : Math.max(0, progress.appliedItemCount);
        } catch (Exception exception) {
            AEM.LOGGER.warn("Failed to read {}", FILE_NAME, exception);
            return 0;
        }
    }

    private static void writeApplied(MinecraftServer server, int count) {
        Progress progress = new Progress();
        progress.appliedItemCount = count;
        try {
            Files.writeString(progressFile(server), GSON.toJson(progress));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Failed to write {}", FILE_NAME, exception);
        }
    }

    private static Path progressFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
    }
}
