package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.connect.WorldLoadResume;
import fr.euclesia.mcarchipelago.client.dump.HeadlessEntitiesDump;
import fr.euclesia.mcarchipelago.client.gui.ArchipelagoConnectingScreen;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Pre-flight Archipelago connection step. {@code Minecraft.doWorldLoad} is the single point every
 * world-entry path funnels through right before the integrated server starts — creating a world
 * ({@code createFreshLevel}), loading one ({@code openWorldDoLoad}) and recreating one all call it.
 * If the world is bound to an Archipelago slot and we are not already connected, we cancel the load,
 * run the connection on a {@link ArchipelagoConnectingScreen}, and only resume the load (→ server
 * start → spawn-area generation) once connected. A failed connection aborts the load entirely, so no
 * chunks generate and no mobs spawn without the slot data that gates them.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftWorldLoadMixin {

    // Set only across the single re-entrant resume call; Minecraft is a singleton, so this must not
    // stay true between separate world loads (it would skip the gate on the next one).
    @Unique
    private boolean archipelago_euclesia$resuming;

    @Inject(method = "doWorldLoad", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$connectFirst(LevelStorageSource.LevelStorageAccess storageAccess,
                                                   PackRepository packs, WorldStem stem,
                                                   Optional<GameRules> gameRules, boolean newWorld,
                                                   CallbackInfo ci) {
        if (archipelago_euclesia$resuming) {
            archipelago_euclesia$resuming = false;
            return; // resumed after a successful connect — let the real load run
        }

        Minecraft self = (Minecraft) (Object) this;

        // The headless entities dump spins up a throwaway, deliberately non-Archipelago world; it must
        // load without a slot, so exempt it before the credential gate below.
        if (HeadlessEntitiesDump.isDumpWorld(storageAccess.getLevelId())) {
            return;
        }

        // A brand-new world's connection file isn't on disk yet (it's written at server start), so
        // check the staged pending connection first, then fall back to the saved file.
        APWorldConnection connection = APWorldConnection.peekPending();
        if (connection == null) {
            connection = APWorldConnection.read(storageAccess.getLevelPath(LevelResource.ROOT));
        }
        if (connection == null || !connection.hasSlot()) {
            // Every world in this pack must be bound to an Archipelago slot. A world without one (a
            // legacy or externally-made save) cannot be joined: abort the load, release the world
            // resources and save lock, and send the player back to the world list where it is flagged.
            ci.cancel();
            stem.close();
            storageAccess.safeClose();
            APWorldConnection.takePending();
            SystemToast.addOrUpdate(self.getToastManager(), SystemToast.SystemToastId.WORLD_ACCESS_FAILURE,
                    Component.translatable("gui.aem.world.incompatible"),
                    Component.translatable("gui.aem.world.blocked"));
            self.setScreen(new SelectWorldScreen(new TitleScreen()));
            return;
        }
        if (AEM.ARCHIPELAGO.client().state().isConnected()) {
            return; // already connected (e.g. via the main-menu connect screen)
        }

        ci.cancel();
        APWorldConnection target = connection;
        self.setScreen(new ArchipelagoConnectingScreen(
                target,
                packs,
                () -> WorldLoadResume.schedule(() -> {
                    // Resume on END_CLIENT_TICK, outside the screen-tick bracket: doWorldLoad swaps
                    // Minecraft.screen and brackets the loading screen with Fabric's shared ticking-screen
                    // tracking, so resuming from within a screen tick corrupts it (NPE in afterScreenTick).
                    archipelago_euclesia$resuming = true;
                    self.doWorldLoad(storageAccess, packs, stem, gameRules, newWorld);
                }),
                () -> {
                    // Abort: release the world resources and save lock, drop any staged connection, go home.
                    stem.close();
                    storageAccess.safeClose();
                    APWorldConnection.takePending();
                    self.setScreen(new TitleScreen());
                }));
    }
}
