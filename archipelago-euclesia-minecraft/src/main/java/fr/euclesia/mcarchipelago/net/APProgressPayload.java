package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * What the client's view of the run needs updating with: the items received and the checks sent.
 *
 * <p>Split out from {@link APStateSyncPayload} because slot data does not change. The first version
 * of this sync shipped everything together on every update, and slot data is almost all of it — a
 * BACAP logic graph serializes to about 2.6 MB of JSON. Rebuilding and compressing that on the
 * WebSocket read thread for every ReceivedItems packet is bad enough with items trickling in; when
 * another player finishes their game and releases, items arrive in a long stream of packets and the
 * session thread spends its life re-serializing a graph that has not changed since connect.
 *
 * <p>So slot data is sent once, on join and on connect, and this carries the part that actually
 * moves. It is a few kilobytes of longs.
 */
public record APProgressPayload(List<Long> received, List<Long> checked) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "ap_progress");
    public static final CustomPacketPayload.Type<APProgressPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /** A slot's whole item/check history; generous, but not unbounded. */
    private static final int MAX_ENTRIES = 200_000;

    public static final StreamCodec<RegistryFriendlyByteBuf, APProgressPayload> CODEC =
            StreamCodec.of(APProgressPayload::write, APProgressPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, APProgressPayload payload) {
        writeLongs(buf, payload.received());
        writeLongs(buf, payload.checked());
    }

    private static APProgressPayload read(RegistryFriendlyByteBuf buf) {
        return new APProgressPayload(readLongs(buf), readLongs(buf));
    }

    private static void writeLongs(RegistryFriendlyByteBuf buf, List<Long> values) {
        buf.writeVarInt(values.size());
        for (long value : values) {
            buf.writeVarLong(value);
        }
    }

    private static List<Long> readLongs(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Long> values = new ArrayList<>(Math.min(count, 4096));
        for (int i = 0; i < count; i++) {
            values.add(buf.readVarLong());
        }
        return List.copyOf(values);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
