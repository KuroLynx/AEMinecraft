package fr.euclesia.mcarchipelago.server.gameplay;

import com.mojang.serialization.Codec;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;

import java.util.Set;

/**
 * Places a freshly joined player in the dimension chosen by the Archipelago {@code start_dimension}
 * slot option. Only {@code nether} requires action — {@code overworld} keeps the vanilla spawn.
 *
 * <p>The decision is recorded once per player in a persistent attachment so it never re-fires: a
 * Nether-start player who has already been relocated keeps their own respawn point across deaths and
 * server restarts instead of being yanked back to the spawn platform.
 *
 * <p>Slot data is only known once the Archipelago session is connected, so {@link #applyIfNeeded}
 * is a no-op until then. It is invoked both on player join (covers connect-before-join, e.g. the
 * main-menu connect flow) and on connect (covers connect-after-join via the {@code /archipelago
 * connect} command), whichever happens last.
 */
public final class StartDimensionService {

    /** Persistent per-player flag: has the start dimension already been resolved for this player? */
    public static final AttachmentType<Boolean> START_APPLIED =
            AttachmentRegistry.createPersistent(
                    Identifier.fromNamespaceAndPath(AEM.MOD_ID, "start_dimension_applied"),
                    Codec.BOOL);

    private static final String NETHER = "nether";
    /** Above the lava ocean (~y31) and below the bedrock roof (y128). */
    private static final int PLATFORM_Y = 64;

    private StartDimensionService() {}

    /** Forces attachment registration during mod init; safe to call repeatedly. */
    public static void bootstrap() {
        // Referencing START_APPLIED triggers class init, which registers the attachment.
    }

    /** Applies the start dimension to every online player (used on connect). */
    public static void applyToOnlinePlayers() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return;
        }
        server.execute(() -> server.getPlayerList().getPlayers().forEach(StartDimensionService::applyIfNeeded));
    }

    /**
     * Relocates the player if the slot data is known and the start dimension has not been resolved
     * for them yet. Must run on the server thread.
     */
    public static void applyIfNeeded(ServerPlayer player) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return; // Slot data not known yet; retried on connect.
        }
        if (Boolean.TRUE.equals(player.getAttached(START_APPLIED))) {
            return;
        }

        String start = AEM.ARCHIPELAGO.client().state().parsedSlotData().startDimension();
        // Mark resolved up front so the start dimension is only ever evaluated once per player.
        player.setAttached(START_APPLIED, Boolean.TRUE);

        if (!NETHER.equalsIgnoreCase(start)) {
            return; // Overworld start: vanilla spawn, nothing to do.
        }
        placeInNether(player);
    }

    private static void placeInNether(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }

        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether == null) {
            AEM.LOGGER.warn("Nether start requested but the Nether dimension is unavailable");
            return;
        }

        // Derive a Nether spawn from the overworld spawn using the vanilla 8:1 scale.
        BlockPos overworldSpawn = server.overworld().getRespawnData().pos();
        BlockPos platform = new BlockPos(overworldSpawn.getX() / 8, PLATFORM_Y, overworldSpawn.getZ() / 8);
        buildSafePlatform(nether, platform);

        double x = platform.getX() + 0.5;
        double y = platform.getY() + 1;
        double z = platform.getZ() + 0.5;
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        player.teleportTo(nether, x, y, z, Set.of(), yaw, pitch, true);

        // Make the platform the player's spawn so deaths return to the Nether, not the overworld.
        player.setRespawnPosition(
                new ServerPlayer.RespawnConfig(
                        LevelData.RespawnData.of(Level.NETHER, BlockPos.containing(x, y, z), yaw, pitch),
                        true),
                false);

        AEM.LOGGER.info("Placed {} on the Nether start platform at {}", player.getGameProfile().name(), platform);
    }

    /** Carves a 5x5 obsidian pad with a 3-tall air pocket above so the player lands safely. */
    private static void buildSafePlatform(ServerLevel level, BlockPos center) {
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(center.offset(dx, 0, dz), obsidian);
                for (int dy = 1; dy <= 3; dy++) {
                    level.setBlockAndUpdate(center.offset(dx, dy, dz), air);
                }
            }
        }
    }
}
