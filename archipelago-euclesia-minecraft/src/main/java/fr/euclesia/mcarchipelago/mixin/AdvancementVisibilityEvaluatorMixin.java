package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.server.advancements.AdvancementVisibilityEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Restricts advancement-screen visibility to the advancements that are Archipelago checks
 * this seed, so non-checks (e.g. challenge advancements when challenge_sanity is off) no
 * longer appear at all — rather than revealing every advancement.
 *
 * <p>The evaluator reports each node's computed visibility through
 * {@code Output.accept(node, visible)}. We redirect that call and override the result with
 * "is this advancement an active Archipelago location" (see {@code APLocationRegistry}). This
 * deliberately bypasses the evaluator's 2-level visibility propagation, so a non-check
 * sitting next to a real check is not leaked into view.
 *
 * <p>While not connected to Archipelago (no location data yet), we fall back to revealing
 * everything; the connection re-runs this evaluation via
 * {@code AdvancementBridge.reloadOnlinePlayers()} on connect, so the screen corrects itself.
 */
@Mixin(AdvancementVisibilityEvaluator.class)
public class AdvancementVisibilityEvaluatorMixin {
    @Redirect(
            method = "evaluateVisibility(Lnet/minecraft/advancements/AdvancementNode;Lit/unimi/dsi/fastutil/Stack;Ljava/util/function/Predicate;Lnet/minecraft/server/advancements/AdvancementVisibilityEvaluator$Output;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/advancements/AdvancementVisibilityEvaluator$Output;accept(Lnet/minecraft/advancements/AdvancementNode;Z)V"))
    private static void archipelago_euclesia$onlyChecks(AdvancementVisibilityEvaluator.Output output,
                                                        AdvancementNode node, boolean visible) {
        output.accept(node, archipelago_euclesia$shouldShow(node));
    }

    @Unique
    private static boolean archipelago_euclesia$shouldShow(AdvancementNode node) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        if (!client.state().isConnected() || !client.registries().apLocations().hasLocations()) {
            return true; // not connected / no data yet: reveal all (re-evaluated on connect)
        }
        String id = node.holder().id().toString();
        // Real advancement checks this seed.
        if (client.registries().apLocations().isActiveLocation(id)) {
            return true;
        }
        // Tracker tabs: each active tracker tile and the non-empty category tab-roots.
        APTrackerRegistry trackers = client.registries().apTrackers();
        if (trackers.isTracker(id) || trackers.isActiveCategory(id)) {
            return true;
        }
        // The main tab is always present once connected: its root plus the two goal tiles, which
        // carry the native X/Y goal progress regardless of which other trackers are active.
        return id.equals(APTrackerRegistry.TAB_ROOT_ID)
                || id.equals(APTrackerRegistry.GOAL_ADVANCEMENTS_ID)
                || id.equals(APTrackerRegistry.GOAL_BOSSES_ID);
    }
}
