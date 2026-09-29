package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A player's logic report on one advancement tile: what they think is wrong ({@code kind}), an
 * optional note, and the colour their tracker showed ({@code logicState}) — the client is the only
 * side that evaluates logic, so it sends what it saw. The server writes it out, see {@link LogicReportNet}.
 */
public record LogicReportPayload(String advancementId, String kind, String note, String logicState)
        implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "logic_report");
    public static final CustomPacketPayload.Type<LogicReportPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, LogicReportPayload> CODEC =
            StreamCodec.of(LogicReportPayload::write, LogicReportPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, LogicReportPayload payload) {
        buf.writeUtf(payload.advancementId());
        buf.writeUtf(payload.kind());
        buf.writeUtf(payload.note(), LogicReportNet.MAX_NOTE_LENGTH);
        buf.writeUtf(payload.logicState());
    }

    private static LogicReportPayload read(RegistryFriendlyByteBuf buf) {
        return new LogicReportPayload(buf.readUtf(), buf.readUtf(),
                buf.readUtf(LogicReportNet.MAX_NOTE_LENGTH), buf.readUtf());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
