package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.server.connect.APWorldConnection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Builds the secondary line shown under a world's name in the world-selection list. In place of
 * vanilla's "{@code <folder> (<last played>)}", a world bound to an Archipelago slot shows
 * "{@code <slot> - <address>:<port> (<last played>)}" so worlds can be told apart by their slot and
 * server; any other world shows "Not compatible with Archipelago".
 *
 * @see fr.euclesia.mcarchipelago.client.mixin.WorldListEntryMixin
 */
public final class WorldListDescription {
    /** The gray vanilla applies to the world's id-and-last-played line ({@code withColor(-8355712)}). */
    private static final int VANILLA_INFO_GRAY = 0x808080;

    private WorldListDescription() {}

    public static Component of(LevelSummary summary) {
        Path worldDir = Minecraft.getInstance().getLevelSource().getLevelPath(summary.getLevelId());
        APWorldConnection connection = APWorldConnection.read(worldDir);
        if (connection == null || !connection.hasSlot()) {
            return Component.translatable("gui.aem.world.incompatible").withStyle(ChatFormatting.RED);
        }

        // Match vanilla's date rendering so the timestamp reads identically to other worlds.
        String lastPlayed = WorldSelectionList.DATE_FORMAT.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(summary.getLastPlayed()), ZoneId.systemDefault()));
        String server = connection.address + ":" + connection.port;
        // Empty root so each child keeps its own colour: the slot/server is green, while the trailing
        // timestamp uses the same gray (0x808080) vanilla gives this line.
        return Component.empty()
                .append(Component.literal(connection.slot + " - " + server).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (" + lastPlayed + ")").withColor(VANILLA_INFO_GRAY));
    }
}
