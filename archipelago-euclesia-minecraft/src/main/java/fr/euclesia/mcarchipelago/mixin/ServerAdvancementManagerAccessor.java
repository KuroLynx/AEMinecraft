package fr.euclesia.mcarchipelago.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes the manager's advancement map so {@code RootAdvancementService} can swap the Archipelago
 * tab-root advancement for a runtime-built version with the right number of goal criteria. The field
 * is not final, so it can be reassigned; the {@code tree()} is rebuilt separately.
 */
@Mixin(ServerAdvancementManager.class)
public interface ServerAdvancementManagerAccessor {
    @Accessor("advancements")
    Map<Identifier, AdvancementHolder> archipelago_euclesia$getAdvancements();

    @Accessor("advancements")
    void archipelago_euclesia$setAdvancements(Map<Identifier, AdvancementHolder> advancements);
}
