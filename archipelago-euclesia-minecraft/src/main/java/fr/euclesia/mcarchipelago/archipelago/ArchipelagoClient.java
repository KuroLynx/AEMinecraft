package fr.euclesia.mcarchipelago.archipelago;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
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
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderService;
import fr.euclesia.mcarchipelago.server.gameplay.FillerTrapService;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureService;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ArchipelagoClient {
    private static final String MINECRAFT_GAME = "Minecraft [AEM]";

    private final APTransport transport;
    private final AEMRegistries registries;
    private final APSessionState state = new APSessionState();
    private final List<APEventListener> listeners = new ArrayList<>();
    private final APDataPackageCache dataPackageCache = new APDataPackageCache();
    // Per-game data-package checksums from RoomInfo, used to decide what to fetch vs serve from cache.
    private final Map<String, String> roomChecksums = new HashMap<>();
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
        AEMDebug.log("send cmd={}", packet.command());
        return transport.send(APPacketCodec.encodeWire(packet));
    }

    public CompletableFuture<Void> sendAll(Collection<? extends APPacket> packets) {
        if (AEMDebug.enabled()) {
            AEMDebug.log("sendAll {} packets: {}", packets.size(),
                    packets.stream().map(packet -> packet.command().toString()).toList());
        }
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

        // Capture per-game data-package checksums so the connect step can serve unchanged packages
        // from the on-disk cache instead of re-downloading them.
        roomChecksums.clear();
        JsonElement checksums = packet.payload().get("datapackage_checksums");
        if (checksums != null && checksums.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : checksums.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    roomChecksums.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }

        AEMDebug.log("RoomInfo: {} game checksums {}", roomChecksums.size(), roomChecksums.keySet());
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

        // The registries are process-wide singletons, so a Connected packet is the one point where
        // the previous slot's session has to be thrown away: connecting a second slot without
        // restarting the game otherwise keeps its locations, checks and items and the advancement
        // tracker still shows the old seed. Two things this depends on:
        //  - load UNCONDITIONALLY: an absent slot_data means "this slot has none", not "keep the
        //    last slot's", and each loadSlotData clears before it fills.
        //  - drop the received items here rather than waiting for ReceivedItems index 0, which the
        //    server only sends when the new slot actually has items — a slot with an empty start
        //    inventory would otherwise inherit the previous slot's, colouring the tracker green.
        // Deliberately not done on disconnect: the gameplay gates (mob/structure locks, material
        // handling, Knowledge) read these registries, and wiping them on a transient drop would
        // silently unlock the world mid-game.
        state.setSlotData(payload.has("slot_data") && payload.get("slot_data").isJsonObject()
                ? payload.getAsJsonObject("slot_data")
                : new JsonObject());
        registries.apItems().resetReceived();
        registries.apItems().loadSlotData(state.parsedSlotData());
        registries.apLocations().loadSlotData(state.parsedSlotData());
        registries.apMobs().loadSlotData(state.parsedSlotData());
        registries.apStructures().loadSlotData(state.parsedSlotData());
        registries.apMaterials().loadSlotData(state.parsedSlotData());
        registries.apTrackers().loadFromSlotData(state.slotData());

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

        // slot_info maps each slot to the game it plays; needed to resolve other games' item/location
        // ids (which only mean something within their own game's data package) for chat rendering.
        Map<Integer, String> gameBySlot = new HashMap<>();
        if (payload.has("slot_info") && payload.get("slot_info").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : payload.getAsJsonObject("slot_info").entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                Integer slotNumber = parseSlotKey(entry.getKey());
                String game = APJson.getString(entry.getValue().getAsJsonObject(), "game", "");
                if (slotNumber != null && !game.isEmpty()) {
                    gameBySlot.put(slotNumber, game);
                }
            }
        }
        registries.apGameData().setGameBySlot(gameBySlot);

        state.missingLocations().clear();
        state.checkedLocations().clear();
        readLocations(payload, "missing_locations", state.missingLocations());
        readLocations(payload, "checked_locations", state.checkedLocations());
        registries.apLocations().replaceMissing(state.missingLocations());
        registries.apLocations().markChecked(state.checkedLocations());

        AEMDebug.log("Connected: team={} slot={} slotDataKeys={} players={} slotInfoGames={} missing={} checked={} locationsLoaded={}",
                state.team(), state.slot(), state.slotData().keySet(), state.playerNamesBySlot().size(),
                gameBySlot.size(), state.missingLocations().size(), state.checkedLocations().size(),
                registries.apLocations().hasLocations());
        AEM.LOGGER.info("Connected to Archipelago as team {} slot {}", state.team(), state.slot());
        send(new StatusUpdatePacket(APClientStatus.CLIENT_PLAYING));
        requestMissingDataPackages();
        listeners.forEach(listener -> listener.onConnected(client, packet));
    }

    private void handleConnectionRefused(ArchipelagoClient client, APReceivedPacket packet) {
        AEM.LOGGER.error("Archipelago connection refused: {}", packet.payload().get("errors"));
        listeners.forEach(listener -> listener.onConnectionRefused(client, packet));
    }

    private void handleReceivedItems(ArchipelagoClient client, APReceivedPacket packet) {
        ReceivedItemsPacket receivedItems = ReceivedItemsPacket.from(packet);
        AEMDebug.log("ReceivedItems: index={} count={} (index 0 = full resync)",
                receivedItems.index(), receivedItems.items().size());
        // index 0 is the full inventory (sent on (re)connect); rebuild counts from scratch.
        if (receivedItems.index() == 0) {
            registries.apItems().resetReceived();
        }
        Set<String> newlyUnlockedStructures = new HashSet<>();
        Set<String> newlyUnlockedMobs = new HashSet<>();
        receivedItems.items().forEach(item -> {
            registries.apItems().markReceived(item);
            AEMDebug.log("  item id={} name='{}' fromSlot={} flags={}", item.itemId(),
                    registries.apItems().name(item.itemId()).orElse("?"), item.player(), item.classification());
            newlyUnlockedMobs.addAll(registries.apMobs().markUnlockedByItem(item.itemId()));
            newlyUnlockedStructures.addAll(registries.apStructures().markUnlockedByItem(item.itemId()));
        });
        if (!newlyUnlockedStructures.isEmpty() || !newlyUnlockedMobs.isEmpty()) {
            AEMDebug.log("  unlocked structures={} mobs={}", newlyUnlockedStructures, newlyUnlockedMobs);
        }
        applyStructureUnlocks(newlyUnlockedStructures, newlyUnlockedMobs);

        // Grant the soulbound Biome Finder to anyone who just (re)gained it. Cheap and idempotent, so
        // we run it on every batch rather than diffing for the specific item.
        MinecraftServer biomeServer = AEMServerRuntime.server();
        if (biomeServer != null) {
            // The index-0 batch is the whole item history replayed on (re)connect, not something that
            // just happened, so its traps are not sprung — see FillerTrapService.Mode. Usually moot
            // (the persisted mark is already caught up), but a player who was in the world before the
            // session connected has a mark of 0 and would otherwise eat the run's entire trap list.
            FillerTrapService.Mode mode = receivedItems.index() == 0
                    ? FillerTrapService.Mode.CATCH_UP
                    : FillerTrapService.Mode.LIVE;
            biomeServer.execute(() -> {
                BiomeFinderService.ensureGrantedToAll(biomeServer);
                // Grant filler contents / fire trap effects for any newly received items.
                FillerTrapService.applyPendingToAll(biomeServer, mode);
            });
        }

        listeners.forEach(listener -> {
            listener.onReceivedItems(client, packet);
            listener.onReceivedItems(client, receivedItems);
        });
    }

    private void applyStructureUnlocks(Set<String> newlyUnlockedStructures, Set<String> newlyUnlockedMobs) {
        if (newlyUnlockedStructures.isEmpty() && newlyUnlockedMobs.isEmpty()) {
            return;
        }
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        // Hop off the network thread: world mutation must run on the server thread. Apply structures
        // first so any held mobs unlocked in the same batch can populate the freshly-placed structures.
        server.execute(() -> {
            StructureCaptureService.applyUnlocked(server, newlyUnlockedStructures);
            StructureCaptureService.spawnPendingMobs(server, newlyUnlockedMobs);
        });
    }

    private void handleLocationInfo(ArchipelagoClient client, APReceivedPacket packet) {
        LocationInfoPacket locationInfo = LocationInfoPacket.from(packet);
        AEMDebug.log("LocationInfo: {} scouted locations", locationInfo.locations().size());
        listeners.forEach(listener -> {
            listener.onLocationInfo(client, packet);
            listener.onLocationInfo(client, locationInfo);
        });
    }

    private void handleRoomUpdate(ArchipelagoClient client, APReceivedPacket packet) {
        JsonObject payload = packet.payload();
        readLocations(payload, "checked_locations", state.checkedLocations());
        Set<Long> newlyChecked = APJson.longSet(payload, "checked_locations");
        registries.apLocations().markChecked(newlyChecked);
        if (!newlyChecked.isEmpty()) {
            AEMDebug.log("RoomUpdate: {} checked_locations now {} total", newlyChecked.size(), state.checkedLocations().size());
        }
        listeners.forEach(listener -> listener.onRoomUpdate(client, packet));
    }

    private void handleDataPackage(ArchipelagoClient client, APReceivedPacket packet) {
        JsonElement data = packet.payload().get("data");
        if (data != null && data.isJsonObject()) {
            JsonElement games = data.getAsJsonObject().get("games");
            if (games != null && games.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : games.getAsJsonObject().entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject gameData = entry.getValue().getAsJsonObject();
                    applyGameData(entry.getKey(), gameData);
                    // Prefer the package's own checksum; fall back to the RoomInfo one it was fetched for.
                    String checksum = APJson.getString(gameData, "checksum",
                            roomChecksums.getOrDefault(entry.getKey(), ""));
                    dataPackageCache.store(entry.getKey(), checksum, gameData);
                    AEMDebug.log("DataPackage game='{}' items={} locations={} cached (checksum={})",
                            entry.getKey(),
                            sizeOf(gameData, "item_name_to_id"), sizeOf(gameData, "location_name_to_id"), checksum);
                }
            }
        }

        listeners.forEach(listener -> listener.onDataPackage(client, packet));
    }

    private static int sizeOf(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject().size() : 0;
    }

    /**
     * Loads each room game's data package, serving unchanged ones from the on-disk cache and
     * requesting only the games whose checksum is new or changed. With no advertised checksums
     * (older server) it falls back to requesting every game.
     */
    private void requestMissingDataPackages() {
        if (roomChecksums.isEmpty()) {
            send(new GetDataPackagePacket());
            return;
        }

        List<String> toRequest = new ArrayList<>();
        for (Map.Entry<String, String> entry : roomChecksums.entrySet()) {
            JsonObject cached = dataPackageCache.load(entry.getKey(), entry.getValue());
            if (cached != null) {
                applyGameData(entry.getKey(), cached);
            } else {
                toRequest.add(entry.getKey());
            }
        }

        if (toRequest.isEmpty()) {
            AEM.LOGGER.info("All {} Archipelago data packages served from cache", roomChecksums.size());
        } else {
            AEM.LOGGER.info("Requesting {} of {} Archipelago data packages ({} cached)",
                    toRequest.size(), roomChecksums.size(), roomChecksums.size() - toRequest.size());
            send(new GetDataPackagePacket(toRequest));
        }
    }

    /** Registers a game's id<->name maps; Minecraft's also feed the gameplay item/location registries. */
    private void applyGameData(String game, JsonObject gameData) {
        registries.apGameData().loadGame(game, gameData);
        if (MINECRAFT_GAME.equals(game)) {
            registries.apItems().registerNameToId(APJson.stringLongMap(gameData, "item_name_to_id"));
            registries.apLocations().registerNameToId(APJson.stringLongMap(gameData, "location_name_to_id"));
        }
    }

    private void handleInvalidPacket(ArchipelagoClient client, APReceivedPacket packet) {
        AEM.LOGGER.warn("Archipelago rejected packet: {}", packet.payload());
    }

    private static Integer parseSlotKey(String key) {
        try {
            return Integer.parseInt(key.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
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
                    AEMDebug.log("recv cmd={}", packet.command());
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
            // A failure on a session that was never established is almost always the wss:// attempt
            // being refused by a server that only speaks ws:// — the connector then falls back and
            // connects. Logging that at ERROR with a stack trace made a routine, successful startup
            // look like a crash, and buried the errors that do matter. One line, and the fallback
            // reports its own success immediately after.
            if (!state.isConnected()) {
                AEM.LOGGER.info("Archipelago connect attempt failed ({}); trying the next address.",
                        throwable.toString());
                return;
            }
            AEM.LOGGER.error("Archipelago transport error", throwable);
        }

        @Override
        public void onClose(int statusCode, String reason) {
            boolean wasConnected = state.isConnected();
            state.setConnected(false);
            AEM.LOGGER.info("Archipelago WebSocket closed: {} {}", statusCode, reason);
            // Only a drop of a live session counts; ignore closes from failed connect attempts (the
            // wss:// -> ws:// fallback) so they don't trigger a kick.
            if (wasConnected) {
                listeners.forEach(listener -> listener.onDisconnected(ArchipelagoClient.this));
            }
        }
    }
}
