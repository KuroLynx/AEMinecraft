package fr.euclesia.mcarchipelago.archipelago.slot;

import net.minecraft.network.chat.Component;

/**
 * Checks that a connected slot's apworld and this mod speak the same slot-data schema. The apworld
 * advertises {@code slot_data_version} (see {@code SLOT_DATA_VERSION} on the Python side); this mod
 * declares the range of schema versions it understands. A world outside that range is refused before
 * it loads (see {@code ArchipelagoConnectingScreen}), so a mismatched mod/apworld pair can't be played
 * in a broken state.
 *
 * <p>Bump {@link #MAX_SUPPORTED} when the mod is updated to read a newer schema; raise
 * {@link #MIN_SUPPORTED} only when dropping support for an old one. See {@code docs/versioning.md}.
 */
public final class CompatibilityService {
    /** Oldest slot-data schema this mod still reads. */
    public static final int MIN_SUPPORTED = 1;
    /** Newest slot-data schema this mod knows about. */
    public static final int MAX_SUPPORTED = 1;

    private CompatibilityService() {}

    public enum Kind {
        /** Version is within the supported range. */
        COMPATIBLE,
        /** Pre-versioning slot data (no {@code slot_data_version}); allowed with a warning. */
        LEGACY_UNVERSIONED,
        /** World uses a newer schema than this mod understands — update the mod. */
        MOD_TOO_OLD,
        /** World uses an older schema than this mod supports — regenerate with a newer apworld. */
        APWORLD_TOO_OLD
    }

    /** The outcome of a compatibility check, with a player-facing {@link #message()} when blocking. */
    public record Result(Kind kind, int slotDataVersion) {
        /** Whether the world may be entered. */
        public boolean compatible() {
            return kind == Kind.COMPATIBLE || kind == Kind.LEGACY_UNVERSIONED;
        }

        /** Whether entry must be refused (mismatched mod/apworld). */
        public boolean blocking() {
            return !compatible();
        }

        /** A red, player-facing explanation for a blocking result; empty otherwise. */
        public Component message() {
            return switch (kind) {
                case MOD_TOO_OLD -> Component.translatable(
                        "message.aem.compat.mod_too_old", slotDataVersion, MAX_SUPPORTED);
                case APWORLD_TOO_OLD -> Component.translatable(
                        "message.aem.compat.apworld_too_old", slotDataVersion, MIN_SUPPORTED);
                default -> Component.empty();
            };
        }
    }

    /** Classifies a slot's advertised {@code slot_data_version} against this mod's supported range. */
    public static Result check(int slotDataVersion) {
        if (slotDataVersion <= 0) {
            return new Result(Kind.LEGACY_UNVERSIONED, slotDataVersion);
        }
        if (slotDataVersion > MAX_SUPPORTED) {
            return new Result(Kind.MOD_TOO_OLD, slotDataVersion);
        }
        if (slotDataVersion < MIN_SUPPORTED) {
            return new Result(Kind.APWORLD_TOO_OLD, slotDataVersion);
        }
        return new Result(Kind.COMPATIBLE, slotDataVersion);
    }
}
