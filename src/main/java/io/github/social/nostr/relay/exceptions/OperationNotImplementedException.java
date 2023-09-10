package io.github.social.nostr.relay.exceptions;

public class OperationNotImplementedException extends RuntimeException {

    public OperationNotImplementedException() { /***/ }

    public OperationNotImplementedException(final String message) {
        super(message);
    }
    
}
