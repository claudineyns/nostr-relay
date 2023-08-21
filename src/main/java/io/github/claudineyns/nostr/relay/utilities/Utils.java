package io.github.claudineyns.nostr.relay.utilities;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class Utils {

	private Utils() { /***/ }

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

}
