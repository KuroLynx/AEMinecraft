package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps an ominous bottle's Bad Omen from being spent on nothing. Two rules:
 *
 * <ul>
 *   <li>Near an unlocked village or Trial Chambers whose raid / ominous trial can't start yet (a raid
 *       needs every raid mob, a trial one of its spawner mobs), the drink is refused and the player
 *       is told which mobs are missing.
 *   <li>When the seed locks a village or the Trial Chambers, the bottle is drunk only inside one whose
 *       event can start. With neither locked it is drunk anywhere else, as in vanilla, so Bad Omen
 *       stays as easy to get as any other effect.
 * </ul>
 *
 * A locked structure counts as absent: {@code StructureManagerMixin} hides it from the "inside"
 * lookup, and the nearby scan skips it. {@code BadOmenMobEffectMixin} sends the same "missing" line
 * when a player walks in with Bad Omen. Logic asks for the same ({@code can_use_ominous_bottle}).
 */
public final class OminousBottleGate {
    /** What a Bad Omen can start, and where. */
    public enum Event { RAID, TRIAL }

    private static final Identifier TRIAL_CHAMBERS = Identifier.withDefaultNamespace("trial_chambers");

    /** How far around a structure's bounds, height included, a drink is refused when its event can't start. */
    private static final int NEARBY_BLOCKS = 32;

    /**
     * What the trial spawners can spawn ({@code data/minecraft/trial_spawner/*}, the same on 26.1.2,
     * 26.2 and 26.3). Hard-coded like the raid roster; mirrors {@code MOBS_TRIAL} in the apworld.
     */
    private static final List<String> TRIAL_MOB_IDS = List.of(
            "minecraft:bogged", "minecraft:breeze", "minecraft:cave_spider", "minecraft:husk",
            "minecraft:silverfish", "minecraft:skeleton", "minecraft:slime", "minecraft:spider",
            "minecraft:stray", "minecraft:zombie");

    private OminousBottleGate() {}

    public static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)
                    || !player.getItemInHand(hand).is(Items.OMINOUS_BOTTLE)) {
                return InteractionResult.PASS;
            }
            Component refusal = refusal(serverLevel, player.blockPosition());
            if (refusal == null) {
                return InteractionResult.PASS;
            }
            LockFeedback.notify(serverPlayer, refusal);
            return InteractionResult.FAIL;
        });
    }

    /** Why the bottle can't be drunk here, or {@code null} when it can. */
    private static Component refusal(ServerLevel level, BlockPos pos) {
        Set<Event> inside = inside(level, pos);
        if (inside.stream().anyMatch(event -> missing(event) == null)) {
            return null;
        }
        for (Event event : nearby(level, pos)) {
            Component missing = missing(event);
            if (missing != null) {
                return missing;
            }
        }
        return gated(level)
                ? Component.translatable("message.aem.ominous_bottle.where").withStyle(ChatFormatting.GOLD)
                : null;
    }

    /** "You're missing … to start a raid/trial" in red, or {@code null} when the event can start. */
    public static Component missing(Event event) {
        if (event == Event.RAID) {
            List<String> locked = MobSpawnLockService.lockedRaidMobs();
            return locked.isEmpty() ? null : Component.translatable("message.aem.ominous_bottle.missing_raid",
                    ComponentUtils.formatList(locked, OminousBottleGate::mobName)).withStyle(ChatFormatting.RED);
        }
        if (TRIAL_MOB_IDS.stream().anyMatch(id -> !MobSpawnLockService.isMobLocked(id))) {
            return null;
        }
        // Any one of them is enough, so they are listed as alternatives.
        return Component.translatable("message.aem.ominous_bottle.missing_trial",
                ComponentUtils.formatList(TRIAL_MOB_IDS, Component.literal(" or "), OminousBottleGate::mobName))
                .withStyle(ChatFormatting.RED);
    }

    /** The events whose (unlocked) structure has a piece at {@code pos}. */
    public static Set<Event> inside(ServerLevel level, BlockPos pos) {
        Set<Event> events = EnumSet.noneOf(Event.class);
        if (level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE).isValid()) {
            events.add(Event.RAID);
        }
        if (level.structureManager().getStructureWithPieceAt(pos, holder -> holder.is(TRIAL_CHAMBERS)).isValid()) {
            events.add(Event.TRIAL);
        }
        return events;
    }

    /**
     * The events whose unlocked structure's bounds, grown by {@link #NEARBY_BLOCKS} on every axis,
     * hold {@code pos}. The chunks around {@code pos} name the candidates (a chunk references every
     * structure whose bounds cross it); the height test is what keeps the surface above a buried
     * Trial Chambers out.
     */
    private static Set<Event> nearby(ServerLevel level, BlockPos pos) {
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Set<Event> events = EnumSet.noneOf(Event.class);
        for (int dx = -NEARBY_BLOCKS; dx <= NEARBY_BLOCKS; dx += 16) {
            for (int dz = -NEARBY_BLOCKS; dz <= NEARBY_BLOCKS; dz += 16) {
                var references = level.structureManager().getAllStructuresAt(pos.offset(dx, 0, dz));
                for (var reference : references.entrySet()) {
                    Structure structure = reference.getKey();
                    Holder<Structure> holder = registry.wrapAsHolder(structure);
                    Event event = holder.is(StructureTags.VILLAGE) ? Event.RAID
                            : holder.is(TRIAL_CHAMBERS) ? Event.TRIAL : null;
                    if (event == null || events.contains(event)
                            || StructureLockService.isStructureLocked(level.registryAccess(), structure)) {
                        continue;
                    }
                    for (long start : reference.getValue()) {
                        StructureStart found = level.getChunk(ChunkPos.getX(start), ChunkPos.getZ(start),
                                ChunkStatus.STRUCTURE_STARTS, true).getStartForStructure(structure);
                        if (found != null && found.isValid()
                                && found.getBoundingBox().inflatedBy(NEARBY_BLOCKS).isInside(pos)) {
                            events.add(event);
                            break;
                        }
                    }
                }
            }
        }
        return events;
    }

    /** Whether this seed locks any village or the Trial Chambers. */
    private static boolean gated(ServerLevel level) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return false;
        }
        var structures = AEM.ARCHIPELAGO.client().registries().apStructures();
        if (structures.isLockable(TRIAL_CHAMBERS.toString())) {
            return true;
        }
        return level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(StructureTags.VILLAGE)
                .map(villages -> villages.stream().anyMatch(holder -> holder.unwrapKey()
                        .map(key -> structures.isLockable(key.identifier().toString())).orElse(false)))
                .orElse(false);
    }

    private static Component mobName(String id) {
        return BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id)).getDescription();
    }
}
