package fr.euclesia.mcarchipelago.client.compat.advancementsreloaded;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the whole Advancements Reloaded compat mixin set behind that mod actually being loaded.
 *
 * <p>The mixins in this package target classes from {@code codes.atomys.advr}
 * ({@code AdvancementReloadedWidget}, {@code AdvancementReloadedScreen}) by name (see the
 * {@code targets = "..."} string form, not a {@code .class} literal) precisely so referencing
 * them never requires those classes to exist on the classpath. This plugin is what makes that
 * safe: without it Mixin would still try to apply the mixins and fail loudly when the target
 * class is missing.
 */
public final class AdvReloadedMixinPlugin implements IMixinConfigPlugin {
    private static final String TARGET_MOD_ID = "advancements_reloaded";

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FabricLoader.getInstance().isModLoaded(TARGET_MOD_ID);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
