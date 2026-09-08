package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData.InventoryLock;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which of the player's own inventory slots are usable, per the {@code inventory_lock}
 * option ({@link InventoryLock}). A run-wide setting, not a per-player one — a Minecraft server
 * here is many people sharing one Archipelago slot, so "Progressive Inventory Slot" copies
 * received are shared and every player's inventory unlocks together (same reasoning
 * {@link StructureFinderService#tier()} already documents for its own item count).
 *
 * <p>Indices below are the player's own vanilla {@code Inventory} container's, which is stable
 * across Minecraft versions regardless of remapping elsewhere: hotbar 0-8, main inventory 9-35
 * (top row 9-17, middle 18-26, the row closest to the hotbar 27-35), armor 36-39, offhand 40.
 * (Not to be confused with {@code InventoryMenu}'s own slot-list indices, which differ — this
 * class only ever looks at a {@code Slot}'s index into its own {@code container}.)
 */
public final class InventoryLockService {
    /** Archipelago item whose receipt unlocks more slots in progressive mode. */
    public static final String AP_ITEM = "Progressive Inventory Slot";

    private static final int HOTBAR_START = 0;
    private static final int HOTBAR_END = 9;
    private static final int REST_START = 9;
    private static final int REST_END = 27;
    private static final int NEAREST_ROW_START = 27;
    private static final int NEAREST_ROW_END = 36;
    private static final int ARMOR_START = 36;
    private static final int ARMOR_END = 40;
    private static final int OFFHAND = 40;

    private InventoryLockService() {}

    /** Whether {@code containerSlotIndex} (an index into the player's own Inventory) is locked. */
    public static boolean isLocked(int containerSlotIndex) {
        InventoryLock lock = current();
        return switch (lock.mode()) {
            case DISABLED -> false;
            case FIXED -> isLockedFixed(lock, containerSlotIndex);
            case PROGRESSIVE -> isLockedProgressive(lock, containerSlotIndex);
        };
    }

    /**
     * How many slots are currently unlocked — for client-facing sync/UI, not per-slot enforcement.
     * {@code Integer.MAX_VALUE} when disabled (nothing is ever locked).
     */
    public static int unlockedCount() {
        InventoryLock lock = current();
        return switch (lock.mode()) {
            case DISABLED -> Integer.MAX_VALUE;
            case FIXED -> lock.slots() + (lock.offhand() ? 1 : 0) + (lock.armor() ? 4 : 0);
            case PROGRESSIVE -> progressiveUnlockedCount(lock);
        };
    }

    private static InventoryLock current() {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return InventoryLock.DISABLED;
        }
        return AEM.ARCHIPELAGO.client().state().parsedSlotData().inventoryLock();
    }

    /**
     * Fixed mode: offhand/armor are simple exemptions (true = always usable, false = locked for
     * the whole game — there is no item to unlock them here), independent of {@code slots}. The
     * 36-slot hotbar+main pool opens its first {@code slots} in priority order (hotbar, then the
     * row closest to the hotbar, then the two remaining rows).
     */
    private static boolean isLockedFixed(InventoryLock lock, int index) {
        if (index == OFFHAND) {
            return !lock.offhand();
        }
        if (index >= ARMOR_START && index < ARMOR_END) {
            return !lock.armor();
        }
        int rank = poolRank(index);
        return rank < 0 || rank >= lock.slots();
    }

    /**
     * Progressive mode: offhand/armor (when included) are folded into the same priority-ordered
     * unlock sequence as the 36-slot pool, per {@link #progressiveOrder}. A slot not included at
     * all (offhand/armor when their flag is false) is never locked.
     */
    private static boolean isLockedProgressive(InventoryLock lock, int index) {
        List<Integer> order = progressiveOrder(lock);
        int rank = order.indexOf(index);
        return rank >= 0 && rank >= progressiveUnlockedCount(lock);
    }

    private static int progressiveUnlockedCount(InventoryLock lock) {
        int received = AEM.ARCHIPELAGO.client().registries().apItems().receivedCount(AP_ITEM);
        return Math.min(lock.totalLocked(), received * lock.slotsPerItem());
    }

    /**
     * This container index's 0-based rank within the 36-slot hotbar+main pool, in priority order:
     * hotbar (0-8), the row closest to the hotbar (9-17), the two remaining rows (18-35). -1 if
     * {@code index} is not part of that pool (offhand/armor).
     */
    private static int poolRank(int index) {
        if (index >= HOTBAR_START && index < HOTBAR_END) {
            return index - HOTBAR_START;
        }
        if (index >= NEAREST_ROW_START && index < NEAREST_ROW_END) {
            return 9 + (index - NEAREST_ROW_START);
        }
        if (index >= REST_START && index < REST_END) {
            return 18 + (index - REST_START);
        }
        return -1;
    }

    /**
     * The full progressive unlock order for this seed: hotbar, then offhand (if included), then
     * the row closest to the hotbar, then armor (if included), then the two remaining rows — per
     * the priority the user specified. Length is {@link InventoryLock#totalLocked()}.
     *
     * <p>Only {@code slots} worth of the 36-slot hotbar+main pool is ever locked, counted from the
     * LOW-priority end: the two remaining rows lock first, then (only once {@code slots} exceeds
     * 18) the row closest to the hotbar, then (only past 27) the hotbar itself. So the hotbar, and
     * the row next to it, are protected by construction under any ordinary configuration — a
     * player is never left with an unusable hotbar just because {@code slots} is small. Mirrors
     * {@code InventoryLock.armor_unlock_items} on the apworld side exactly: that is what makes the
     * multiworld's fill place "Progressive Inventory Slot" copies where this says they're needed.
     */
    private static List<Integer> progressiveOrder(InventoryLock lock) {
        int slots = lock.slots();
        int restLocked = Math.min(slots, 18);
        int nearestRowLocked = Math.min(Math.max(slots - 18, 0), 9);
        int hotbarLocked = Math.min(Math.max(slots - 27, 0), 9);

        List<Integer> order = new ArrayList<>(lock.totalLocked());
        for (int i = 0; i < hotbarLocked; i++) {
            order.add(HOTBAR_START + i);
        }
        if (lock.offhand()) {
            order.add(OFFHAND);
        }
        for (int i = 0; i < nearestRowLocked; i++) {
            order.add(NEAREST_ROW_START + i);
        }
        if (lock.armor()) {
            for (int i = ARMOR_START; i < ARMOR_END; i++) {
                order.add(i);
            }
        }
        for (int i = 0; i < restLocked; i++) {
            order.add(REST_START + i);
        }
        return order;
    }
}
