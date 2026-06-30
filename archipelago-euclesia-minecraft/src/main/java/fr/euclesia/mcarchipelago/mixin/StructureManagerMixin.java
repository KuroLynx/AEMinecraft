package fr.euclesia.mcarchipelago.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fr.euclesia.mcarchipelago.server.gameplay.StructureLockService;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Predicate;

/**
 * Hides a locked Archipelago structure from "is a position inside structure X" queries on the LIVE
 * world. A locked structure still has its {@code StructureStart} registered in the chunk (only its
 * blocks are diverted into a capture, see {@code StructureStartMixin}/{@link StructureLockService}), so
 * {@link StructureManager#getStructureWithPieceAt} would otherwise report the player as standing inside
 * a structure that hasn't physically generated yet — wrongly firing "inside structure" advancements
 * (vanilla Trial Chambers, BACAP structure goals).
 *
 * <p>Both the {@code Predicate} overload (which the {@code HolderSet}/{@code TagKey} overloads — and
 * therefore {@code LocationPredicate.structures} — delegate to) and the per-{@code Structure} overload
 * are wrapped. Suppression is scoped to a live {@link ServerLevel}: a worldgen {@code StructureManager}
 * (from {@code forWorldGenRegion}) wraps a {@code WorldGenRegion}, so generation-time adjacency checks
 * are left untouched. {@link StructureLockService#isStructureLocked} reads the live unlock state, so the
 * structure reappears in these queries the moment its unlock item arrives.
 */
@Mixin(StructureManager.class)
public abstract class StructureManagerMixin {
    @Shadow @Final private LevelAccessor level;

    @Shadow public abstract RegistryAccess registryAccess();

    @WrapMethod(method = "getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;")
    private StructureStart archipelago_euclesia$hideLockedByPredicate(BlockPos pos, Predicate<Holder<Structure>> predicate,
                                                                      Operation<StructureStart> original) {
        return archipelago_euclesia$suppressIfLocked(original.call(pos, predicate));
    }

    @WrapMethod(method = "getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;")
    private StructureStart archipelago_euclesia$hideLockedByStructure(BlockPos pos, Structure structure,
                                                                      Operation<StructureStart> original) {
        return archipelago_euclesia$suppressIfLocked(original.call(pos, structure));
    }

    @Unique
    private StructureStart archipelago_euclesia$suppressIfLocked(StructureStart start) {
        if (start != null && start != StructureStart.INVALID_START
                && this.level instanceof ServerLevel
                && StructureLockService.isStructureLocked(registryAccess(), start.getStructure())) {
            return StructureStart.INVALID_START;
        }
        return start;
    }
}
