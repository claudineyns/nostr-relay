package io.github.social.nostr.relay.specs;

public final class CoordinatedParameterEvent {
    private final int kind;
    private final String pubkey;
    private final String data;

    public CoordinatedParameterEvent(final int kind, final String pubkey, final String data) {
        this.kind = kind;
        this.pubkey = pubkey;
        this.data = data;
    }

    public static CoordinatedParameterEvent of(final String aTag) {
        final String[] parameters = aTag.split(":");
        final int kind = Integer.parseInt(parameters[0]);
        final String pubkey = parameters[1];
        final String data = parameters[2];

        return new CoordinatedParameterEvent(kind, pubkey, data);
    }

    public int getKind() {
        return kind;
    }

    public String getPubkey() {
        return pubkey;
    }

    public String getData() {
        return data;
    }

}
