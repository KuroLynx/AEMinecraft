package fr.euclesia.mcarchipelago.server.gameplay;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The "Dé à Coudre" trap: a no-bucket MLG. It builds a small bedrock-framed water platform OFF TO THE
 * SIDE of the victim, clears a corridor straight up (cave-safe), and flings the player high above their
 * original spot. Because the water is offset, dropping straight down lands on bedrock/ground — the
 * player has to actively steer toward the lone water source to survive.
 *
 * <p>Every block the trap changes is snapshotted and restored when the platform expires (after
 * {@link #LIFETIME_MS}) or when the victim dies, so the world returns to exactly how it was.
 */
public final class TrapPlatformService {
    private static final long LIFETIME_MS = 15_000L;
    /** How far above the original spot the player is flung. */
    private static final int DROP_HEIGHT = 70;
    /** How far to the side the water lands — far enough that you must move to reach it. */
    private static final int HORIZONTAL_OFFSET = 5;
    /** Perpendicular half-width of the cleared fall corridor (1 => 3 wide). */
    private static final int CORRIDOR_HALF_WIDTH = 1;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    /** Cheap block-set flags for the corridor: notify clients, skip neighbour/physics cascades. */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final List<Platform> PLATFORMS = new ArrayList<>();

    private record Platform(ServerLevel level, Map<BlockPos, BlockState> original, long expiry, UUID victim) {}

    private TrapPlatformService() {}

    /** Registers the expiry tick. Call once during server-bridge setup. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void tick() {
        if (PLATFORMS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Platform> it = PLATFORMS.iterator();
        while (it.hasNext()) {
            Platform platform = it.next();
            if (now >= platform.expiry()) {
                restore(platform);
                it.remove();
            }
        }
    }

    /** Restores (and forgets) any platform belonging to a player who just died. */
    public static void onPlayerDeath(ServerPlayer player) {
        UUID id = player.getUUID();
        Iterator<Platform> it = PLATFORMS.iterator();
        while (it.hasNext()) {
            Platform platform = it.next();
            if (id.equals(platform.victim())) {
                restore(platform);
                it.remove();
            }
        }
    }

    /** Builds the offset platform + corridor and flings {@code player} skyward over their original spot. */
    public static void mlg(ServerPlayer player, ServerLevel level) {
        BlockPos origin = player.blockPosition();
        BlockPos water = platformSpot(level, origin);
        Map<BlockPos, BlockState> snapshot = new HashMap<>();

        // Offset water platform: a bedrock plus-frame holding a single central water source.
        //   X bedrock X
        //   bedrock water bedrock
        //   X bedrock X
        set(level, snapshot, water, Blocks.WATER.defaultBlockState());
        set(level, snapshot, water.north(), Blocks.BEDROCK.defaultBlockState());
        set(level, snapshot, water.south(), Blocks.BEDROCK.defaultBlockState());
        set(level, snapshot, water.east(), Blocks.BEDROCK.defaultBlockState());
        set(level, snapshot, water.west(), Blocks.BEDROCK.defaultBlockState());

        // Clear a corridor spanning both columns so the fall and sideways drift are unobstructed.
        int teleportY = Math.min(origin.getY() + DROP_HEIGHT, level.getMaxY() - 2);
        int topY = Math.min(teleportY + 1, level.getMaxY() - 1);
        int minX = Math.min(origin.getX(), water.getX()) - CORRIDOR_HALF_WIDTH;
        int maxX = Math.max(origin.getX(), water.getX()) + CORRIDOR_HALF_WIDTH;
        int minZ = Math.min(origin.getZ(), water.getZ()) - CORRIDOR_HALF_WIDTH;
        int maxZ = Math.max(origin.getZ(), water.getZ()) + CORRIDOR_HALF_WIDTH;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = origin.getY() + 1; y <= topY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (!level.getBlockState(cursor).isAir() && !holdsContents(level, cursor)) {
                        clear(level, snapshot, cursor);
                    }
                }
            }
        }

        player.connection.teleport(origin.getX() + 0.5, teleportY, origin.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        PLATFORMS.add(new Platform(level, snapshot,
                System.currentTimeMillis() + LIFETIME_MS, player.getUUID()));
    }

    /**
     * Where to put the water platform: a random side, preferring one whose footprint would not pave
     * over a chest or a furnace (see {@link #holdsContents}). Falls back to the random pick when the
     * victim has managed to surround themselves with containers.
     */
    private static BlockPos platformSpot(ServerLevel level, BlockPos origin) {
        int first = level.getRandom().nextInt(HORIZONTAL.length);
        BlockPos fallback = origin.relative(HORIZONTAL[first], HORIZONTAL_OFFSET);
        for (int i = 0; i < HORIZONTAL.length; i++) {
            BlockPos spot = origin.relative(HORIZONTAL[(first + i) % HORIZONTAL.length], HORIZONTAL_OFFSET);
            if (!holdsContents(level, spot) && !holdsContents(level, spot.north()) && !holdsContents(level, spot.south())
                    && !holdsContents(level, spot.east()) && !holdsContents(level, spot.west())) {
                return spot;
            }
        }
        return fallback;
    }

    /**
     * Whether {@code pos} holds something the snapshot could not give back.
     *
     * <p>The snapshot is a {@link BlockState}, and a state is not a chest's contents: clearing a
     * furnace out of the corridor and putting the same furnace back thirty seconds later would return
     * it empty, and the trap would have eaten the player's things. Anything with a block entity is
     * therefore left standing — the corridor detours around it. Falling into it is survivable; losing
     * a shulker box is not.
     */
    private static boolean holdsContents(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) != null;
    }

    /** Snapshots {@code pos} once and sets it with a full update (the water/bedrock platform). */
    private static void set(ServerLevel level, Map<BlockPos, BlockState> snapshot, BlockPos pos, BlockState state) {
        BlockPos key = pos.immutable();
        snapshot.putIfAbsent(key, level.getBlockState(key));
        level.setBlockAndUpdate(key, state);
    }

    /** Snapshots {@code pos} once and clears it to air quietly (no neighbour cascade) — the corridor. */
    private static void clear(ServerLevel level, Map<BlockPos, BlockState> snapshot, BlockPos pos) {
        BlockPos key = pos.immutable();
        snapshot.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, Blocks.AIR.defaultBlockState(), QUIET);
    }

    private static void restore(Platform platform) {
        platform.original().forEach((pos, state) -> platform.level().setBlock(pos, state, QUIET));
    }
}
