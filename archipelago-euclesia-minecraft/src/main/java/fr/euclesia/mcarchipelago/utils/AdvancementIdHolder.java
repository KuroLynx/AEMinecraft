package fr.euclesia.mcarchipelago.utils;

import net.minecraft.resources.Identifier;

/**
 * Mixed into {@code DisplayInfo}: which advancement it belongs to. Vanilla keeps the id on the
 * holder only, and every advancement screen — vanilla's and the compat mods' — asks the display for
 * its description, so this is what lets one hook on {@code getDescription} know whose it is.
 * Set by {@code AdvancementHolderMixin} whenever a holder is built, on either side.
 */
public interface AdvancementIdHolder {
    Identifier aem$advancementId();

    void aem$setAdvancementId(Identifier id);
}
