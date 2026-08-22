package fr.euclesia.mcarchipelago.server.gameplay;

import com.mojang.serialization.Codec;
import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-dimension persistent record of structure mobs that were ready to spawn (their structure
 * unlocked) but whose own mob type is still spawn-locked. Keyed by mob game id with the full entity
 * NBT, so {@link StructureCaptureService} can spawn them into the already-placed structure once the
 * mob's unlock item arrives. See [[mob-spawn-lock-feature]].
 */
public final class PendingMobData extends SavedData {

    private static final Codec<Map<String, List<CompoundTag>>> MAP_CODEC =
            Codec.unboundedMap(Codec.STRING, CompoundTag.CODEC.listOf());

    public static final Codec<PendingMobData> CODEC =
            MAP_CODEC.xmap(PendingMobData::fromMap, PendingMobData::toMap);

    public static final SavedDataType<PendingMobData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(AEM.MOD_ID, "pending_structure_mobs"),
            PendingMobData::new,
            CODEC,
            DataFixTypes.LEVEL
    );

    // mobGameId -> entity NBT (includes id + Pos) awaiting the mob's unlock item.
    private final Map<String, List<CompoundTag>> pending = new HashMap<>();

    public PendingMobData() {}

    private static PendingMobData fromMap(Map<String, List<CompoundTag>> map) {
        PendingMobData data = new PendingMobData();
        map.forEach((mobId, mobs) -> data.pending.put(mobId, new ArrayList<>(mobs)));
        return data;
    }

    private Map<String, List<CompoundTag>> toMap() {
        Map<String, List<CompoundTag>> map = new HashMap<>();
        pending.forEach((mobId, mobs) -> {
            if (!mobs.isEmpty()) {
                map.put(mobId, new ArrayList<>(mobs));
            }
        });
        return map;
    }

    public synchronized void add(String mobId, CompoundTag entityNbt) {
        pending.computeIfAbsent(mobId, key -> new ArrayList<>()).add(entityNbt);
        setDirty();
    }

    /** Every mob id currently holding deferred spawns. */
    public synchronized Set<String> pendingIds() {
        return Set.copyOf(pending.keySet());
    }

    public synchronized List<CompoundTag> drain(String mobId) {
        List<CompoundTag> removed = pending.remove(mobId);
        if (removed != null && !removed.isEmpty()) {
            setDirty();
            return removed;
        }
        return List.of();
    }
}
