package io.github.social.nostr.relay.specs;

public final class ReplaceableMetadata {
    private final int kind;
    private final String pubkey;
    private final String data;

    public ReplaceableMetadata(final int kind, final String pubkey, final String data) {
        this.kind = kind;
        this.pubkey = pubkey;
        this.data = data;
    }

    public static ReplaceableMetadata of(final String aTagValue) {
        final String[] parameters = aTagValue.split(":");

        final int kind = Integer.parseInt(parameters[0]);
        final String pubkey = parameters[1];
        final String data = parameters.length > 2 ? parameters[2] : null;

        return new ReplaceableMetadata(kind, pubkey, data);
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
