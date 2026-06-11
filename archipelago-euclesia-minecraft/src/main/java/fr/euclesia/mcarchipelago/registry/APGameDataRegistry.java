package fr.euclesia.mcarchipelago.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cross-game id↔name data from the Archipelago data package, keyed by game name. Item and location
 * ids are only unique within a single game's namespace — different games reuse the same numbers —
 * so resolving an id from a {@code PrintJSON} message requires knowing which game owns it. That
 * comes from the message part's {@code "player"} slot, mapped to a game via the Connected packet's
 * {@code slot_info}. Used to render item/location names for other games' slots in chat (the
 * Minecraft-only {@link APItemRegistry}/{@link APLocationRegistry} can't, and would mis-resolve on
 * a colliding id).
 */
public final class APGameDataRegistry {
    private final Map<String, Map<Long, String>> itemNamesByGame = new HashMap<>();
    private final Map<String, Map<Long, String>> locationNamesByGame = new HashMap<>();
    private final Map<Integer, String> gameBySlot = new HashMap<>();

    /** Loads one game's data-package entry ({@code item_name_to_id}/{@code location_name_to_id}). */
    public void loadGame(String game, JsonObject gameData) {
        itemNamesByGame.put(game, reverse(gameData.get("item_name_to_id")));
        locationNamesByGame.put(game, reverse(gameData.get("location_name_to_id")));
    }

    /** Records which game each slot is playing (from the Connected packet's {@code slot_info}). */
    public void setGameBySlot(Map<Integer, String> games) {
        gameBySlot.clear();
        gameBySlot.putAll(games);
    }

    /** Item name for an id owned by the given slot's game, if both the slot's game and the id are known. */
    public Optional<String> itemName(int slot, long itemId) {
        return lookup(itemNamesByGame, slot, itemId);
    }

    /** Location name for an id owned by the given slot's game, if known. */
    public Optional<String> locationName(int slot, long locationId) {
        return lookup(locationNamesByGame, slot, locationId);
    }

    private Optional<String> lookup(Map<String, Map<Long, String>> namesByGame, int slot, long id) {
        String game = gameBySlot.get(slot);
        if (game == null) {
            return Optional.empty();
        }
        Map<Long, String> names = namesByGame.get(game);
        return names == null ? Optional.empty() : Optional.ofNullable(names.get(id));
    }

    private static Map<Long, String> reverse(JsonElement nameToId) {
        Map<Long, String> idToName = new HashMap<>();
        if (nameToId != null && nameToId.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : nameToId.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    idToName.put(entry.getValue().getAsLong(), entry.getKey());
                }
            }
        }
        return idToName;
    }
}
