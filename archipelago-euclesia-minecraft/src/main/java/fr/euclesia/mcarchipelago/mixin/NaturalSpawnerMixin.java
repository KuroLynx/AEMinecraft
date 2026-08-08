package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MobSpawnLockService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes locked mobs out of the natural spawner's candidate list, so locking one slows nothing down.
 *
 * <p>{@code ServerLevelMixin} refuses locked mobs at {@code addEntity}, which is the right place for a
 * lock that must hold against every spawn path — but it is far too late for the natural spawner, which
 * by then has already committed the attempt. Read the spawn loop in order:
 *
 * <pre>
 *   iinc  (attempt counter)          &lt;- spent
 *   iinc  (group counter)            &lt;- spent
 *   addFreshEntityWithPassengers()   &lt;- returns void; our refusal is invisible
 *   AfterSpawnCallback.run()         &lt;- SpawnState.afterSpawn: counts it against the MOB CAP
 * </pre>
 *
 * <p>So a blocked mob was charged to the cap exactly as if it had spawned, and both the per-category
 * global cap and the per-player local one filled with mobs that do not exist. Lock a third of the
 * hostile list and roughly a third of every spawn cycle went to phantoms; the cap hit its ceiling and
 * the spawner stopped trying. That is the "mobs spawn slowly when some are locked" everyone notices.
 *
 * <p>Filtering the weighted list before the pick fixes it at the source: a locked type is simply not a
 * candidate, its weight goes to the mobs that CAN spawn, and no attempt, group slot or cap point is
 * spent on it. Locking zombies now means more skeletons, not fewer mobs.
 *
 * <p>The {@code addEntity} block stays as the real lock — this is only about not wasting the
 * spawner's budget. Every other path (spawners, breeding, {@code /summon}, conversions) still goes
 * through it.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Inject(method = "mobsAt", at = @At("RETURN"), cancellable = true)
    private static void archipelago_euclesia$dropLockedCandidates(
            ServerLevel level, StructureManager structures, ChunkGenerator generator, MobCategory category,
            BlockPos pos, Holder<Biome> biome,
            CallbackInfoReturnable<WeightedList<MobSpawnSettings.SpawnerData>> cir) {
        WeightedList<MobSpawnSettings.SpawnerData> candidates = cir.getReturnValue();
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        List<Weighted<MobSpawnSettings.SpawnerData>> entries = candidates.unwrap();
        // Hot path — one spawn attempt per chunk per tick — so don't allocate unless something is
        // actually locked, which for most of a run is nothing.
        boolean anyLocked = false;
        for (Weighted<MobSpawnSettings.SpawnerData> entry : entries) {
            if (isLocked(entry)) {
                anyLocked = true;
                break;
            }
        }
        if (!anyLocked) {
            return;
        }
        List<Weighted<MobSpawnSettings.SpawnerData>> allowed = new ArrayList<>(entries.size());
        for (Weighted<MobSpawnSettings.SpawnerData> entry : entries) {
            if (!isLocked(entry)) {
                allowed.add(entry);
            }
        }
        // All locked: an empty list makes getRandomSpawnMobAt come back empty, and the attempt ends
        // without costing the cap anything.
        cir.setReturnValue(WeightedList.of(allowed));
    }

    /**
     * Registry ids of the spawnable types, so the check below doesn't rebuild {@code "minecraft:zombie"}
     * for every candidate of every spawn attempt. The set of entity types is fixed for the run.
     */
    @Unique
    private static final Map<EntityType<?>, String> archipelago_euclesia$ids = new ConcurrentHashMap<>();

    @Unique
    private static boolean isLocked(Weighted<MobSpawnSettings.SpawnerData> entry) {
        EntityType<?> type = entry.value().type();
        String id = archipelago_euclesia$ids.computeIfAbsent(
                type, key -> BuiltInRegistries.ENTITY_TYPE.getKey(key).toString());
        return MobSpawnLockService.isMobLocked(id);
    }
}
