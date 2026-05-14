package fr.euclesia.mcarchipelago.archipelago;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.protocol.APClientStatus;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APPacket;
import fr.euclesia.mcarchipelago.protocol.APPacketCodec;
import fr.euclesia.mcarchipelago.protocol.APProtocolException;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.ConnectPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.GetDataPackagePacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.StatusUpdatePacket;
import fr.euclesia.mcarchipelago.protocol.registry.APHandlerRegistry;
import fr.euclesia.mcarchipelago.protocol.transport.APTransport;
import fr.euclesia.mcarchipelago.protocol.transport.APTransportListener;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ArchipelagoClient {
    private final APTransport transport;
    private final APHandlerRegistry handlers;
    private final APSessionState state = new APSessionState();
    private final List<APEventListener> listeners = new ArrayList<>();
    private APConnectionOptions options;

    public ArchipelagoClient(APTransport transport) {
        this.transport = transport;
        this.handlers = new APHandlerRegistry();
        registerDefaultHandlers();
    }

    public CompletableFuture<Void> connect(URI uri, APConnectionOptions options) {
        this.options = options;
        return transport.connect(uri, new Listener());
    }

    public CompletableFuture<Void> send(APPacket packet) {
        return transport.send(APPacketCodec.encodeWire(packet));
    }

    public CompletableFuture<Void> sendAll(Collection<? extends APPacket> packets) {
        return transport.send(APPacketCodec.encodeWire(packets));
    }

    public void addListener(APEventListener listener) {
        listeners.add(listener);
    }

    public APHandlerRegistry handlers() {
        return handlers;
    }

    public APSessionState state() {
        return state;
    }

    public boolean isOpen() {
        return transport.isOpen();
    }

    public void close() {
        transport.close();
    }

    private void registerDefaultHandlers() {
        handlers.register(APCommand.ROOM_INFO, this::handleRoomInfo);
        handlers.register(APCommand.CONNECTED, this::handleConnected);
        handlers.register(APCommand.CONNECTION_REFUSED, this::handleConnectionRefused);
        handlers.register(APCommand.RECEIVED_ITEMS, (client, packet) -> listeners.forEach(listener -> listener.onReceivedItems(client, packet)));
        handlers.register(APCommand.LOCATION_INFO, (client, packet) -> listeners.forEach(listener -> listener.onLocationInfo(client, packet)));
        handlers.register(APCommand.ROOM_UPDATE, this::handleRoomUpdate);
        handlers.register(APCommand.PRINT_JSON, (client, packet) -> listeners.forEach(listener -> listener.onPrintJson(client, packet)));
        handlers.register(APCommand.DATA_PACKAGE, (client, packet) -> listeners.forEach(listener -> listener.onDataPackage(client, packet)));
        handlers.register(APCommand.BOUNCED, (client, packet) -> listeners.forEach(listener -> listener.onBounced(client, packet)));
        handlers.register(APCommand.INVALID_PACKET, this::handleInvalidPacket);
        handlers.register(APCommand.RETRIEVED, (client, packet) -> listeners.forEach(listener -> listener.onRetrieved(client, packet)));
        handlers.register(APCommand.SET_REPLY, (client, packet) -> listeners.forEach(listener -> listener.onSetReply(client, packet)));
    }

    private void handleRoomInfo(ArchipelagoClient client, APReceivedPacket packet) {
        if (options == null) {
            throw new APProtocolException("Received RoomInfo before connection options were configured");
        }

        AEM.LOGGER.info("Received Archipelago RoomInfo, sending Connect for slot {}", options.playerName());
        send(new ConnectPacket(
                options.game(),
                options.playerName(),
                options.password(),
                options.version(),
                options.itemsHandling(),
                options.tags(),
                options.uuid()
        ));
    }

    private void handleConnected(ArchipelagoClient client, APReceivedPacket packet) {
        JsonObject payload = packet.payload();
        state.setConnected(true);
        state.setTeam(payload.get("team").getAsInt());
        state.setSlot(payload.get("slot").getAsInt());

        if (payload.has("slot_data") && payload.get("slot_data").isJsonObject()) {
            state.setSlotData(payload.getAsJsonObject("slot_data"));
        }

        readLocations(payload, "missing_locations", state.missingLocations());
        readLocations(payload, "checked_locations", state.checkedLocations());

        AEM.LOGGER.info("Connected to Archipelago as team {} slot {}", state.team(), state.slot());
        send(new StatusUpdatePacket(APClientStatus.CLIENT_PLAYING));
        send(new GetDataPackagePacket());
        listeners.forEach(listener -> listener.onConnected(client, packet));
    }

    private void handleConnectionRefused(ArchipelagoClient client, APReceivedPacket packet) {
        AEM.LOGGER.error("Archipelago connection refused: {}", packet.payload().get("errors"));
    }

    private void handleRoomUpdate(ArchipelagoClient client, APReceivedPacket packet) {
        JsonObject payload = packet.payload();
        readLocations(payload, "checked_locations", state.checkedLocations());
        listeners.forEach(listener -> listener.onRoomUpdate(client, packet));
    }

    private void handleInvalidPacket(ArchipelagoClient client, APReceivedPacket packet) {
        AEM.LOGGER.warn("Archipelago rejected packet: {}", packet.payload());
    }

    private static void readLocations(JsonObject payload, String field, Collection<Long> target) {
        if (!payload.has(field) || !payload.get(field).isJsonArray()) {
            return;
        }

        JsonArray locations = payload.getAsJsonArray(field);
        for (JsonElement location : locations) {
            target.add(location.getAsLong());
        }
    }

    private final class Listener implements APTransportListener {
        @Override
        public void onOpen() {
            AEM.LOGGER.info("Archipelago WebSocket opened");
        }

        @Override
        public void onText(String message) {
            try {
                for (APReceivedPacket packet : APPacketCodec.decodeWire(message)) {
                    if (!handlers.dispatch(ArchipelagoClient.this, packet)) {
                        AEM.LOGGER.debug("Unhandled Archipelago packet: {}", packet.command());
                    }
                }
            } catch (RuntimeException exception) {
                AEM.LOGGER.error("Failed to handle Archipelago message: {}", message, exception);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            AEM.LOGGER.error("Archipelago transport error", throwable);
        }

        @Override
        public void onClose(int statusCode, String reason) {
            state.setConnected(false);
            AEM.LOGGER.info("Archipelago WebSocket closed: {} {}", statusCode, reason);
        }
    }
}
