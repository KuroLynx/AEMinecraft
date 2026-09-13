package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import fr.euclesia.mcarchipelago.server.ap.HintLocationListener;
import fr.euclesia.mcarchipelago.utils.AdvancementIdHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Tells an unlock tile's reader where its item is. Once hinted, the description becomes one line per
 * copy still waiting ("Unlock X @ Location in Slot's world"); until then it gains a reminder that
 * holding the tile asks for a hint. Nothing once the tile is satisfied — the item has arrived, so
 * where it was no longer matters.
 *
 * <p>Hooked on the display rather than the widget so every advancement screen shows it, vanilla's
 * and the compat mods' alike. Screens read the description when they build a tile, so a hint that
 * lands while the screen is open shows the next time it is opened.
 */
@Mixin(DisplayInfo.class)
public abstract class DisplayInfoDescriptionMixin {
    @Inject(method = "getDescription", at = @At("RETURN"), cancellable = true)
    private void archipelago_euclesia$hintLines(CallbackInfoReturnable<Component> cir) {
        Identifier id = ((AdvancementIdHolder) this).aem$advancementId();
        if (id == null) {
            return;
        }
        APTrackerRegistry.Tracker tracker = AEM.ARCHIPELAGO.client().registries().apTrackers().get(id.toString());
        if (tracker == null || !APTrackerRegistry.KIND_UNLOCK.equals(tracker.kind()) || tracker.itemId() == null
                || AEM.ARCHIPELAGO.client().registries().apItems().receivedCount(tracker.itemId()) >= tracker.count()) {
            return;
        }
        Component base = cir.getReturnValue();
        List<HintLocationListener.Spot> spots = HintLocationListener.spots(tracker.itemId());
        if (spots.isEmpty()) {
            cir.setReturnValue(base.copy().append("\n").append(Component.translatable("gui.aem.hint.hold")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
            return;
        }
        // The hint takes the description's place, one line per copy: "Unlock X @ Location in Slot's world".
        MutableComponent description = Component.empty();
        for (HintLocationListener.Spot spot : spots) {
            if (!description.getSiblings().isEmpty()) {
                description.append("\n");
            }
            description.append(Component.translatable("gui.aem.hint.spot", base,
                    Component.literal(spot.location()).withStyle(ChatFormatting.GREEN),
                    Component.literal(spot.player()).withStyle(ChatFormatting.YELLOW)));
        }
        cir.setReturnValue(description);
    }
}
