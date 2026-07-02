package fr.euclesia.mcarchipelago.server.gameplay;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps BlazeandCave's item rewards from smuggling gated items past the Archipelago economy. BACAP
 * hands out items with plain {@code give} commands in its {@code bacap_rewards:*} advancement-reward
 * functions, which bypasses the material/knowledge pickup gates ({@link MaterialLockService}).
 *
 * <p>{@link fr.euclesia.mcarchipelago.mixin.AdvancementRewardsMixin} snapshots the player's inventory
 * around a BACAP reward grant; any item the reward newly added that is <em>currently pickup-blocked</em>
 * (i.e. something the slot isn't supposed to have yet) is pulled back out of the inventory and dropped
 * on the floor. The existing pickup lock then refuses to let it back in until the corresponding
 * Archipelago unlock arrives, so it simply despawns — while allowed reward items are left untouched.
 *
 * <p>Runs entirely on the server thread. A thread-local stack of snapshots handles the (rare) case of a
 * reward function that recursively grants another advancement.
 */
public final class BacapRewardService {
    /** Namespace of BACAP's reward functions ({@code bacap_rewards:<category>/<advancement>}). */
    private static final String BACAP_REWARDS_NAMESPACE = "bacap_rewards";

    private static final ThreadLocal<Deque<Map<Item, Integer>>> SNAPSHOTS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private BacapRewardService() {}

    /** Whether {@code functionId} is one of BACAP's reward functions. */
    public static boolean isBacapReward(Identifier functionId) {
        return functionId != null && BACAP_REWARDS_NAMESPACE.equals(functionId.getNamespace());
    }

    /** Records the pre-reward inventory contents so {@link #afterReward} can diff against them. */
    public static void beforeReward(ServerPlayer player) {
        SNAPSHOTS.get().push(countItems(player));
    }

    /**
     * Diffs the inventory against the matching {@link #beforeReward} snapshot and drops any newly
     * granted item that is currently pickup-blocked. Must be paired 1:1 with {@code beforeReward}.
     */
    public static void afterReward(ServerPlayer player) {
        Map<Item, Integer> before = SNAPSHOTS.get().poll();
        if (before == null) {
            return; // Unbalanced call (should not happen); nothing to diff against.
        }
        Map<Item, Integer> after = countItems(player);
        after.forEach((item, afterCount) -> {
            int granted = afterCount - before.getOrDefault(item, 0);
            if (granted <= 0) {
                return;
            }
            // Only pull back items the slot isn't allowed to pick up yet; leave allowed rewards alone.
            if (!MaterialLockService.isPickupBlocked(new ItemStack(item))) {
                return;
            }
            dropFromInventory(player, item, granted);
        });
    }

    /** Total count of each item type currently in {@code player}'s inventory. */
    private static Map<Item, Integer> countItems(ServerPlayer player) {
        Map<Item, Integer> counts = new HashMap<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** Removes up to {@code amount} of {@code item} from the inventory and drops what was removed. */
    private static void dropFromInventory(ServerPlayer player, Item item, int amount) {
        Inventory inventory = player.getInventory();
        int remaining = amount;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        int removed = amount - remaining;
        if (removed > 0) {
            player.drop(new ItemStack(item, removed), false);
        }
    }
}
