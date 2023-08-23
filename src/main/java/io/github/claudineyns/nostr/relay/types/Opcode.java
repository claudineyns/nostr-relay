package io.github.claudineyns.nostr.relay.types;

public enum Opcode {

	OPCODE_CONTINUE   (0b0000, false),
	OPCODE_TEXT       (0b0001, false),
	OPCODE_BINARY     (0b0010, false),
    OPCODE_RESERVED_3 (0b0011, true),
    OPCODE_RESERVED_4 (0b0100, true),
    OPCODE_RESERVED_5 (0b0101, true),
    OPCODE_RESERVED_6 (0b0110, true),
    OPCODE_RESERVED_7 (0b0111, true),
	OPCODE_CLOSE      (0b1000, false),
	OPCODE_PING       (0b1001, false),
	OPCODE_PONG       (0b1010, false),
    OPCODE_RESERVED_B (0b1011, true),
    OPCODE_RESERVED_C (0b1100, true),
    OPCODE_RESERVED_D (0b1101, true),
    OPCODE_RESERVED_E (0b1110, true),
    OPCODE_RESERVED_F (0b1111, true),
    ;

    private final boolean reserved;
    private final byte _code;
    private Opcode(final int code, final boolean reserved) {
        this._code = (byte) code;
        this.reserved = reserved;
    }

    public byte code() {
        return _code;
    }

    public boolean isReserved() {
        return reserved;
    }

    public static Opcode byCode(final int code) {
        for(Opcode opcode: values()) {
            if(opcode._code == code ) return opcode;
        }

        return null;
    }
    
}
