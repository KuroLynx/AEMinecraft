package fr.euclesia.mcarchipelago;

/**
 * Verbose, greppable debug logging for the Archipelago integration. Every line is prefixed with
 * {@code [AEM-DBG]} and emitted at INFO so it shows in the normal Minecraft server log during
 * development (server-side only — never shown to players). The whole channel can be silenced
 * without touching call sites via {@code -Daem.debug=false} (it defaults on).
 *
 * <p>For messages whose arguments are expensive to build (joining collections, formatting), guard
 * the call site with {@link #enabled()} so nothing is computed when debugging is off.</p>
 */
public final class AEMDebug {
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("aem.debug", "true"));

    /** Wire-dump truncation: long packets are clipped so a single message can't flood the log. */
    private static final int MAX_WIRE_CHARS = 400;

    private AEMDebug() {}

    /** Whether verbose debug logging is on. Guard expensive arg construction with this. */
    public static boolean enabled() {
        return ENABLED;
    }

    /** Logs an {@code [AEM-DBG]} line (slf4j {@code {}} placeholders) when debugging is enabled. */
    public static void log(String format, Object... args) {
        if (ENABLED) {
            AEM.LOGGER.info("[AEM-DBG] " + format, args);
        }
    }

    /** Clips a wire payload to {@link #MAX_WIRE_CHARS} for readable, bounded protocol dumps. */
    public static String truncate(String value) {
        if (value == null || value.length() <= MAX_WIRE_CHARS) {
            return value;
        }
        return value.substring(0, MAX_WIRE_CHARS) + "…(" + value.length() + " chars)";
    }
}
