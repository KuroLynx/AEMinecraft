package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.client.connect.APConnectConfig;
import fr.euclesia.mcarchipelago.client.gui.ArchipelagoCreateTab;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Adds an "Archipelago" tab to the create-world screen and stages its connection details when the
 * world is created. The staged details are written into the new world's folder once its server
 * starts (see {@code MinecraftEventBridge}), so joining that world connects to its Archipelago slot.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
    @Unique
    private ArchipelagoCreateTab archipelago_euclesia$tab;

    @ModifyArg(
            method = "init",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/tabs/TabNavigationBar$Builder;"
                            + "addTabs([Lnet/minecraft/client/gui/components/tabs/Tab;)"
                            + "Lnet/minecraft/client/gui/components/tabs/TabNavigationBar$Builder;"))
    private Tab[] archipelago_euclesia$addArchipelagoTab(Tab[] tabs) {
        archipelago_euclesia$tab = new ArchipelagoCreateTab(Minecraft.getInstance().font);
        Tab[] extended = Arrays.copyOf(tabs, tabs.length + 1);
        extended[tabs.length] = archipelago_euclesia$tab;
        return extended;
    }

    @Inject(method = "onCreate", at = @At("HEAD"))
    private void archipelago_euclesia$stageConnection(CallbackInfo ci) {
        if (archipelago_euclesia$tab == null) {
            return;
        }
        APWorldConnection connection = new APWorldConnection();
        connection.address = archipelago_euclesia$tab.address();
        connection.port = archipelago_euclesia$tab.port();
        connection.slot = archipelago_euclesia$tab.slot();
        connection.password = archipelago_euclesia$tab.password();
        APWorldConnection.setPending(connection);

        // Remember the details globally too, so the next create pre-fills with what was just used.
        APConnectConfig config = APConnectConfig.get();
        config.address = connection.address;
        config.port = connection.port;
        config.slot = connection.slot;
        config.password = connection.password;
        config.save();
    }
}
