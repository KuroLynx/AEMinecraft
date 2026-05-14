package fr.euclesia.mcarchipelago.protocol;

public enum APClientStatus {
    CLIENT_CONNECTED(5),
    CLIENT_READY(10),
    CLIENT_PLAYING(20),
    CLIENT_GOAL(30);

    private final int code;

    APClientStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
