package fr.euclesia.mcarchipelago.protocol.registry;

import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;

@FunctionalInterface
public interface APPacketHandler {
    void handle(ArchipelagoClient client, APReceivedPacket packet);
}
