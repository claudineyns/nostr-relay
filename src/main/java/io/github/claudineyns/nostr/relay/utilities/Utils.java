package io.github.claudineyns.nostr.relay.utilities;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.apache.commons.codec.binary.Hex;

public final class Utils {

	private Utils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

	@SuppressWarnings("unchecked")
	public static <T> T nullValue(final T... options) {
		for(final T q: options) {
			if(q != null) { return q; }
		}
		throw new IllegalArgumentException("At least one non-null arg must be provided");
	}

	public static String secWebsocketAccept(final String secWebSocketKey) {
		final String data = secWebSocketKey + Constants.WEBSOCKET_UUID;

		final MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-1");
		} catch(NoSuchAlgorithmException failure) {
			throw new IllegalStateException(failure);
		}

		md.update(data.getBytes(StandardCharsets.US_ASCII));

		return Base64.getEncoder().encodeToString(md.digest());
	}

    public static String sha256(final byte[] source) {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
            return Hex.encodeHexString(md.digest(source));
        } catch(NoSuchAlgorithmException e) {
            return "0".repeat(64);
        }
    }

	public static String secureHash() {
		final byte[] key = new byte[1024];
		new SecureRandom().nextBytes(key);

		try {
			return Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(key));
		} catch(NoSuchAlgorithmException e) {
			return "0".repeat(64);
		}
	}

}
