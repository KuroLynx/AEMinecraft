package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.registry.AEMRegistries;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import fr.euclesia.mcarchipelago.server.ap.ArchipelagoChatListener;
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
 * Rewrites an unlock tile's description in Archipelago's terms. It names the item as AP does
 * ("Structure Unlock: Ancient City"), in AP's colour for its classification. While the item has not
 * arrived it also says where it is: once hinted, one line per copy still waiting ("Structure Unlock:
 * Ancient City @ Location in Slot's world"); until then, a reminder that holding the tile asks for a
 * hint.
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
        AEMRegistries registries = AEM.ARCHIPELAGO.client().registries();
        APTrackerRegistry.Tracker tracker = registries.apTrackers().get(id.toString());
        if (tracker == null || !APTrackerRegistry.KIND_UNLOCK.equals(tracker.kind()) || tracker.itemId() == null) {
            return;
        }
        Component base = archipelago_euclesia$apName(cir.getReturnValue(), tracker, registries);
        if (registries.apItems().receivedCount(tracker.itemId()) >= tracker.count()) {
            cir.setReturnValue(base);
            return;
        }
        List<HintLocationListener.Spot> spots = HintLocationListener.spots(tracker.itemId());
        if (spots.isEmpty()) {
            cir.setReturnValue(base.copy().append("\n").append(Component.translatable("gui.aem.hint.hold")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
            return;
        }
        MutableComponent description = Component.empty();
        for (HintLocationListener.Spot spot : spots) {
            if (!description.getSiblings().isEmpty()) {
                description.append("\n");
            }
            description.append(Component.translatable("gui.aem.hint.spot", base,
                    Component.literal(spot.location()).withStyle(ChatFormatting.GREEN),
                    Component.literal(spot.player()).withStyle(ChatFormatting.YELLOW))
                    .withStyle(ChatFormatting.WHITE)); // the joining words; each argument keeps its own colour
        }
        cir.setReturnValue(description);
    }

    /**
     * The item's AP name in its AP colour, or the original description when the name is unknown. A
     * progressive tile keeps its " (level N)" — the pack's own description is the one place that says
     * which level this tile is.
     */
    private static Component archipelago_euclesia$apName(Component original, APTrackerRegistry.Tracker tracker,
                                                         AEMRegistries registries) {
        String name = registries.apItems().name(tracker.itemId()).orElse(null);
        if (name == null) {
            return original;
        }
        String text = original.getString();
        int level = text.lastIndexOf(" (level ");
        MutableComponent apName = Component.literal(level >= 0 ? name + text.substring(level) : name);
        return tracker.flags() == null ? apName : apName.withStyle(ArchipelagoChatListener.itemColor(tracker.flags()));
    }
}
