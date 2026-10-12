package fr.euclesia.mcarchipelago.client.finder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderTrackerState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * The Biome Finder's guide: three arrowheads floating ahead of the player at waist height, in a row
 * towards the tracked biome and pointing at it. They pulse nearest first, so the row reads as "this
 * way" even at a glance.
 *
 * <p>Each one is a flat, horizontal quad textured with {@link #TEXTURE}, which resource packs can
 * replace: the texture's top edge points at the target. Drawn translucent, unculled and full-bright
 * ({@code RenderTypes.entityTranslucent}), relative to the camera like everything the level renderer
 * submits.
 */
public final class BiomeArrowRenderer {
    /** {@code assets/aem/textures/misc/biome_finder_arrow.png}; its top edge is the arrow's point. */
    public static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(AEM.MOD_ID, "textures/misc/biome_finder_arrow.png");
    /** Distance (blocks) from the player to each arrowhead's centre. */
    private static final float[] DISTANCES = {2.2F, 3.6F, 5.0F};
    /** Each arrowhead is a SIZE×SIZE square of texture, in blocks. */
    private static final float SIZE = 1.4F;
    /** Height above the player's feet: about the waist, between feet and head. */
    private static final float HEIGHT = 0.9F;
    /** Closer than this the arrows would overshoot the target; arrival clears it soon after. */
    private static final double MIN_DISTANCE = 4.0;
    /** Packed block/sky light, both at 15: the arrows glow the same by day, night and underground. */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** One pulse along the row, in milliseconds. */
    private static final long PULSE_MILLIS = 1200;

    private BiomeArrowRenderer() {}

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(BiomeArrowRenderer::submit);
    }

    private static void submit(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        BiomeFinderTrackerState.Target target = BiomeFinderTrackerState.get().target(player.getUUID());
        if (target == null || !target.dimension().equals(minecraft.level.dimension())) {
            return;
        }

        Vec3 feet = player.getPosition(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        double dx = target.pos().getX() + 0.5 - feet.x;
        double dz = target.pos().getZ() + 0.5 - feet.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < MIN_DISTANCE) {
            return;
        }
        // Forward (towards the target) and right, on the horizontal plane.
        float fx = (float) (dx / distance);
        float fz = (float) (dz / distance);
        float rx = -fz;
        float rz = fx;

        Vec3 camera = context.levelState().cameraRenderState.pos;
        long now = System.currentTimeMillis();
        PoseStack poseStack = context.poseStack();
        for (int i = 0; i < DISTANCES.length; i++) {
            float ox = (float) (feet.x + fx * DISTANCES[i] - camera.x);
            float oy = (float) (feet.y + HEIGHT - camera.y);
            float oz = (float) (feet.z + fz * DISTANCES[i] - camera.z);
            int argb = alpha(now, i) << 24 | 0xFFFFFF;
            context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURE),
                    (pose, consumer) -> arrowhead(pose, consumer, ox, oy, oz, fx, fz, rx, rz, argb));
        }
    }

    /** The arrowheads light up in turn, nearest first, and fade back: a wave running towards the target. */
    private static int alpha(long now, int index) {
        float phase = ((now % PULSE_MILLIS) / (float) PULSE_MILLIS - index / (float) DISTANCES.length + 1.0F) % 1.0F;
        float glow = Math.max(0.0F, 1.0F - phase * 3.0F); // a short bright flash, then rest
        return (int) (110 + 145 * glow);
    }

    /** One textured square centred on (ox, oy, oz): the texture's top edge (v = 0) faces forward. */
    private static void arrowhead(PoseStack.Pose pose, VertexConsumer consumer, float ox, float oy, float oz,
                                  float fx, float fz, float rx, float rz, int argb) {
        float half = SIZE / 2.0F;
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, -half, -half, 0.0F, 1.0F, argb);
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, -half, half, 1.0F, 1.0F, argb);
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, half, half, 1.0F, 0.0F, argb);
        vertex(pose, consumer, ox, oy, oz, fx, fz, rx, rz, half, -half, 0.0F, 0.0F, argb);
    }

    /** A vertex {@code forward} blocks ahead and {@code right} blocks to the right of the centre. */
    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, float ox, float oy, float oz,
                               float fx, float fz, float rx, float rz, float forward, float right,
                               float u, float v, int argb) {
        consumer.addVertex(pose, ox + fx * forward + rx * right, oy, oz + fz * forward + rz * right)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
