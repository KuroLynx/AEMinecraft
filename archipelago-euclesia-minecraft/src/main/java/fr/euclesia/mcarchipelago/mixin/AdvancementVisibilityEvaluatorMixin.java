package fr.euclesia.mcarchipelago.mixin;

import net.minecraft.advancements.AdvancementNode;
import net.minecraft.server.advancements.AdvancementVisibilityEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Predicate;

/**
 * Reveals every advancement (and therefore every tab) regardless of progress, so the
 * Archipelago logic overlay can colour the whole tree from the start.
 *
 * <p>The server only sends advancements the {@link AdvancementVisibilityEvaluator}
 * deems visible. Its {@code predicate} argument is the "treat this node as shown"
 * test; replacing it with an always-true predicate makes every displayable,
 * non-hidden advancement visible (intentionally {@code hidden}-flagged advancements
 * and structural no-display nodes are still left alone).
 */
@Mixin(AdvancementVisibilityEvaluator.class)
public class AdvancementVisibilityEvaluatorMixin {
    @ModifyVariable(method = "evaluateVisibility(Lnet/minecraft/advancements/AdvancementNode;Ljava/util/function/Predicate;Lnet/minecraft/server/advancements/AdvancementVisibilityEvaluator$Output;)V", at = @At("HEAD"), argsOnly = true, name = "isDone")
    private static Predicate<AdvancementNode> archipelago_euclesia$revealAll(Predicate<AdvancementNode> isDone) {
        return _ -> true;
    }
}
