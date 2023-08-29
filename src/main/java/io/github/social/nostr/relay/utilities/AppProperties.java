package io.github.social.nostr.relay.utilities;

import static io.github.social.nostr.relay.utilities.Utils.nullValue;

import java.net.Inet4Address;
import java.net.UnknownHostException;

public final class AppProperties {
	private AppProperties() { /***/ }

	private static final String DEFAULT_ACME_CHALLENGE_PATH = "/var/www/.well-known/acme-challenge/";

	private static final String DEFAULT_PORT = "8080";
	private static final String DEFAULT_TLS_PORT = "8443";
	private static final String DEFAULT_TLS_ACTIVE = "false";
	private static final String DEFAULT_CLIENT_PING_SECOND = "60";
	private static final String DEFAULT_NIR_PATH = "/var/www/docs/nir.json";

	private static final String DEFAULT_REDIS_HOST = "localhost";
	private static final String DEFAULT_REDIS_PORT = "6379";
	private static final String DEFAULT_REDIS_PASS = "";

	private static final String DEFAULT_VALIDATION_HOST = "localhost";
	private static final String DEFAULT_VALIDATION_PORT = "8888";

	private static final String DEFAULT_REDIRECT_PAGE = "https://example.com";

	public static String getAcmeChallengePath() {
		return nullValue(
				System.getProperty(Constants.PROPERTY_ACME_CHALLENGE_PATH),
				System.getenv(Constants.ENV_ACME_CHALLENGE_PATH),
				DEFAULT_ACME_CHALLENGE_PATH
			);
	}

	public static int getPort() {
		final String port = nullValue(
				System.getProperty(Constants.PROPERTY_PORT),
				System.getenv(Constants.ENV_PORT),
				DEFAULT_PORT
			);
		return Integer.parseInt(port);
	}

	public static int getTlsPort() {
		final String port = nullValue(
				System.getProperty(Constants.PROPERTY_TLS_PORT),
				System.getenv(Constants.ENV_TLS_PORT),
				DEFAULT_TLS_PORT
			);
		return Integer.parseInt(port);
	}

	public static boolean isTls() {
		final String tlsRequired = nullValue(
				System.getProperty(Constants.PROPERTY_TLS_ACTIVE),
				System.getenv(Constants.ENV_TLS_ACTIVE),
				DEFAULT_TLS_ACTIVE
			);
		return Boolean.parseBoolean(tlsRequired);
	}

	public static String getRedisHost() {
		return nullValue(
				System.getProperty(Constants.PROPERTY_REDIS_HOST),
				System.getenv(Constants.ENV_REDIS_HOST),
				DEFAULT_REDIS_HOST
			);
	}

	public static int getRedisPort() {
		final String port = nullValue(
				System.getProperty(Constants.PROPERTY_REDIS_PORT),
				System.getenv(Constants.ENV_REDIS_PORT),
				DEFAULT_REDIS_PORT
			);
		return Integer.parseInt(port);
	}

	public static String getRedisSecret() {
		return nullValue(
				System.getProperty(Constants.PROPERTY_REDIS_PASS),
				System.getenv(Constants.ENV_REDIS_PASS),
				DEFAULT_REDIS_PASS
			);
	}

	public static int getClientPingSecond() {
		final String info = nullValue(
				System.getProperty(Constants.PROPERTY_CLIENT_PING_SECOND),
				System.getenv(Constants.ENV_CLIENT_PING_SECOND),
				DEFAULT_CLIENT_PING_SECOND
			);
		return Integer.parseInt(info);
	}

	public static String getRedirectPage() {
		return nullValue(
				System.getProperty(Constants.PROPERTY_REDIRECT_PAGE),
				System.getenv(Constants.ENV_REDIRECT_PAGE),
				DEFAULT_REDIRECT_PAGE
			);
	}

	public static String getNirFullpath() {
		return nullValue(
				System.getProperty(Constants.PROPERTY_NIR_FULLPATH),
				System.getenv(Constants.ENV_NIR_FULLPATH),
				DEFAULT_NIR_PATH
			);
	}

	private static final String DEFAULT_HOST_NAME;
	
	static {
		String hostname;
		try {
			hostname = Inet4Address.getLocalHost().getHostName();
		} catch(UnknownHostException e) {
			hostname = "localhost";
		}

		DEFAULT_HOST_NAME = hostname;
	}

	public static String getHostName() {
		return nullValue(
				System.getProperty(Constants.PROPERTY_HOSTNAME),
				System.getenv(Constants.ENV_HOSTNAME),
				DEFAULT_HOST_NAME
			);
	}

	public static String getEventValidationHost() {
		return nullValue(
				System.getProperty(Constants.PROPERTY_VALIDATION_HOST),
				System.getenv(Constants.ENV_VALIDATION_HOST),
				DEFAULT_VALIDATION_HOST
			);
	}

	public static int getEventValidationPort() {
		final String port = nullValue(
				System.getProperty(Constants.PROPERTY_VALIDATION_PORT),
				System.getenv(Constants.ENV_VALIDATION_PORT),
				DEFAULT_VALIDATION_PORT
			);
		return Integer.parseInt(port);
	}

}
