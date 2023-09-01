package io.github.social.nostr.relay.utilities;

public final class Constants {
	public static final String PROPERTY_ACME_CHALLENGE_PATH = 
		"acme.challenge-path";
	public static final String PROPERTY_HOST  = 
		"nostr.server.host";
	public static final String PROPERTY_PORT  = 
		"nostr.server.port";
	public static final String PROPERTY_TLS_PORT = 
		"nostr.server.tls.port";
	public static final String PROPERTY_TLS_ACTIVE = 
		"nostr.server.tls";
	public static final String PROPERTY_HOSTNAME = 
		"nostr.server.hostname";
	public static final String PROPERTY_CLIENT_PING_SECOND = 
		"nostr.websocket.client.ping.second";
	public static final String PROPERTY_NIR_FULLPATH = 
		"nostr.nir.fullpath";

	public static final String PROPERTY_REDIS_HOST = 
		"redis.host";
	public static final String PROPERTY_REDIS_PORT = 
		"redis.port";
	public static final String PROPERTY_REDIS_PASS = 
		"redis.pass";

	public static final String PROPERTY_VALIDATION_HOST = 
		"event.validation.host";
	public static final String PROPERTY_VALIDATION_PORT = 
		"event.validation.port";
	
	public static final String PROPERTY_REDIRECT_PAGE = 
		"server.redirect.page";

	public static final String ENV_ACME_CHALLENGE_PATH = 
		"ACME_CHALLENGE_PATH";

	public static final String ENV_HOST = 
		"NOSTR_SERVER_HOST";
	public static final String ENV_PORT = 
		"NOSTR_SERVER_PORT";
	public static final String ENV_TLS_PORT = 
		"NOSTR_SERVER_TLS_PORT";
	public static final String ENV_TLS_ACTIVE = 
		"NOSTR_SERVER_TLS";
	public static final String ENV_HOSTNAME = 
		"NOSTR_SERVER_HOSTNAME";
	public static final String ENV_CLIENT_PING_SECOND = 
		"NOSTR_WEBSOCKET_CLIENT_PING_SECOND";
	public static final String ENV_NIR_FULLPATH = 
		"NOSTR_NIR_FULLPATH";

	public static final String ENV_REDIS_HOST = 
		"REDIS_HOST";
	public static final String ENV_REDIS_PORT = 
		"REDIS_PORT";
	public static final String ENV_REDIS_PASS = 
		"REDIS_PASS";

	public static final String ENV_VALIDATION_HOST = 
		"EVENT_VALIDATION_HOST";
	public static final String ENV_VALIDATION_PORT = 
		"EVENT_VALIDATION_PORT";

	public static final String ENV_REDIRECT_PAGE = 
		"SERVER_REDIRECT_PAGE";

	public static final String WEBSOCKET_UUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	private Constants() { /***/ }

}
