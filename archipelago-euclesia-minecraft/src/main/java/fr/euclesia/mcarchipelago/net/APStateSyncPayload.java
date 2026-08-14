package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The Archipelago session, pushed from the server to a client that has no session of its own.
 *
 * <p>In singleplayer the client and the server share a process, so the advancement-screen overlay,
 * the tracker tab and the finder bars all read {@code AEM.ARCHIPELAGO} directly. On a dedicated
 * server that instance exists in the client JVM but never connects, so every tile resolved to
 * UNKNOWN and the tracker simply did not work. This payload is what closes that gap: the server
 * holds the only real session and mirrors it down.
 *
 * <p>The body is gzipped JSON. That is not premature optimisation — the slot's logic graph is the
 * bulk of it and runs to hundreds of kilobytes uncompressed, close enough to Minecraft's per-payload
 * ceiling to be worth not gambling on. It compresses extremely well (it is repetitive JSON), and the
 * cost is one deflate per send on a payload that is sent rarely.
 *
 * <p>Deliberately one payload rather than three. Slot data, received items and checked locations are
 * read together by the reachability pass, so splitting them would let a client evaluate a graph
 * against the wrong item set and paint tiles that flip a tick later.
 */
public record APStateSyncPayload(byte[] gzippedJson) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "ap_state");
    public static final CustomPacketPayload.Type<APStateSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /** Generous, but bounded: a malicious or broken server must not be able to make a client allocate freely. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public static final StreamCodec<RegistryFriendlyByteBuf, APStateSyncPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeByteArray(payload.gzippedJson()),
                    buf -> new APStateSyncPayload(buf.readByteArray(MAX_BYTES)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static APStateSyncPayload of(String json) {
        return new APStateSyncPayload(gzip(json));
    }

    /** The JSON body, or {@code null} if it cannot be read (a truncated or corrupt payload). */
    public String json() {
        return gunzip(gzippedJson);
    }

    private static byte[] gzip(String json) {
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 8));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        } catch (IOException exception) {
            AEM.LOGGER.error("Could not compress Archipelago state for sync", exception);
            return new byte[0];
        }
        return out.toByteArray();
    }

    private static String gunzip(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return null;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AEM.LOGGER.error("Could not read Archipelago state sync payload", exception);
            return null;
        }
    }
}
