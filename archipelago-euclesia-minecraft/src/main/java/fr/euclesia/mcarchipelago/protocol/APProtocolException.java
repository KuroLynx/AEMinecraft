package fr.euclesia.mcarchipelago.protocol;

public class APProtocolException extends RuntimeException {
    public APProtocolException(String message) {
        super(message);
    }

    public APProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
