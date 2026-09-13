package fr.euclesia.mcarchipelago.archipelago;

/**
 * Runtime "hide multiworld noise" chat filter on/off, mirroring {@link DeathLinkPreference}'s
 * shape but simpler: a plain boolean rather than a tri-state that falls back to slot data, since
 * there is no per-slot default for this — it is purely a display preference.
 *
 * <p>This is a run-wide setting (the server is many people sharing one Archipelago slot, same
 * reasoning DeathLink is an operator switch rather than a personal one), so a single static field
 * is correct. The authoritative copy lives wherever {@code ArchipelagoChatListener} actually
 * filters — the server's JVM — and is kept in sync elsewhere (screens, HUDs) via
 * {@code ChatFilterSyncPayload}; see {@code ChatFilterNet} and {@code APStateSyncClient}.
 */
public final class ChatFilterPreference {
    private static volatile boolean enabled;

    private ChatFilterPreference() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Puts back a value loaded from the world ({@code ChatFilterSetting}), with no side effects. */
    public static void restore(boolean stored) {
        enabled = stored;
    }
}
