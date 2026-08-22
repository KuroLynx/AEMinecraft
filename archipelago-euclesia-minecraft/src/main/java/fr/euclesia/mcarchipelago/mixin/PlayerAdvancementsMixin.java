package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.AdvancementBridge;
import fr.euclesia.mcarchipelago.server.gameplay.StartDimensionService;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Shadow
    private AdvancementTree tree;

    @Shadow
    @Final
    private Set<AdvancementNode> rootsToUpdate;

    @Shadow
    private boolean isFirstPacket;

    @Shadow
    public abstract AdvancementProgress getOrStartProgress(AdvancementHolder advancement);

    /**
     * The story "We Need to Go Deeper!" advancement — distinct from the Nether tab root
     * ({@code nether/root}), which shares the title but must still be granted on a Nether start.
     */
    @Unique
    private static final String AEM_ENTER_THE_NETHER = "minecraft:story/enter_the_nether";

    /**
     * Blocks "We Need to Go Deeper" from being granted by the Nether-start placement teleport (and
     * thus from sending its location check). Only this one advancement is suppressed — {@code nether/root}
     * still fires — so the player earns it later by travelling through a real portal.
     */
    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$suppressNetherStartEntry(AdvancementHolder holder, String criterion,
                                                               CallbackInfoReturnable<Boolean> cir) {
        if (StartDimensionService.isSuppressingNetherEntryAdvancement()
                && AEM_ENTER_THE_NETHER.equals(holder.id().toString())) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Every earned criterion goes to the run: the one that finishes an advancement as a completion
     * (which shares the whole thing), any other as a single step, so half-done multi-criterion
     * advancements pool across the server instead of stranding in one player's file.
     */
    @Inject(method = "award", at = @At("RETURN"))
    private void archipelago_euclesia$onAward(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        if (getOrStartProgress(holder).isDone()) {
            AdvancementBridge.onCompleted(player, holder.id().toString());
        } else {
            AdvancementBridge.onCriterion(player, holder, criterion);
        }
    }

    /**
     * On the first advancements packet, queue every root in the tree for a visibility
     * update so all tabs are sent — not just roots the player has touched. Combined
     * with {@code AdvancementVisibilityEvaluatorMixin} (which forces every node
     * visible), this reveals every advancement and every tab from the start.
     */
    @Inject(method = "flushDirty", at = @At("HEAD"))
    private void archipelago_euclesia$revealAllRoots(ServerPlayer player, boolean showAdvancements, CallbackInfo ci) {
        if (this.isFirstPacket && this.tree != null) {
            this.tree.roots().forEach(this.rootsToUpdate::add);
        }
    }
}
