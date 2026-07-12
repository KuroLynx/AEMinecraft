package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.client.gui.WorldListDescription;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewrites the secondary line of each world entry in the world-selection list. Vanilla builds it as
 * "{@code <folder> (<last played>)}"; we swap in an Archipelago-aware description (slot + server, or
 * "Not compatible with Archipelago") once the entry has finished constructing, mutating the existing
 * {@code idAndLastPlayedText} widget so its row-width clamping is preserved.
 *
 * @see WorldListDescription
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry")
public abstract class WorldListEntryMixin {
    @Shadow @Final private LevelSummary summary;
    @Shadow @Final private StringWidget idAndLastPlayedText;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void archipelago_euclesia$rewriteDescription(CallbackInfo ci) {
        Component description = WorldListDescription.of(this.summary);
        this.idAndLastPlayedText.setMessage(description);
        this.idAndLastPlayedText.setTooltip(Tooltip.create(description));
    }
}
