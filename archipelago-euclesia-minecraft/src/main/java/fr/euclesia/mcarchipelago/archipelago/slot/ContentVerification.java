package fr.euclesia.mcarchipelago.archipelago.slot;

import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData.ContentRequirement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that the datapacks/mods a seed depends on (see {@code required_content} in slot data) are
 * actually installed at the version this apworld was generated against. A seed generated with, say,
 * BACAP enabled logically requires that datapack; loading the world without it — or with a different
 * BACAP version — would desync locations/advancements, so the mod refuses to enter such a world (see
 * {@code ArchipelagoConnectingScreen}), the same way a slot-data schema mismatch is refused.
 *
 * <p>Datapacks are matched against the world's <em>selected</em> packs by a substring of their id/title
 * (BACAP ships its version only in its datapack filename, so we read the version from there); mods are
 * matched by Fabric mod id and their reported version.
 */
public final class ContentVerification {
    /** Pulls a dotted version (e.g. {@code 1.20.3}) out of a pack id/title for the mismatch message. */
    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    private ContentVerification() {}

    public enum Status { OK, MISSING, VERSION_MISMATCH }

    /** The outcome of checking one requirement (or an OK aggregate when nothing failed). */
    public record Result(Status status, ContentRequirement requirement, String installedVersion) {
        public boolean ok() {
            return status == Status.OK;
        }

        /** A red, player-facing explanation for a failing result; empty when OK. */
        public Component message() {
            if (ok()) {
                return Component.empty();
            }
            Component kind = Component.translatable(
                    "message.aem.content.kind." + (requirement.isMod() ? "mod" : "datapack"));
            return switch (status) {
                case MISSING -> Component.translatable("message.aem.content.missing", requirement.name(), kind);
                case VERSION_MISMATCH -> Component.translatable("message.aem.content.version",
                        requirement.name(), requirement.version(),
                        installedVersion == null || installedVersion.isEmpty() ? "?" : installedVersion);
                default -> Component.empty();
            };
        }
    }

    private static final Result OK = new Result(Status.OK, null, null);

    /** Verifies every requirement; returns the first failure, or an OK result when all pass. */
    public static Result verify(List<ContentRequirement> requirements, PackRepository packs) {
        for (ContentRequirement requirement : requirements) {
            Result result = requirement.isMod() ? verifyMod(requirement) : verifyDatapack(requirement, packs);
            if (!result.ok()) {
                return result;
            }
        }
        return OK;
    }

    private static Result verifyDatapack(ContentRequirement requirement, PackRepository packs) {
        String wantedVersion = requirement.version().toLowerCase(Locale.ROOT);
        boolean present = false;
        String installedVersion = null;
        for (Pack pack : packs.getSelectedPacks()) {
            String haystack = (pack.getId() + " " + pack.getTitle().getString()).toLowerCase(Locale.ROOT);
            if (!haystack.contains(requirement.match())) {
                continue;
            }
            present = true;
            if (wantedVersion.isEmpty() || haystack.contains(wantedVersion)) {
                return OK;
            }
            installedVersion = extractVersion(haystack);
        }
        return new Result(present ? Status.VERSION_MISMATCH : Status.MISSING, requirement, installedVersion);
    }

    private static Result verifyMod(ContentRequirement requirement) {
        Optional<net.fabricmc.loader.api.ModContainer> container =
                FabricLoader.getInstance().getModContainer(requirement.id());
        if (container.isEmpty()) {
            return new Result(Status.MISSING, requirement, null);
        }
        String installed = container.get().getMetadata().getVersion().getFriendlyString();
        if (requirement.version().isEmpty() || installed.equals(requirement.version())) {
            return OK;
        }
        return new Result(Status.VERSION_MISMATCH, requirement, installed);
    }

    private static String extractVersion(String haystack) {
        Matcher matcher = VERSION.matcher(haystack);
        return matcher.find() ? matcher.group(1) : null;
    }
}
