package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.utils.AdvancementIdHolder;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stamps each advancement's display with its id — see {@link AdvancementIdHolder}. */
@Mixin(AdvancementHolder.class)
public abstract class AdvancementHolderMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void archipelago_euclesia$stampDisplay(Identifier id, Advancement value, CallbackInfo ci) {
        value.display().ifPresent(display -> ((AdvancementIdHolder) display).aem$setAdvancementId(id));
    }
}
