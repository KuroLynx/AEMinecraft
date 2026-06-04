package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
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
 * Drives the two native-progress goal tiles on the main Archipelago tab:
 * <ul>
 *   <li>{@code aem:goal/advancements} — "X/Y" where Y = {@code advancements_required} and X = goal
 *       advancements completed; and</li>
 *   <li>{@code aem:goal/bosses} — "M/N" where N = the bosses required by the goal and M = those
 *       killed.</li>
 * </ul>
 *
 * <p>Both tiles ship statically with a single impossible criterion, so on connect we rebuild them
 * with the right criteria and swap them into the server's advancement manager + tree; Minecraft then
 * renders the criterion fraction natively. The advancements tile uses {@code Y} anonymous criteria
 * (one awarded per completed active advancement). The bosses tile uses <em>one criterion named per
 * boss</em>, awarded by boss identity when that boss is killed — naturally idempotent and restored
 * from disk on rejoin, so no counting is needed.
 */
public final class RootAdvancementService {
    private static final Identifier ADVANCEMENTS_ID = Identifier.parse(APTrackerRegistry.GOAL_ADVANCEMENTS_ID);
    private static final Identifier BOSSES_ID = Identifier.parse(APTrackerRegistry.GOAL_BOSSES_ID);

    /** Currently-installed goal-tile holders (rebuilt, or the static one when nothing to track). */
    private static volatile AdvancementHolder advancementsHolder;
    private static volatile AdvancementHolder bossesHolder;

    /** Advancements the goal requires this seed, capped at how many actually exist (0 = none). */
    private static volatile int requiredAdvancements;

    private RootAdvancementService() {}

    /**
     * Rebuilds both goal tiles from the slot data and installs them. Must run on the server thread.
     * The advancements tile gets {@code advancements_required} anonymous criteria; the bosses tile
     * one criterion per goal boss (its namespace-stripped slug).
     */
    public static void rebuild(MinecraftServer server, APSlotData slotData) {
        ServerAdvancementManager manager = server.getAdvancements();
        ServerAdvancementManagerAccessor accessor = (ServerAdvancementManagerAccessor) manager;
        Map<Identifier, AdvancementHolder> changed = new HashMap<>();

        // Advancements tile: Y anonymous criteria (c0..c{Y-1}). Cap Y at the number of advancements
        // that actually exist this seed — mirrors the apworld completion condition (which caps the
        // same way), so the goal can't demand more advancements than are obtainable.
        requiredAdvancements = Math.min(slotData.advancementsRequired(), countActiveAdvancements(server));
        List<String> advCriteria = new ArrayList<>();
        for (int i = 0; i < requiredAdvancements; i++) {
            advCriteria.add("c" + i);
        }
        advancementsHolder = rebuildTile(manager, ADVANCEMENTS_ID, advCriteria, changed);

        // Bosses tile: one criterion per required boss, named by slug.
        bossesHolder = rebuildTile(manager, BOSSES_ID, requiredBossSlugs(slotData), changed);

        if (changed.isEmpty()) {
            return;
        }

        // Swap the changed holders into the (immutable) advancement map and rebuild the tree in
        // place so the tabs keep their identical structure — only these tiles' criteria change.
        Map<Identifier, AdvancementHolder> updated = new HashMap<>(accessor.archipelago_euclesia$getAdvancements());
        updated.putAll(changed);
        accessor.archipelago_euclesia$setAdvancements(Map.copyOf(updated));

        AdvancementTree tree = manager.tree();
        tree.clear();
        tree.addAll(accessor.archipelago_euclesia$getAdvancements().values());
        AEM.LOGGER.info("Rebuilt Archipelago goal tiles: {} advancement criteria, {} boss criteria",
                advCriteria.size(), bossesHolder == null ? 0 : bossesHolder.value().criteria().size());
    }

    /**
     * Rebuilds one goal tile with the named criteria (all required) and records it in {@code changed}.
     * Returns the new holder, or the existing one when there are no criteria to track (keeps the
     * static single-criterion tile). {@code null} only if the tile is missing from the datapack.
     */
    private static AdvancementHolder rebuildTile(ServerAdvancementManager manager, Identifier id,
                                                 List<String> criterionNames,
                                                 Map<Identifier, AdvancementHolder> changed) {
        AdvancementHolder existing = manager.get(id);
        if (existing == null) {
            return null;
        }
        if (criterionNames.isEmpty()) {
            return existing;
        }
        Advancement base = existing.value();
        Map<String, Criterion<?>> criteria = new LinkedHashMap<>();
        Criterion<?> impossible = new Criterion<>(CriteriaTriggers.IMPOSSIBLE, new ImpossibleTrigger.TriggerInstance());
        for (String name : criterionNames) {
            criteria.put(name, impossible);
        }
        Advancement rebuilt = new Advancement(
                base.parent(), base.display(), base.rewards(),
                criteria, AdvancementRequirements.allOf(criterionNames), base.sendsTelemetryEvent());
        AdvancementHolder holder = new AdvancementHolder(id, rebuilt);
        changed.put(id, holder);
        return holder;
    }

    /**
     * Goal boss slugs (namespace-stripped) = the slot's {@code boss_list}. The win condition is
     * always "kill every selected boss" (a dragon-only seed just has a one-entry list), so there is
     * no enum to branch on.
     */
    private static List<String> requiredBossSlugs(APSlotData slotData) {
        return slotData.bossSelection().stream().map(RootAdvancementService::slug).distinct().toList();
    }

    private static String slug(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /**
     * Applies the rebuilt goal tiles to a player as they join. Covers the connect-before-join path
     * (main-menu connect): at connect time the player isn't online yet, so the connect-time reload is
     * a no-op and the player would otherwise initialise from the still-static tiles. Rebuilding
     * (idempotent) and reloading here guarantees the client receives the goal-count versions; the
     * boss criteria already granted on disk survive the reload. Runs on the server thread.
     */
    public static void applyOnJoin(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return;
        }
        rebuild(server, AEM.ARCHIPELAGO.client().state().parsedSlotData());
        player.getAdvancements().reload(server.getAdvancements());
        syncProgress(player);
    }

    /**
     * Reconciles the advancements tile's granted criteria to exactly the number of completed active
     * advancements (capped at the criteria count). Idempotent and self-correcting, so it is safe to
     * call live on each completion AND on (re)join — unlike a plain "award the next criterion", which
     * would double-count when {@code scanPlayer} re-fires completions on top of disk-restored progress.
     */
    public static void syncProgress(ServerPlayer player) {
        AdvancementHolder holder = advancementsHolder;
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

    /**
     * Awards the bosses tile's criterion for a killed boss to every online player. A no-op when the
     * killed mob isn't one of the goal's bosses (no matching criterion). Granting by identity makes
     * this idempotent and rejoin-safe.
     */
    public static void recordBossKill(String bossGameId) {
        AdvancementHolder holder = bossesHolder;
        if (holder == null) {
            return;
        }
        String slug = slug(bossGameId);
        if (!holder.value().criteria().containsKey(slug)) {
            return;
        }
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CriterionProgress criterion = player.getAdvancements().getOrStartProgress(holder).getCriterion(slug);
            if (criterion != null && !criterion.isDone()) {
                player.getAdvancements().award(holder, slug);
            }
        }
    }

    /** Advancements the goal requires this seed (capped at how many exist); 0 if none. */
    public static int requiredAdvancements() {
        return requiredAdvancements;
    }

    /** The player's completed advancements that are active Archipelago checks this seed (for the goal). */
    public static int completedGoalAdvancements(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        return server == null ? 0 : countCompletedAdvancements(server, player);
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

    /** Counts the advancements that are active Archipelago checks this seed (the goal's max). */
    private static int countActiveAdvancements(MinecraftServer server) {
        APLocationRegistry locations = AEM.ARCHIPELAGO.client().registries().apLocations();
        int count = 0;
        for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements()) {
            if (locations.isActiveLocation(advancement.id().toString())) {
                count++;
            }
        }
        return count;
    }
}
