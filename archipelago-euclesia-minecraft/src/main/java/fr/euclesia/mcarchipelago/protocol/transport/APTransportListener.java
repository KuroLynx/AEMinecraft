package fr.euclesia.mcarchipelago.protocol.transport;

public interface APTransportListener {
    void onOpen();

    void onText(String message);

    void onError(Throwable throwable);

    void onClose(int statusCode, String reason);
}
