package fr.euclesia.mcarchipelago.archipelago;

import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;

public interface APEventListener {
    default void onConnected(ArchipelagoClient client, APReceivedPacket packet) {}

    default void onReceivedItems(ArchipelagoClient client, APReceivedPacket packet) {}

    default void onLocationInfo(ArchipelagoClient client, APReceivedPacket packet) {}

    default void onRoomUpdate(ArchipelagoClient client, APReceivedPacket packet) {}

    default void onPrintJson(ArchipelagoClient client, APReceivedPacket packet) {}

    default void onDataPackage(ArchipelagoClient client, APReceivedPacket packet) {}

    default void onBounced(ArchipelagoClient client, APReceivedPacket packet) {}

    default void onRetrieved(ArchipelagoClient client, APReceivedPacket packet) {}

    default void onSetReply(ArchipelagoClient client, APReceivedPacket packet) {}
}
