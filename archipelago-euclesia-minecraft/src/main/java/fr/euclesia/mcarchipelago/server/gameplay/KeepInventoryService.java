package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.registry.APItemRegistry;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A dial between vanilla's "drop everything" and the keepInventory gamerule: on death a share of the
 * player's filled slots is set aside and given back at respawn, and the rest drops as normal loot.
 *
 * <p>How large that share is comes from the {@code keep_inventory} option (see {@link #percent()}):
 * {@code disabled} keeps nothing, {@code active} keeps everything, and {@code progressive} scales
 * from one to the other as Progressive Keep Inventory items arrive — each of the seed's
 * {@code keep_inventory_pool_size} copies is worth an equal slice, so the last one always lands
 * exactly on 100%. Like every other progressive item this is a RUN-wide count, not a personal one: a
 * server is many people sharing one Archipelago slot, so everybody dies with the same odds (hence no
 * player argument on {@link #percent()}).
 *
 * <p>Scope is the vanilla gamerule's: main inventory, armor and offhand — every slot the player's
 * {@link Inventory} container addresses. Experience is deliberately left alone; you drop your XP on
 * death whatever this option says.
 *
 * <p>The kept slots are cleared from the live inventory in {@link #onDeath} rather than filtered out
 * of the drops afterwards, because there is nothing to filter afterwards: by the time the death has
 * resolved, vanilla has turned the inventory into loose {@code ItemEntity}s and the slot each stack
 * came from is gone. Emptying the slot first is how {@link BiomeFinderService} keeps its soulbound
 * compass, and it is what lets respawn put every kept stack back where its owner left it.
 */
public final class KeepInventoryService {
    /** The item whose received count drives the kept percentage under the progressive mode. */
    public static final String ITEM_NAME = "Progressive Keep Inventory";

    /** Slot index -> stack, set aside at death and handed back on the next respawn, per player. */
    private static final Map<UUID, Map<Integer, ItemStack>> savedOnDeath = new HashMap<>();

    private KeepInventoryService() {}

    /**
     * The RUN's kept share, 0 (vanilla) to 100 (everything), for the current seed's mode.
     *
     * <p>A {@code progressive} seed whose pool size never reached the mod (0, e.g. slot data from
     * before the option existed) would divide by zero, so it reads as 0% — vanilla, the same as an
     * unrecognized mode.
     */
    public static double percent() {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return 0.0;
        }
        APSlotData slotData = AEM.ARCHIPELAGO.client().state().parsedSlotData();
        if (slotData == null) {
            return 0.0;
        }
        return switch (slotData.keepInventoryMode()) {
            case "active" -> 100.0;
            case "progressive" -> {
                int poolSize = slotData.keepInventoryPoolSize();
                if (poolSize <= 0) {
                    yield 0.0;
                }
                APItemRegistry items = AEM.ARCHIPELAGO.client().registries().apItems();
                yield Math.min(100.0, items.receivedCount(ITEM_NAME) * 100.0 / poolSize);
            }
            default -> 0.0;  // "disabled", and anything a newer generator might send
        };
    }

    /**
     * Death handler: pick the slots this death keeps, remember them, and empty them so the death loop
     * that follows drops only the rest. Runs before the drops are computed (ALLOW_DEATH), and is a
     * no-op at 0% — a disabled seed never touches the inventory at all.
     *
     * <p>Which slots are kept is drawn from the OCCUPIED ones, not from all 41: keeping "half your
     * slots" has to mean half of what you are actually carrying, or a nearly-empty inventory would
     * lose everything while a full one lost half. The draw is a shuffle rather than a per-slot coin
     * flip, so the count kept is exactly the promised share every time instead of on average.
     */
    public static void onDeath(ServerPlayer player) {
        double percent = percent();
        if (percent <= 0.0) {
            return;
        }

        Inventory inventory = player.getInventory();
        List<Integer> occupied = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                occupied.add(slot);
            }
        }
        if (occupied.isEmpty()) {
            return;
        }

        // At 100% (an 'active' seed, or a maxed-out progressive one) the whole list is kept as-is;
        // shuffling it would only be a waste of entropy.
        if (percent < 100.0) {
            Collections.shuffle(occupied);
            int keptCount = Math.round((float) (occupied.size() * percent / 100.0));
            occupied = occupied.subList(0, Math.min(keptCount, occupied.size()));
        }

        Map<Integer, ItemStack> kept = new HashMap<>();
        for (int slot : occupied) {
            kept.put(slot, inventory.getItem(slot).copy());
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        if (!kept.isEmpty()) {
            savedOnDeath.put(player.getUUID(), kept);
        }
    }

    /**
     * Respawn handler: put every stack set aside at death back in the slot it came from, on the fresh
     * player. No-op when this player kept nothing (a disabled seed, an empty inventory, or a 0% draw).
     *
     * <p>Restoring into exact slots overwrites whatever sits there, so this must run BEFORE the
     * services that hand out items by finding a free slot ({@link BiomeFinderService}) — see the
     * respawn registration in {@code MinecraftEventBridge}.
     */
    public static void restoreOnRespawn(ServerPlayer player) {
        Map<Integer, ItemStack> kept = savedOnDeath.remove(player.getUUID());
        if (kept == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        for (Map.Entry<Integer, ItemStack> entry : kept.entrySet()) {
            int slot = entry.getKey();
            if (slot < inventory.getContainerSize()) {
                inventory.setItem(slot, entry.getValue());
            }
        }
    }
}
