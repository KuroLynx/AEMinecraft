package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.gameplay.FinderTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's Structure Finder bar, pushed from the server.
 *
 * <p>The result used to be published into a server-side holder that the HUD read directly. That works
 * only while client and server share a process; on a dedicated server the client's copy of that
 * holder is empty, so the bar simply never appeared.
 *
 * <p>Sent per player rather than broadcast, but NOT because the tier differs — a server is many
 * people playing one slot, so the Finder belongs to the run and everybody has the same tier. It is
 * per player because the targets are: someone in the Nether must be sent the Nether's structures, and
 * the set is trimmed to the ones nearest that player, which is a different set for each of them.
 *
 * <p>Re-sent whenever that set changes — the server re-picks it against the live player position a
 * couple of times a second — so the bar keeps up with a player who walks away from what it was
 * showing.
 *
 * <p>Only the located structures travel. Bearing, distance and icon size stay client-side, derived
 * from the live player position every frame — otherwise the bar would lag a walking player by
 * however often the server sent an update.
 */
public record FinderSyncPayload(int tier, List<FinderTarget> targets) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "finder_state");
    public static final CustomPacketPayload.Type<FinderSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /** A finder bar holds a handful of structure types; this is a sanity bound, not a design limit. */
    private static final int MAX_TARGETS = 512;

    public static final StreamCodec<RegistryFriendlyByteBuf, FinderSyncPayload> CODEC =
            StreamCodec.of(FinderSyncPayload::write, FinderSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, FinderSyncPayload payload) {
        buf.writeVarInt(payload.tier());
        buf.writeVarInt(payload.targets().size());
        for (FinderTarget target : payload.targets()) {
            buf.writeUtf(target.structureId());
            buf.writeBlockPos(target.pos());
            // distanceSq is recomputed client-side against the live player position, so the
            // search-time value is not worth the bytes.
        }
    }

    private static FinderSyncPayload read(RegistryFriendlyByteBuf buf) {
        int tier = buf.readVarInt();
        int count = Math.min(buf.readVarInt(), MAX_TARGETS);
        List<FinderTarget> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String structureId = buf.readUtf();
            BlockPos pos = buf.readBlockPos();
            targets.add(new FinderTarget(structureId, pos, 0.0));
        }
        return new FinderSyncPayload(tier, List.copyOf(targets));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
