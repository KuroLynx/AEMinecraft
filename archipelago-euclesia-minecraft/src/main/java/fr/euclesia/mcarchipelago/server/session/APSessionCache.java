package fr.euclesia.mcarchipelago.server.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.APItemClassification;
import fr.euclesia.mcarchipelago.protocol.packet.inbound.APNetworkItem;
import fr.euclesia.mcarchipelago.registry.AEMRegistries;
import fr.euclesia.mcarchipelago.server.connect.APWorldPaths;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The last known Archipelago session, written into the world so the world can be played without one.
 *
 * <p>Everything this mod gates on — which advancements are checks, which mobs and structures are
 * locked, which unlock items have arrived — comes from the slot data and the received-item list, and
 * both are handed over once at connect time. That made the session a permanent dependency: the link
 * dropping meant the locks could no longer tell locked content from free, so the only safe answer was
 * to lock everything and put the players out.
 *
 * <p>It was only a dependency because the data was never written down. Once it is, a world that has
 * connected even once knows its own slot for good, and a later session becomes a way to exchange
 * progress rather than a precondition for playing. So the first connect still ships the data — there
 * is nothing to fall back to yet, and generating without it would place locked content for real —
 * and every load after it may fall back to this file.
 *
 * <h2>Two files, because one of them is enormous</h2>
 *
 * <p>The slot data is the compiled logic graph and runs to megabytes, and it is fixed for the life of
 * the slot: the room sends the same bytes on every connect. Progress — which locations are checked,
 * which items have arrived — is a few lists of numbers that change constantly. Keeping them in one
 * file would mean rewriting several megabytes on every {@code ReceivedItems} packet, and a mass
 * release from another world sends a lot of those in a row.
 *
 * <p>So {@code session_slot.json} is written once per connect and {@code session_progress.json} on
 * every change. Progress is the one that must never be stale; the slot half has nothing to go stale.
 *
 * <p>Deliberately NOT stored: the data package (the name-to-id tables for every game in the room).
 * Those are already cached by checksum, only render other players' names in chat, and gate nothing —
 * an offline run shows raw ids in the few messages it can still produce rather than keeping a second
 * copy of the whole room.
 */
public final class APSessionCache {
    private static final String SLOT_FILE = "session_slot.json";
    private static final String PROGRESS_FILE = "session_progress.json";
    private static final Gson GSON = new GsonBuilder().create();

    /** Bumped when either shape below changes incompatibly; an older file is ignored, not migrated. */
    private static final int FORMAT = 1;

    // Set by the client pre-flight when it has already tried and failed to reach the room, so the
    // integrated server does not spend the whole connect timeout failing at the same address again.
    // Consumed once, at server start; a later /aem reconnect is unaffected.
    private static volatile boolean offlineStartRequested;

    private APSessionCache() {}

    /** The heavy half: fixed for the life of the slot. */
    private static final class SlotSnapshot {
        int format = FORMAT;
        int team = -1;
        int slot = -1;
        JsonObject slotData = new JsonObject();
        Map<String, String> playerNames = new HashMap<>();
    }

    /** The light half: changes constantly, so it is written on every change. */
    private static final class ProgressSnapshot {
        int format = FORMAT;
        List<Long> checkedLocations = new ArrayList<>();
        List<Long> missingLocations = new ArrayList<>();
        /** Arrival order matters: FillerTrapService indexes this list by its high-water mark. */
        List<Long> receivedItems = new ArrayList<>();
    }

    /** Tells the next server start not to retry a connection the caller has already failed. */
    public static void requestOfflineStart() {
        offlineStartRequested = true;
    }

    /** Whether to skip this start's connect attempt, clearing the request. */
    public static boolean consumeOfflineStart() {
        boolean requested = offlineStartRequested;
        offlineStartRequested = false;
        return requested;
    }

    /**
     * Writes the slot half. Called once per connect: the room hands over the same bytes every time,
     * so there is nothing to keep up to date, and this is the expensive write.
     */
    public static void saveSlot(MinecraftServer server) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        if (server == null || !client.state().isConnected()) {
            return;
        }
        SlotSnapshot snapshot = new SlotSnapshot();
        snapshot.team = client.state().team();
        snapshot.slot = client.state().slot();
        snapshot.slotData = client.state().slotData();
        client.state().playerNamesBySlot()
                .forEach((playerSlot, name) -> snapshot.playerNames.put(String.valueOf(playerSlot), name));
        write(server, SLOT_FILE, snapshot);
    }

    /**
     * Writes the progress half, so a drop or a crash costs nothing that was already earned. Cheap
     * enough to call on every packet that moves either list.
     */
    public static void saveProgress(MinecraftServer server) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        if (server == null || !client.state().isConnected()) {
            return;  // never overwrite a good snapshot with a half-built one
        }
        ProgressSnapshot snapshot = new ProgressSnapshot();
        snapshot.checkedLocations = new ArrayList<>(client.state().checkedLocations());
        snapshot.missingLocations = new ArrayList<>(client.state().missingLocations());
        snapshot.receivedItems = client.registries().apItems().receivedOrder();
        write(server, PROGRESS_FILE, snapshot);
    }

    /** Whether this world has a session cached, i.e. it has connected successfully at least once. */
    public static boolean exists(Path worldRoot) {
        return read(worldRoot, SLOT_FILE, SlotSnapshot.class) != null;
    }

    /**
     * Restores the cached session into the live client, so every gate reads what it would have read
     * online. Returns {@code false} when there is no usable cache — the caller must then insist on a
     * real connection rather than let the world generate blind.
     *
     * <p>The registries are filled the way {@code handleConnected} fills them, including the mob and
     * structure unlocks each received item implies. What it does NOT do is apply those unlocks to the
     * world ({@code StructureCaptureService.applyUnlocked}): they were applied when the items first
     * arrived, and re-applying them here would re-place structures on every offline load.
     */
    public static boolean restore(Path worldRoot) {
        SlotSnapshot slotSnapshot = read(worldRoot, SLOT_FILE, SlotSnapshot.class);
        if (slotSnapshot == null) {
            return false;
        }
        // Progress may legitimately be missing (a world that connected but never earned or received
        // anything), so an absent file is an empty one rather than a failure.
        ProgressSnapshot progress = read(worldRoot, PROGRESS_FILE, ProgressSnapshot.class);
        if (progress == null) {
            progress = new ProgressSnapshot();
        }

        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        AEMRegistries registries = client.registries();

        client.state().setTeam(slotSnapshot.team);
        client.state().setSlot(slotSnapshot.slot);
        client.state().setSlotData(
                slotSnapshot.slotData == null ? new JsonObject() : slotSnapshot.slotData);

        registries.apItems().resetReceived();
        registries.apItems().loadSlotData(client.state().parsedSlotData());
        registries.apLocations().loadSlotData(client.state().parsedSlotData());
        registries.apMobs().loadSlotData(client.state().parsedSlotData());
        registries.apStructures().loadSlotData(client.state().parsedSlotData());
        registries.apMaterials().loadSlotData(client.state().parsedSlotData());
        registries.apTrackers().loadFromSlotData(client.state().slotData());

        // Replay the item history in order. markUnlockedByItem is what turns an item id into "this
        // mob/structure is no longer locked", and the locks read those registries, not the raw ids.
        for (Long itemId : nullSafe(progress.receivedItems)) {
            registries.apItems().markReceived(
                    new APNetworkItem(itemId, -1L, -1, APItemClassification.NORMAL));
            registries.apMobs().markUnlockedByItem(itemId);
            registries.apStructures().markUnlockedByItem(itemId);
        }

        client.state().missingLocations().clear();
        client.state().missingLocations().addAll(nullSafe(progress.missingLocations));
        client.state().checkedLocations().clear();
        client.state().checkedLocations().addAll(nullSafe(progress.checkedLocations));
        registries.apLocations().replaceMissing(client.state().missingLocations());
        registries.apLocations().markChecked(client.state().checkedLocations());

        client.state().playerNamesBySlot().clear();
        if (slotSnapshot.playerNames != null) {
            slotSnapshot.playerNames.forEach((slotKey, name) -> {
                try {
                    client.state().playerNamesBySlot().put(Integer.parseInt(slotKey), name);
                } catch (NumberFormatException ignored) {
                    // A hand-edited file; the name is cosmetic, so skip it rather than fail the load.
                }
            });
        }

        if (!registries.apLocations().hasLocations()) {
            // A cache that carries no locations is not usable slot data — treat it as no cache at
            // all, so the caller falls back to demanding a real connection instead of opening the
            // gate over empty registries.
            AEM.LOGGER.error("The cached Archipelago slot data for this world has no locations in it; "
                    + "ignoring it and requiring a live connection.");
            return false;
        }
        AEM.LOGGER.info("Restored the cached Archipelago session offline: slot {}, {} checked, "
                        + "{} items received.",
                slotSnapshot.slot, client.state().checkedLocations().size(),
                nullSafe(progress.receivedItems).size());
        return true;
    }

    private static void write(MinecraftServer server, String fileName, Object snapshot) {
        try {
            Files.writeString(APWorldPaths.writePath(server, fileName), GSON.toJson(snapshot));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Could not save {}: {}", fileName, exception.toString());
        }
    }

    private static <T> T read(Path worldRoot, String fileName, Class<T> type) {
        Path file = APWorldPaths.readPath(worldRoot, fileName);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            T snapshot = GSON.fromJson(Files.readString(file), type);
            if (snapshot == null || formatOf(snapshot) != FORMAT) {
                AEM.LOGGER.warn("Ignoring {}: written in an older format.", fileName);
                return null;
            }
            return snapshot;
        } catch (IOException | JsonSyntaxException exception) {
            AEM.LOGGER.warn("Could not read {}: {}", fileName, exception.toString());
            return null;
        }
    }

    private static int formatOf(Object snapshot) {
        if (snapshot instanceof SlotSnapshot slotSnapshot) {
            return slotSnapshot.format;
        }
        if (snapshot instanceof ProgressSnapshot progress) {
            return progress.format;
        }
        return -1;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
