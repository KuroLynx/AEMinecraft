package fr.euclesia.mcarchipelago.archipelago;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.protocol.APClientStatus;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;
import fr.euclesia.mcarchipelago.protocol.APPacketCodec;
import fr.euclesia.mcarchipelago.protocol.APProtocolException;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;
import fr.euclesia.mcarchipelago.protocol.packet.inbound.LocationInfoPacket;
import fr.euclesia.mcarchipelago.protocol.packet.inbound.ReceivedItemsPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.ConnectPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.GetDataPackagePacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.StatusUpdatePacket;
import fr.euclesia.mcarchipelago.protocol.registry.APHandlerRegistry;
import fr.euclesia.mcarchipelago.protocol.transport.APTransport;
import fr.euclesia.mcarchipelago.protocol.transport.APTransportListener;
import fr.euclesia.mcarchipelago.registry.AEMRegistries;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ArchipelagoClient {
    private final APTransport transport;
    private final AEMRegistries registries;
    private final APSessionState state = new APSessionState();
    private final List<APEventListener> listeners = new ArrayList<>();
    private APConnectionOptions options;

    public ArchipelagoClient(APTransport transport) {
        this(transport, new AEMRegistries());
    }

    public ArchipelagoClient(APTransport transport, AEMRegistries registries) {
        this.transport = transport;
        this.registries = registries;
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
        return registries.apHandlers();
    }

    public AEMRegistries registries() {
        return registries;
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
        handlers().register(APCommand.ROOM_INFO, this::handleRoomInfo);
        handlers().register(APCommand.CONNECTED, this::handleConnected);
        handlers().register(APCommand.CONNECTION_REFUSED, this::handleConnectionRefused);
        handlers().register(APCommand.RECEIVED_ITEMS, this::handleReceivedItems);
        handlers().register(APCommand.LOCATION_INFO, this::handleLocationInfo);
        handlers().register(APCommand.ROOM_UPDATE, this::handleRoomUpdate);
        handlers().register(APCommand.PRINT_JSON, (client, packet) -> listeners.forEach(listener -> listener.onPrintJson(client, packet)));
        handlers().register(APCommand.DATA_PACKAGE, this::handleDataPackage);
        handlers().register(APCommand.BOUNCED, (client, packet) -> listeners.forEach(listener -> listener.onBounced(client, packet)));
        handlers().register(APCommand.INVALID_PACKET, this::handleInvalidPacket);
        handlers().register(APCommand.RETRIEVED, (client, packet) -> listeners.forEach(listener -> listener.onRetrieved(client, packet)));
        handlers().register(APCommand.SET_REPLY, (client, packet) -> listeners.forEach(listener -> listener.onSetReply(client, packet)));
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
            registries.apItems().loadSlotData(state.parsedSlotData());
            registries.apLocations().loadSlotData(state.parsedSlotData());
            registries.apMobs().loadSlotData(state.parsedSlotData());
            registries.apTrackers().loadFromSlotData(state.slotData());
        }

        state.playerNamesBySlot().clear();
        if (payload.has("players") && payload.get("players").isJsonArray()) {
            for (JsonElement element : payload.getAsJsonArray("players")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject player = element.getAsJsonObject();
                int playerSlot = APJson.getInt(player, "slot", -1);
                String alias = APJson.getString(player, "alias", APJson.getString(player, "name", ""));
                if (playerSlot >= 0 && !alias.isEmpty()) {
                    state.playerNamesBySlot().put(playerSlot, alias);
                }
            }
        }

        state.missingLocations().clear();
        state.checkedLocations().clear();
        readLocations(payload, "missing_locations", state.missingLocations());
        readLocations(payload, "checked_locations", state.checkedLocations());
        registries.apLocations().replaceMissing(state.missingLocations());
        registries.apLocations().markChecked(state.checkedLocations());

        AEM.LOGGER.info("Connected to Archipelago as team {} slot {}", state.team(), state.slot());
        send(new StatusUpdatePacket(APClientStatus.CLIENT_PLAYING));
        send(new GetDataPackagePacket());
        listeners.forEach(listener -> listener.onConnected(client, packet));
    }

    private void handleConnectionRefused(ArchipelagoClient client, APReceivedPacket packet) {
        AEM.LOGGER.error("Archipelago connection refused: {}", packet.payload().get("errors"));
    }

    private void handleReceivedItems(ArchipelagoClient client, APReceivedPacket packet) {
        ReceivedItemsPacket receivedItems = ReceivedItemsPacket.from(packet);
        // index 0 is the full inventory (sent on (re)connect); rebuild counts from scratch.
        if (receivedItems.index() == 0) {
            registries.apItems().resetReceived();
        }
        receivedItems.items().forEach(item -> {
            registries.apItems().markReceived(item);
            registries.apMobs().markUnlockedByItem(item.itemId());
        });

        listeners.forEach(listener -> {
            listener.onReceivedItems(client, packet);
            listener.onReceivedItems(client, receivedItems);
        });
    }

    private void handleLocationInfo(ArchipelagoClient client, APReceivedPacket packet) {
        LocationInfoPacket locationInfo = LocationInfoPacket.from(packet);
        listeners.forEach(listener -> {
            listener.onLocationInfo(client, packet);
            listener.onLocationInfo(client, locationInfo);
        });
    }

    private void handleRoomUpdate(ArchipelagoClient client, APReceivedPacket packet) {
        JsonObject payload = packet.payload();
        readLocations(payload, "checked_locations", state.checkedLocations());
        registries.apLocations().markChecked(APJson.longSet(payload, "checked_locations"));
        listeners.forEach(listener -> listener.onRoomUpdate(client, packet));
    }

    private void handleDataPackage(ArchipelagoClient client, APReceivedPacket packet) {
        JsonObject minecraftData = minecraftDataPackage(packet.payload());
        if (minecraftData != null) {
            registries.apItems().registerNameToId(APJson.stringLongMap(minecraftData, "item_name_to_id"));
            registries.apLocations().registerNameToId(APJson.stringLongMap(minecraftData, "location_name_to_id"));
        }

        listeners.forEach(listener -> listener.onDataPackage(client, packet));
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

    private static JsonObject minecraftDataPackage(JsonObject payload) {
        JsonElement data = payload.get("data");
        if (data == null || !data.isJsonObject()) {
            return null;
        }

        JsonElement games = data.getAsJsonObject().get("games");
        if (games == null || !games.isJsonObject()) {
            return null;
        }

        JsonElement minecraft = games.getAsJsonObject().get("Minecraft");
        return minecraft != null && minecraft.isJsonObject() ? minecraft.getAsJsonObject() : null;
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
                    if (!handlers().dispatch(ArchipelagoClient.this, packet)) {
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
