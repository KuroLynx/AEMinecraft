package fr.euclesia.mcarchipelago.server.gameplay;

import net.minecraft.core.BlockPos;

/**
 * A located structure for the Progressive Structure Finder: its game id
 * (e.g. {@code minecraft:village_plains}), world position, and squared distance from the player at
 * search time (used to sort and to cap the locator bar at the nearest N types).
 */
public record FinderTarget(String structureId, BlockPos pos, double distanceSq) {}
