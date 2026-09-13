package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.utils.AdvancementIdHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives {@link DisplayInfo} the id slot {@link AdvancementIdHolder} describes. */
@Mixin(DisplayInfo.class)
public abstract class DisplayInfoMixin implements AdvancementIdHolder {
    @Unique
    private Identifier archipelago_euclesia$advancementId;

    @Override
    public Identifier aem$advancementId() {
        return archipelago_euclesia$advancementId;
    }

    @Override
    public void aem$setAdvancementId(Identifier id) {
        archipelago_euclesia$advancementId = id;
    }
}
