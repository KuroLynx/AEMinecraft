package fr.euclesia.mcarchipelago.protocol.registry;

import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class APHandlerRegistry {
    private final Map<APCommand, List<APPacketHandler>> handlers = new EnumMap<>(APCommand.class);

    public APHandlerRegistry register(APCommand command, APPacketHandler handler) {
        handlers.computeIfAbsent(command, ignored -> new ArrayList<>()).add(handler);
        return this;
    }

    public boolean dispatch(ArchipelagoClient client, APReceivedPacket packet) {
        List<APPacketHandler> commandHandlers = handlers.get(packet.command());
        if (commandHandlers == null || commandHandlers.isEmpty()) {
            return false;
        }

        for (APPacketHandler handler : commandHandlers) {
            handler.handle(client, packet);
        }
        return true;
    }
}
