package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.ap.HintLocationListener.Spot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This slot's unfound hints, item id → where each copy is, names already resolved. The full map
 * every time — on join and whenever the hint list changes — see {@code HintLocationListener}.
 */
public record HintLocationsPayload(Map<Long, List<Spot>> spots) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "hint_locations");
    public static final CustomPacketPayload.Type<HintLocationsPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, HintLocationsPayload> CODEC =
            StreamCodec.of(HintLocationsPayload::write, HintLocationsPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, HintLocationsPayload payload) {
        buf.writeVarInt(payload.spots().size());
        payload.spots().forEach((itemId, spots) -> {
            buf.writeLong(itemId);
            buf.writeVarInt(spots.size());
            for (Spot spot : spots) {
                buf.writeUtf(spot.player());
                buf.writeUtf(spot.location());
            }
        });
    }

    private static HintLocationsPayload read(RegistryFriendlyByteBuf buf) {
        int items = buf.readVarInt();
        Map<Long, List<Spot>> spots = new HashMap<>();
        for (int i = 0; i < items; i++) {
            long itemId = buf.readLong();
            int count = buf.readVarInt();
            List<Spot> list = new ArrayList<>(count);
            for (int j = 0; j < count; j++) {
                list.add(new Spot(buf.readUtf(), buf.readUtf()));
            }
            spots.put(itemId, list);
        }
        return new HintLocationsPayload(spots);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
