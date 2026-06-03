package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.mixin.ServerAdvancementManagerAccessor;
import fr.euclesia.mcarchipelago.registry.APLocationRegistry;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.advancements.criterion.ImpossibleTrigger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes the Archipelago tab-root advancement display native goal progress "X/Y" (X = goal
 * advancements completed, Y = {@code advancements_required}).
 *
 * <p>{@code aem:archipelago} ships statically with a single impossible criterion, so on connect we
 * rebuild it with exactly Y impossible criteria (one requirement group each, all required) and swap
 * it into the server's advancement manager + tree. Minecraft then renders the criterion fraction
 * natively. A criterion is awarded for each active advancement the player completes
 * (see {@link AdvancementBridge#onCompleted}); the root completes once Y are awarded.
 */
public final class RootAdvancementService {
    private static final Identifier ROOT_ID = Identifier.parse(APTrackerRegistry.TAB_ROOT_ID);

    /** The currently-installed root holder (rebuilt, or the static one when no count is required). */
    private static volatile AdvancementHolder rootHolder;

    private RootAdvancementService() {}

    /**
     * Rebuilds the tab root with {@code required} criteria and installs it. Must run on the server
     * thread. No-op when {@code required <= 0} (keeps the static single-criterion root).
     */
    public static void rebuild(MinecraftServer server, int required) {
        ServerAdvancementManager manager = server.getAdvancements();
        AdvancementHolder existing = manager.get(ROOT_ID);
        if (existing == null) {
            return;
        }
        if (required <= 0) {
            rootHolder = existing;
            return;
        }

        Advancement base = existing.value();
        Map<String, Criterion<?>> criteria = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(required);
        Criterion<?> impossible = new Criterion<>(CriteriaTriggers.IMPOSSIBLE, new ImpossibleTrigger.TriggerInstance());
        for (int i = 0; i < required; i++) {
            String name = "c" + i;
            criteria.put(name, impossible);
            names.add(name);
        }
        Advancement rebuilt = new Advancement(
                base.parent(), base.display(), base.rewards(),
                criteria, AdvancementRequirements.allOf(names), base.sendsTelemetryEvent());
        AdvancementHolder holder = new AdvancementHolder(ROOT_ID, rebuilt);
        rootHolder = holder;

        // Swap the holder into the (immutable) advancement map and rebuild the tree in place so the
        // tab keeps its identical structure — only the root's criteria change.
        ServerAdvancementManagerAccessor accessor = (ServerAdvancementManagerAccessor) manager;
        Map<Identifier, AdvancementHolder> updated = new HashMap<>(accessor.archipelago_euclesia$getAdvancements());
        updated.put(ROOT_ID, holder);
        accessor.archipelago_euclesia$setAdvancements(Map.copyOf(updated));

        AdvancementTree tree = manager.tree();
        tree.clear();
        tree.addAll(accessor.archipelago_euclesia$getAdvancements().values());
        AEM.LOGGER.info("Rebuilt Archipelago tab root with {} goal criteria", required);
    }

    /**
     * Applies the rebuilt root to a player as they join. Covers the connect-before-join path
     * (main-menu connect): at connect time the player isn't online yet, so the connect-time reload
     * is a no-op and the player would otherwise initialise from the still-static (or not-yet-swapped)
     * root. Rebuilding (idempotent) and reloading here guarantees the client receives the goal-count
     * version. Runs on the server thread (the JOIN event fires there).
     */
    public static void applyOnJoin(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return;
        }
        rebuild(server, AEM.ARCHIPELAGO.client().state().parsedSlotData().advancementsRequired());
        player.getAdvancements().reload(server.getAdvancements());
        syncProgress(player);
    }

    /**
     * Reconciles the root's granted criteria to exactly the number of completed active advancements
     * (capped at the criteria count). Idempotent and self-correcting, so it is safe to call live on
     * each completion AND on (re)join — unlike a plain "award the next criterion", which would
     * double-count when {@code scanPlayer} re-fires completions on top of disk-restored progress.
     */
    public static void syncProgress(ServerPlayer player) {
        AdvancementHolder holder = rootHolder;
        if (holder == null || holder.value().requirements().size() <= 1) {
            return; // not rebuilt with a goal count
        }
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        List<String> names = new ArrayList<>(holder.value().criteria().keySet());
        int target = Math.min(countCompletedAdvancements(server, player), names.size());
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        for (int i = 0; i < names.size(); i++) {
            CriterionProgress criterion = progress.getCriterion(names.get(i));
            boolean granted = criterion != null && criterion.isDone();
            if (i < target && !granted) {
                player.getAdvancements().award(holder, names.get(i));
            } else if (i >= target && granted) {
                player.getAdvancements().revoke(holder, names.get(i));
            }
        }
    }

    /** Counts the player's completed advancements that are active Archipelago checks this seed. */
    private static int countCompletedAdvancements(MinecraftServer server, ServerPlayer player) {
        APLocationRegistry locations = AEM.ARCHIPELAGO.client().registries().apLocations();
        int count = 0;
        for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements()) {
            if (locations.isActiveLocation(advancement.id().toString())
                    && player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                count++;
            }
        }
        return count;
    }
}
