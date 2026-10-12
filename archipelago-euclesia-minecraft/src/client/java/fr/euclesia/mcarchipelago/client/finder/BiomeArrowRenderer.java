package fr.euclesia.mcarchipelago.client.finder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderTrackerState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Biome Finder's guide: three flat arrows on the ground ahead of the player, laid along the way to
 * the tracked biome and pointing at it. They pulse back to front, so the row reads as "this way" even
 * at a glance.
 *
 * <p>Each arrow sits on the ground under its own spot — the first walkable surface within a few blocks
 * of the player's feet — so they follow slopes and steps instead of floating or sinking. Drawn as
 * translucent, unculled position-colour quads ({@code RenderTypes.debugQuads()}), relative to the
 * camera like everything the level renderer submits.
 */
public final class BiomeArrowRenderer {
    /** Distance (blocks) from the player to each arrow's centre. */
    private static final float[] DISTANCES = {2.5F, 4.5F, 6.5F};
    // Arrow shape, in blocks: total length, head length, head and shaft widths.
    private static final float LENGTH = 1.5F;
    private static final float HEAD = 0.7F;
    private static final float HEAD_WIDTH = 1.1F;
    private static final float SHAFT_WIDTH = 0.4F;
    /** Lifted off the surface so it never z-fights with the block top. */
    private static final float LIFT = 0.02F;
    /** How far above and below the player's feet to look for each arrow's ground. */
    private static final int SEARCH_UP = 2;
    private static final int SEARCH_DOWN = 5;
    /** Closer than this the arrows would sit on top of the target; arrival clears it soon after. */
    private static final double MIN_DISTANCE = 4.0;
    private static final int RGB = 0xFFFFFF;
    /** One back-to-front pulse, in milliseconds. */
    private static final long PULSE_MILLIS = 1200;

    private BiomeArrowRenderer() {}

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(BiomeArrowRenderer::submit);
    }

    private static void submit(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        BiomeFinderTrackerState.Target target = BiomeFinderTrackerState.get().target(player.getUUID());
        if (target == null || !target.dimension().equals(level.dimension())) {
            return;
        }

        Vec3 feet = player.getPosition(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        double dx = target.pos().getX() + 0.5 - feet.x;
        double dz = target.pos().getZ() + 0.5 - feet.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < MIN_DISTANCE) {
            return;
        }
        // Forward (towards the target) and right, on the ground plane.
        float fx = (float) (dx / distance);
        float fz = (float) (dz / distance);
        float rx = -fz;
        float rz = fx;

        Vec3 camera = context.levelState().cameraRenderState.pos;
        long now = System.currentTimeMillis();
        PoseStack poseStack = context.poseStack();
        for (int i = 0; i < DISTANCES.length; i++) {
            double cx = feet.x + fx * DISTANCES[i];
            double cz = feet.z + fz * DISTANCES[i];
            Double ground = groundY(level, cx, cz, (int) Math.floor(feet.y));
            if (ground == null) {
                continue; // over a drop or inside a wall: leave a gap rather than float
            }
            float ox = (float) (cx - camera.x);
            float oy = (float) (ground + LIFT - camera.y);
            float oz = (float) (cz - camera.z);
            int argb = alpha(now, i) << 24 | RGB;
            context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(),
                    (pose, consumer) -> arrow(pose, consumer, ox, oy, oz, fx, fz, rx, rz, argb));
        }
    }

    /**
     * The arrows light up in turn, nearest first, and fade back: a wave running towards the target.
     */
    private static int alpha(long now, int index) {
        float phase = ((now % PULSE_MILLIS) / (float) PULSE_MILLIS - index / (float) DISTANCES.length + 1.0F) % 1.0F;
        float glow = Math.max(0.0F, 1.0F - phase * 3.0F); // a short bright flash, then rest
        return (int) (90 + 120 * glow);
    }

    /**
     * Top of the first standable surface at (x, z) near the player's feet: scanning down from just above
     * them, a block with collision whose block above has none. Null when there is none in range — over a
     * drop, or inside a wall.
     */
    private static Double groundY(ClientLevel level, double x, double z, int feetY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        for (int y = feetY + SEARCH_UP; y >= feetY - SEARCH_DOWN; y--) {
            VoxelShape shape = level.getBlockState(pos.set(bx, y, bz)).getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            boolean openAbove = level.getBlockState(pos.set(bx, y + 1, bz)).getCollisionShape(level, pos).isEmpty();
            if (openAbove) {
                return y + shape.max(Direction.Axis.Y);
            }
        }
        return null;
    }

    /** One arrow: a head (triangle, as a quad with a doubled tip) and a shaft, centred on (ox, oy, oz). */
    private static void arrow(PoseStack.Pose pose, VertexConsumer consumer, float ox, float oy, float oz,
                              float fx, float fz, float rx, float rz, int argb) {
        float back = -LENGTH / 2.0F;
        float neck = LENGTH / 2.0F - HEAD;
        float tip = LENGTH / 2.0F;
        // Shaft.
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, back, -SHAFT_WIDTH / 2.0F, argb);
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, back, SHAFT_WIDTH / 2.0F, argb);
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, neck, SHAFT_WIDTH / 2.0F, argb);
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, neck, -SHAFT_WIDTH / 2.0F, argb);
        // Head.
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, neck, -HEAD_WIDTH / 2.0F, argb);
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, neck, HEAD_WIDTH / 2.0F, argb);
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, tip, 0.0F, argb);
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, tip, 0.0F, argb);
    }

    /** A vertex {@code forward} blocks ahead and {@code right} blocks to the right of the arrow's centre. */
    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, float ox, float oy, float oz,
                               float fx, float fz, float rx, float rz, float forward, float right, int argb) {
        consumer.addVertex(pose, ox + fx * forward + rx * right, oy, oz + fz * forward + rz * right).setColor(argb);
    }
}
