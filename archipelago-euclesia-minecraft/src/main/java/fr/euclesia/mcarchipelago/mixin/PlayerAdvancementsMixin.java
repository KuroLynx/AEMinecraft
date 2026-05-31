package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.AdvancementBridge;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    @Inject(method = "award", at = @At("RETURN"))
    private void archipelago_euclesia$onAward(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && getOrStartProgress(holder).isDone()) {
            AdvancementBridge.onCompleted(player, holder.id().toString());
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
