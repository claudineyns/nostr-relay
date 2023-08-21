package io.github.claudineyns.nostr.relay.exceptions;

public class UtilityInstantiationException extends RuntimeException {

    public UtilityInstantiationException() {
        super("Utility class cannot be instantiated");
    }
    
}
