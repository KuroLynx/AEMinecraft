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
 * The Biome Finder's guide: one arrowhead floating half a block ahead of where the player looks, at waist
 * height, pointing at the tracked biome — straight ahead when you face it, back at you when it is
 * behind — with a slow pulse so it catches the eye.
 *
 * <p>Like the vanilla compass, the arrow is never rotated at runtime: the quad always lies square to the
 * player's view, and one of {@link #FRAMES} pre-drawn textures is picked for the target's angle
 * ({@code assets/aem/textures/misc/biome_finder_arrow/arrow_NN.png}, frame k pointing k·360/FRAMES°
 * clockwise from straight ahead; resource packs can replace any of them, and
 * {@code tools/gen_biome_arrow_textures.py} regenerates the set). Drawn translucent, unculled and
 * full-bright ({@code RenderTypes.entityTranslucent}), relative to the camera like everything the
 * level renderer submits.
 */
public final class BiomeArrowRenderer {
    /** Direction frames, as many as the vanilla compass has. */
    private static final int FRAMES = 32;
    private static final Identifier[] TEXTURES = new Identifier[FRAMES];
    static {
        for (int i = 0; i < FRAMES; i++) {
            TEXTURES[i] = Identifier.fromNamespaceAndPath(AEM.MOD_ID,
                    String.format("textures/misc/biome_finder_arrow/arrow_%02d.png", i));
        }
    }
    /** Distance (blocks) ahead of the player, along where they look. */
    private static final float DISTANCE = 0.5F;
    /** The arrowhead is a SIZE×SIZE square of texture, in blocks. */
    private static final float SIZE = 1.0F;
    /** Height above the player's feet: about the waist, between feet and head. */
    private static final float HEIGHT = 0.9F;
    /** Closer than this the direction is meaningless; arrival clears the target soon after. */
    private static final double MIN_DISTANCE = 4.0;
    /** Packed block/sky light, both at 15: the arrows glow the same by day, night and underground. */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** One pulse, in milliseconds. */
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

        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 feet = player.getPosition(partialTick);
        double dx = target.pos().getX() + 0.5 - feet.x;
        double dz = target.pos().getZ() + 0.5 - feet.z;
        if (dx * dx + dz * dz < MIN_DISTANCE * MIN_DISTANCE) {
            return;
        }
        // Forward (where the player looks, flattened) and right, on the horizontal plane.
        double yaw = Math.toRadians(player.getViewYRot(partialTick));
        float fx = (float) -Math.sin(yaw);
        float fz = (float) Math.cos(yaw);
        float rx = -fz;
        float rz = fx;
        // The target's angle from straight ahead, clockwise seen from above, picks the frame.
        double angle = Math.atan2(dx * rx + dz * rz, dx * fx + dz * fz);
        int frame = Math.floorMod((int) Math.round(angle / (2.0 * Math.PI) * FRAMES), FRAMES);

        Vec3 camera = context.levelState().cameraRenderState.pos;
        long now = System.currentTimeMillis();
        PoseStack poseStack = context.poseStack();
        float ox = (float) (feet.x + fx * DISTANCE - camera.x);
        float oy = (float) (feet.y + HEIGHT - camera.y);
        float oz = (float) (feet.z + fz * DISTANCE - camera.z);
        int argb = alpha(now) << 24 | 0xFFFFFF;
        context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURES[frame]),
                (pose, consumer) -> arrowhead(pose, consumer, ox, oy, oz, fx, fz, rx, rz, argb));
    }

    /** A slow breathe between dim and bright. */
    private static int alpha(long now) {
        float wave = 0.5F + 0.5F * (float) Math.sin(2.0 * Math.PI * (now % PULSE_MILLIS) / PULSE_MILLIS);
        return (int) (150 + 105 * wave);
    }

    /** One textured square centred on (ox, oy, oz), square to the view: the texture's top edge faces forward. */
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
