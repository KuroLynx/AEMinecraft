package fr.euclesia.mcarchipelago.server.ap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.archipelago.ChatFilterPreference;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;
import fr.euclesia.mcarchipelago.registry.AEMRegistries;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

import java.util.Set;

/**
 * Mirrors Archipelago {@code PrintJSON} messages (item sends, checks, hints, chat, joins…) into
 * the in-game chat — the same stream a text client shows. Ids in the message are resolved to
 * names via the registries / session state so it reads as e.g. "Player found Item (Location)".
 *
 * <p>When {@link ChatFilterPreference} is on, the multiworld firehose is trimmed to what actually
 * concerns this slot: an {@code ItemSend} or {@code Hint} is kept only if this slot is the
 * receiving end or the location's owner; {@code Join}, {@code Goal}, {@code Chat} and
 * {@code ServerChat} always pass regardless of whose slot they're about (connecting, reaching a
 * goal, and ordinary conversation are never "noise"); everything else ({@code Part},
 * {@code Tutorial}, {@code TagsChanged}, {@code CommandResult}, …) is dropped. See the
 * <a href="https://github.com/ArchipelagoMW/Archipelago/blob/main/docs/network%20protocol.md">AP
 * network protocol</a> for the {@code type}/{@code receiving}/{@code item} envelope fields this
 * reads.
 */
public final class ArchipelagoChatListener implements APEventListener {
    /** PrintJSON types that always pass the filter, regardless of which slot they're about. */
    private static final Set<String> ALWAYS_SHOWN = Set.of("Join", "Goal", "Chat", "ServerChat");

    @Override
    public void onPrintJson(ArchipelagoClient client, APReceivedPacket packet) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        JsonObject payload = packet.payload();
        if (ChatFilterPreference.enabled() && !passesFilter(client, payload)) {
            return;
        }
        JsonElement data = payload.get("data");
        if (data == null || !data.isJsonArray()) {
            return;
        }
        Component message = render(client, data.getAsJsonArray());
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(message, false));
    }

    /** Whether this PrintJSON message should show while the chat filter is on. */
    private boolean passesFilter(ArchipelagoClient client, JsonObject payload) {
        String type = APJson.getString(payload, "type", "");
        if (ALWAYS_SHOWN.contains(type)) {
            return true;
        }
        if (!"ItemSend".equals(type) && !"Hint".equals(type)) {
            return false;
        }
        int mySlot = client.state().slot();
        int receiving = APJson.getInt(payload, "receiving", -1);
        if (receiving == mySlot) {
            return true;
        }
        JsonElement itemElement = payload.get("item");
        if (itemElement == null || !itemElement.isJsonObject()) {
            return false;
        }
        int locationOwner = APJson.getInt(itemElement.getAsJsonObject(), "player", -1);
        return locationOwner == mySlot;
    }

    private Component render(ArchipelagoClient client, JsonArray data) {
        MutableComponent line = Component.empty();
        ChatFormatting carriedColor = null; // a {"type":"color"} part sets colour for following text
        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            String type = APJson.getString(part, "type", "text");
            if ("color".equals(type)) {
                carriedColor = apColor(APJson.getString(part, "text", ""));
                continue;
            }
            line.append(renderPart(client, part, type, carriedColor));
        }
        return line;
    }

    private MutableComponent renderPart(ArchipelagoClient client, JsonObject part, String type, ChatFormatting carried) {
        String text = APJson.getString(part, "text", "");
        AEMRegistries registries = client.registries();
        return switch (type) {
            case "player_id" -> {
                String name = client.state().playerName(parseInt(text));
                yield Component.literal(name != null ? name : text).withStyle(ChatFormatting.YELLOW);
            }
            case "item_id" -> {
                // "player" is the slot whose game owns this item id; resolve against that game's data
                // package first (ids collide across games), falling back to Minecraft's own map.
                long itemId = parseLong(text);
                int owner = APJson.getInt(part, "player", -1);
                String name = registries.apGameData().itemName(owner, itemId)
                        .orElseGet(() -> registries.apItems().name(itemId).orElse(text));
                yield Component.literal(name).withStyle(itemColor(APJson.getInt(part, "flags", 0)));
            }
            case "location_id" -> {
                long locationId = parseLong(text);
                int owner = APJson.getInt(part, "player", -1);
                String name = registries.apGameData().locationName(owner, locationId)
                        .orElseGet(() -> registries.apLocations().nameForId(locationId).orElse(text));
                yield Component.literal(name).withStyle(ChatFormatting.GREEN);
            }
            case "entrance_id" -> Component.literal(text).withStyle(ChatFormatting.BLUE);
            default -> {
                // Plain text: honour an explicit per-part colour, else the carried colour.
                ChatFormatting color = part.has("color") ? apColor(APJson.getString(part, "color", "")) : carried;
                MutableComponent component = Component.literal(text);
                yield color == null ? component : component.withStyle(color);
            }
        };
    }

    /** AP's item colours by classification flags, as its own clients draw item names. */
    public static ChatFormatting itemColor(int flags) {
        if ((flags & 0b100) != 0) {
            return ChatFormatting.RED;          // trap
        }
        if ((flags & 0b001) != 0) {
            return ChatFormatting.LIGHT_PURPLE; // progression
        }
        if ((flags & 0b010) != 0) {
            return ChatFormatting.BLUE;         // useful
        }
        return ChatFormatting.AQUA;             // filler
    }

    private static ChatFormatting apColor(String name) {
        return switch (name) {
            case "red", "salmon" -> ChatFormatting.RED;
            case "green" -> ChatFormatting.GREEN;
            case "yellow" -> ChatFormatting.YELLOW;
            case "blue", "slateblue" -> ChatFormatting.BLUE;
            case "magenta", "plum" -> ChatFormatting.LIGHT_PURPLE;
            case "cyan" -> ChatFormatting.AQUA;
            case "orange" -> ChatFormatting.GOLD;
            case "black" -> ChatFormatting.BLACK;
            case "white" -> ChatFormatting.WHITE;
            default -> null;
        };
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }
}
