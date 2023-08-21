package io.github.claudineyns.nostr.relay.utilities;

public final class Utils {

	private Utils() { /***/ }

	@SuppressWarnings("unchecked")
	public static <T> T nullValue(final T... options) {
		for(final T q: options) {
			if(q != null) { return q; }
		}
		throw new IllegalArgumentException("At least one non-null arg must be provided");
	}

}
