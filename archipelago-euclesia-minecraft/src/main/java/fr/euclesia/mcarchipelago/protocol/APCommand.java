package fr.euclesia.mcarchipelago.protocol;

/**
 * Archipelago Protocol commands.
 *
 * <p>Outbound: commands sent from the mod to the AP server.</p>
 * <p>Inbound:  commands received from the AP server by the mod.</p>
 *
 * @see <a href="https://github.com/ArchipelagoMW/Archipelago/blob/main/docs/network%20protocol.md">AP Network Protocol</a>
 */
public enum APCommand {

    // -----------------------------------------------------------------------
    // Outbound (Mod → Server)
    // -----------------------------------------------------------------------

    /** Initial connection request. Must be the first packet sent. */
    CONNECT("Connect"),

    /** Update tags or items_handling after connection. */
    CONNECT_UPDATE("ConnectUpdate"),

    /** Report one or more completed location checks. */
    LOCATION_CHECKS("LocationChecks"),

    /** Scout locations without checking them (optionally creates hints). */
    LOCATION_SCOUTS("LocationScouts"),

    /** Update the player's client status (ready, playing, goal reached...). */
    STATUS_UPDATE("StatusUpdate"),

    /** Send a chat message visible to all players. */
    SAY("Say"),

    /** Request the data package (item/location name↔id mappings). */
    GET_DATA_PACKAGE("GetDataPackage"),

    /** Broadcast a message to players with matching tags (used for DeathLink). */
    BOUNCE("Bounce"),

    /** Read a value from the AP DataStorage. */
    GET("Get"),

    /** Write a value to the AP DataStorage. */
    SET("Set"),

    /** Subscribe to changes on a DataStorage key. */
    SET_NOTIFY("SetNotify"),

    // -----------------------------------------------------------------------
    // Inbound (Server → Mod)
    // -----------------------------------------------------------------------

    /** Received on connect, before sending Connect. Contains room info. */
    ROOM_INFO("RoomInfo"),

    /** Received after a successful Connect. Contains slot_data. */
    CONNECTED("Connected"),

    /** Received when connection is refused. Contains error codes. */
    CONNECTION_REFUSED("ConnectionRefused"),

    /** Received when the player is granted one or more items. */
    RECEIVED_ITEMS("ReceivedItems"),

    /** Response to LocationScouts. Contains item info for scouted locations. */
    LOCATION_INFO("LocationInfo"),

    /** Received when the room state changes (new checks, players joining...). */
    ROOM_UPDATE("RoomUpdate"),

    /** Chat/event message to display in the AP console. */
    PRINT_JSON("PrintJSON"),

    /** Response to GetDataPackage. Contains all item/location name↔id mappings. */
    DATA_PACKAGE("DataPackage"),

    /** Received when a Bounce matches the client's tags (e.g. DeathLink). */
    BOUNCED("Bounced"),

    /** Received when a sent packet was malformed. */
    INVALID_PACKET("InvalidPacket"),

    /** Response to a Get request. Contains the requested DataStorage values. */
    RETRIEVED("Retrieved"),

    /** Response to a Set request when want_reply is true. */
    SET_REPLY("SetReply");

    private final String commandName;

    APCommand(String commandName) {
        this.commandName = commandName;
    }

    public String getCommandName() {
        return commandName;
    }

    public static APCommand fromString(String cmd) {
        for (APCommand command : values()) {
            if (command.commandName.equals(cmd)) {
                return command;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return commandName;
    }
}