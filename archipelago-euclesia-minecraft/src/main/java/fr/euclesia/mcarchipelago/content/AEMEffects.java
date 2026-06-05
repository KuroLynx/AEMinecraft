package fr.euclesia.mcarchipelago.content;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Registers the mod's custom status effects used by the trap items (see {@code TrapEffects}). Like the
 * other custom registrations, this runs at mod init, before the registries freeze.
 */
public final class AEMEffects {
    /** "Butterfingers": repeatedly fumbles the held stack — see {@link ButterfingersMobEffect}. */
    public static final Holder<MobEffect> BUTTERFINGERS = register("butterfingers",
            new ButterfingersMobEffect(MobEffectCategory.HARMFUL, 0x6E4B2A));

    /** "Skittish loot": nearby dropped items flee the victim — see {@link SkittishLootMobEffect}. */
    public static final Holder<MobEffect> SKITTISH_LOOT = register("skittish_loot",
            new SkittishLootMobEffect(MobEffectCategory.HARMFUL, 0x3A2E5C));

    private AEMEffects() {}

    private static Holder<MobEffect> register(String path, MobEffect effect) {
        return Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
                Identifier.fromNamespaceAndPath(AEM.MOD_ID, path), effect);
    }

    /** Loads this class so its static registration runs. Call once during mod init. */
    public static void register() {
        // Touching the fields above triggers registration on class load; nothing else to do.
    }
}
