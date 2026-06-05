package fr.euclesia.mcarchipelago.client.connect;

/**
 * One-shot deferral for resuming a world load after the Archipelago pre-flight connect succeeds (see
 * {@code MinecraftWorldLoadMixin}). The resume calls {@code Minecraft.doWorldLoad}, which swaps the
 * active screen and brackets the loading screen with Fabric's shared ticking-screen tracking. Running
 * it from inside a screen's {@code tick()} (e.g. via {@code Minecraft.execute}, which runs inline on
 * the render thread) corrupts that tracking and NPEs. Running it on {@code END_CLIENT_TICK} — after
 * the screen-tick bracket has completed — keeps the tracking balanced.
 */
public final class WorldLoadResume {

    private static volatile Runnable pending;

    private WorldLoadResume() {}

    public static void schedule(Runnable resume) {
        pending = resume;
    }

    /** Runs and clears any scheduled resume; call once per client tick from END_CLIENT_TICK. */
    public static void runPending() {
        Runnable resume = pending;
        pending = null;
        if (resume != null) {
            resume.run();
        }
    }
}
