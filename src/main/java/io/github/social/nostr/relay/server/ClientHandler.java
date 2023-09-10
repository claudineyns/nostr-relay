package io.github.social.nostr.relay.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;

import io.github.social.nostr.relay.exceptions.CloseConnectionException;
import io.github.social.nostr.relay.misc.IncomingData;
import io.github.social.nostr.relay.types.HttpMethod;
import io.github.social.nostr.relay.types.HttpStatus;
import io.github.social.nostr.relay.types.Opcode;
import io.github.social.nostr.relay.utilities.AppProperties;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.websocket.BinaryMessage;
import io.github.social.nostr.relay.websocket.TextMessage;
import io.github.social.nostr.relay.websocket.Websocket;
import io.github.social.nostr.relay.websocket.WebsocketException;

import static io.github.social.nostr.relay.utilities.Utils.secWebsocketAccept;

@SuppressWarnings("unused")
public class ClientHandler implements Runnable {
	private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

	private final ScheduledExecutorService pingService = Executors.newScheduledThreadPool(1);

	private final ExecutorService websocketEventService = Executors.newCachedThreadPool();

    private final ExecutorService clientBroadcaster = Executors.newSingleThreadExecutor();
	
	private final String redirectPage = AppProperties.getRedirectPage();

	private final String nirFullpath = AppProperties.getNirFullpath();

	private final String host = AppProperties.getHost();

	private final WebsocketContext websocketContext = new WebsocketContext() {
		public byte broadcast(final String message) {
			clientBroadcaster.submit(() -> {
				if(interrupt) return;

				logger.infof("[WS] Server -> Client [%s]%n%s", remoteAddress, message);

				try {
					sendWebsocketDataClient(message);
				} catch (IOException e) { /***/ }
			});

			return 0;
		}

		public String getRemoteAddress()  {
			return remoteAddress;
		}

		public String getUserAgent() {
			return userAgent;
		}

		public synchronized byte requestClose() {
			interrupt = true;
			return 0;
		}

	};

	private InputStream in;
	private OutputStream out;

	private String userAgent;
	private String remoteAddress = "0.0.0.0";

	private final Socket client;
	private final Websocket websocketHandler;
	private final String serverHost;
	private final int serverPort;
	private final boolean isServerTls;

	public ClientHandler(
			final String serverHost,
			final int serverPort,
			final boolean isServerTls,
			final Socket c,
			final Websocket websocketHandler
	) {
		this.client = c;
		this.serverHost = serverHost;
		this.serverPort = serverPort;
		this.websocketHandler = websocketHandler;
		this.isServerTls = isServerTls;
	}

	private boolean interrupt = false;

	private boolean websocket = false;

	final int MAX_TIMEOUT_MILLIS = 10000;
	final int SOCKET_TIMEOUT_MILLIS = 50;

	@Override
	public void run() {
		try {
			this.process();
		} catch(IOException failure) { /****/ }
	}

	private void process() throws IOException {
		this.startStreams();
		this.handleStream();
		this.endStreams();
	}

	private void startStreams() throws IOException {
		final SocketAddress clientSocketAddress = client.getRemoteSocketAddress();
		Optional.ofNullable(clientSocketAddress).ifPresent(sk -> {
			this.remoteAddress = ((InetSocketAddress) sk).getAddress().getHostAddress();
		});

		boolean started = false;
		try {
			this.client.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
			this.in = client.getInputStream();
			this.out = client.getOutputStream();

			started = true;
		} catch(IOException failure) {
			logger.warning(
				"Client startup error.\n> Class: {}\n> Message: {}",
				failure.getClass().getCanonicalName(), failure.getMessage());

			throw failure;
		}

		if(!started) this.client.close();
	}

	private synchronized byte sendBytes(final byte[] rawData) throws IOException {
		this.out.write(rawData);

		return 0;
	}

	private synchronized byte flushStream() throws IOException {
		this.out.flush();

		return 0;
	}

	private void handleStream() throws IOException {
		while(true) {
			try {
				this.handle();

				if( ! interrupt ) continue;
			} catch (CloseConnectionException failure) {
				logger.warning("[Server] Connection will be closed.");
			} catch (IOException failure) {
				logger.warning(
					"I/O error.\n> Class: {}\n> Message: {}",
					failure.getClass().getCanonicalName(),
					failure.getMessage());

				this.notifyWebsocketFailure(failure);
			} catch (Exception failure) {
				logger.error(
					"Unpredictable error occured.\n> Class: {}\n> Message: {}",
					failure.getClass().getCanonicalName(),
					failure.getMessage());

				failure.printStackTrace();

				this.notifyWebsocketFailure(failure);
			}

			break;
		}

		this.notifyWebsocketClosure();

		this.pingService.shutdown();
	}

	private void endStreams() throws IOException {
		try {
			if( !this.client.isClosed() ) {
				client.close();
			}
		} catch (IOException e) { /***/ }

		logger.info("[Server] Client connection terminated.");
	}

	private static final String CRLF = "\r\n";
	private static final byte[] CRLF_RAW = CRLF.getBytes(StandardCharsets.US_ASCII);

	private HttpMethod requestMethod = null;
	private URL requestUrl = null;

	private ByteArrayOutputStream httpRawRequestHeaders = new ByteArrayOutputStream();
	private Map<String, List<String>> httpRequestHeaders = new LinkedHashMap<>();
	private ByteArrayOutputStream httpRequestBody = new ByteArrayOutputStream();
	private Map<String, List<String>> httpResponseHeaders = new LinkedHashMap<>();
	private ByteArrayOutputStream httpResponseBody = new ByteArrayOutputStream();

	private void cleanup() {
		this.requestMethod = null;
		this.requestUrl = null;
		this.httpRequestHeaders.clear();
		this.httpResponseHeaders.clear();
		this.httpRequestBody.reset();
		this.httpResponseBody.reset();
	}

	private byte handle() throws IOException {
		if(this.websocket) {
			return this.handleWebsocketClientPacket();
		} else {
			return this.handleHttpStream();
		}
	}

	private byte handleHttpStream() throws IOException {
		this.cleanup();
		
		this.startHandleHttpRequest();

		if (this.requestMethod != null) {
			this.continueHandleHttpRequest();
		}
		this.flushStream();

		return this.checkCloseConnection();
	}

	private byte handleWebsocketClientPacket() throws IOException {
		return this.consumeWebsocketClientPacket();
	}
	
	private byte checkCloseConnection() throws IOException {
		final List<String> connectionHeader = this.httpRequestHeaders
			.getOrDefault("connection", Collections.emptyList());

		final boolean closeAfterEnd = connectionHeader.stream().filter(q -> "close".equalsIgnoreCase(q)).count() > 0;

		if( connectionHeader.isEmpty() || closeAfterEnd ) this.interrupt = true;

		return 0;
	}

	private final byte[] incomingBytes = new byte[1024];

	private IncomingData incomingData = new IncomingData();

	private void startHandleHttpRequest() throws IOException {
		this.incomingData.reset();

		final IncomingData httpData = new IncomingData();

		int bytesRead = 0;

		final int[] lastOctets = new int[] {0, 0, 0, 0};

		long totalTimeout = 0;

		fetchData:
		while(true) {
			if(this.interrupt) return;

			bytesRead = 0;
			try {
				bytesRead = this.in.read(this.incomingBytes);
				if(bytesRead <= 0) continue;

				this.incomingData.write(this.incomingBytes, 0, bytesRead);
			} catch(SocketTimeoutException timeout) {
				totalTimeout += SOCKET_TIMEOUT_MILLIS;
				if(totalTimeout >= MAX_TIMEOUT_MILLIS) {
					logger.warning("[Server] client connect reached max allowed timeout.");
					throw new CloseConnectionException();
				}

				continue;
			} catch(IOException failure) {
				throw failure;
			}

			totalTimeout = 0;

			int pos = 0;
			do {
				byte octet = this.incomingData.next();
				httpData.write(octet);

				lastOctets[0] = lastOctets[1];
				lastOctets[1] = lastOctets[2];
				lastOctets[2] = lastOctets[3];
				lastOctets[3] = octet;

				if (	lastOctets[0] == '\r' 
					&&	lastOctets[1] == '\n'
					&&	lastOctets[2] == '\r' 
					&&	lastOctets[3] == '\n'
				) {
					final byte[] rawHeaders = httpData.consume();

					this.httpRawRequestHeaders.write(rawHeaders);
					this.parseRequestHeader();

					this.incomingData.shrink();

					break fetchData;
				}

			} while(pos < bytesRead);
		}

	}
	
	static final byte Q_BAD_REQUEST = -1;
	static final byte Q_NOT_FOUND = -2;
	static final byte Q_SERVER_ERROR = -3;

	static final byte Q_OK_OPTIONS = 1;
	static final byte Q_SWITCHING_PROTOCOL = 2;

	private byte continueHandleHttpRequest() throws IOException {
		final List<String> hostList = Optional
			.ofNullable(this.httpRequestHeaders.get("host"))
			.orElseGet(Collections::emptyList);

		if( hostList
			.stream()
			.filter(userHost -> userHost.equals(this.host) || userHost.startsWith(this.host+":"))
			.count() == 0
		) return this.sendBadRequest();

		byte returnCode = 0;
		switch (this.requestMethod) {
		case OPTIONS:
			returnCode = this.handleOptionsRequests();
			break;
		case GET:
			returnCode = this.handleGetRequests();
			break;
		default:
			return this.sendMethodNotAllowed();
		}

		switch(returnCode) {
			case Q_BAD_REQUEST:
				return this.sendBadRequest("Invalid Request Data");
			case Q_NOT_FOUND:
				return this.sendResourceNotFound();
			case Q_SWITCHING_PROTOCOL:
				return this.checkSwitchingProtocol();
			case Q_OK_OPTIONS:
				return 0;
			case 0:
				return sendResponse();
			default:
				return this.sendServerError(null);
		}

	}
	
	private byte handleOptionsRequests() {
		try {
			return doHandleOptionsRequests();
		} catch (IOException e) {
			return 1;
		}
	}
	
	private byte handleGetRequests() {
		try {
			return doHandleGetRequests();
		} catch (IOException e) {
			return 1;
		}
	}
	
	private final String getPath() {
		return this.requestUrl.getPath();
	}

	static final String ACME_CHALLENGE_BASE_PATH = "/.well-known/acme-challenge/";

	private byte doHandleOptionsRequests() throws IOException {
		final String path = getPath(); 

		switch (path) {
			case "/":
				return this.sendOkOptions();
			default:
				return Q_NOT_FOUND;
		}
	}

	private byte doHandleGetRequests() throws IOException {
		final String path = getPath(); 

		if (path.startsWith(ACME_CHALLENGE_BASE_PATH)) {
			final String relativePath = path.substring(ACME_CHALLENGE_BASE_PATH.length());
			return this.sendAcmeChallengeData(relativePath);
		}

		switch (path) {
			case "/live":
				return this.sendIndexPage();
			case "/favicon.ico":
				return this.sendFavicon();
			case "/":
				return Q_SWITCHING_PROTOCOL;
			default:
				return Q_NOT_FOUND;
		}
	}

	static final String CRLF_RE = "\\r\\n";
	private byte parseRequestHeader() throws IOException {
		final byte[] raw = this.httpRawRequestHeaders.toByteArray();

		final String data = new String(raw, StandardCharsets.US_ASCII)
			.replaceAll("\\r\\n[\\s\\t]+", "\u0000\u0000\u0000");
		final String[] entries = data.split(CRLF_RE);

		if (entries.length == 0) {
			return sendBadRequest("Invalid HTTP Request");
		}

		int startLine = 0;
		while(true) {
			if( ! entries[startLine].replaceAll("[\\s\\t]", "").isEmpty() ) {
				break;
			}
			++startLine;
		}

		final String methodLine = entries[startLine];
		final String[] methodContent = methodLine.split("\\s");
		if (methodContent.length != 3) {
			return sendBadRequest("Invalid HTTP Method Sintax");
		}

		logger.info("[Server] {}", methodLine);

		final String httpVersion = methodContent[2];
		if ( ! "HTTP/1.1".equalsIgnoreCase(httpVersion) ) {
			return sendVersionNotSupported();
		}

		final String methodLineLower = methodLine.toUpperCase();
		final String method = methodLineLower.substring(0, methodLineLower.indexOf(" "));

		final HttpMethod httpMethod = HttpMethod.from(method);
		if (httpMethod == null) {
			// return this.sendMethodNotImplemented();
			return this.sendForbidden();
		}

		if ( ! methodLineLower.toUpperCase().startsWith(httpMethod.name() + " ") ) {
			return this.sendBadRequest("Invalid HTTP Method Sintax.");
		}

		final String path = methodContent[1];
		if ( ! this.validateURI(path) ) {
			return this.sendBadRequest("HTTP URI must be a relative path.");
		}

		this.httpRequestHeaders.put(null, Collections.singletonList(methodLine));
		for (int i = startLine + 1; i < entries.length; ++i) {
			final String entry = entries[i];
			final String header = entry.substring(0, entry.indexOf(':')).toLowerCase();
			final String value = entry
				.substring(entry.indexOf(':') + 1)
				.trim()
				.replaceAll("[\u0000]{3}", "\r\n ");

			this.httpRequestHeaders.putIfAbsent(header, new LinkedList<>());
			this.httpRequestHeaders.get(header).add(value);
		}

		final StringBuilder originDebug = new StringBuilder("");
		originDebug.append("[Server] Client identification");

		originDebug.append(String.format("%n> Remote Address: %s", this.remoteAddress));

		Optional
			.ofNullable(this.httpRequestHeaders.get("user-agent"))
			.ifPresent(lista -> 
				lista
					.stream()
					.peek(q -> { userAgent = q; })
					.forEach(q -> originDebug.append(String.format("%n> User-Agent: %s", q)) )
			);

		Optional
			.ofNullable(this.httpRequestHeaders.get("origin"))
			.ifPresent(lista -> lista.stream().forEach(q -> originDebug.append(String.format("%n> Origin: %s", q)) ));

		logger.info("{}", originDebug);

		this.requestMethod = httpMethod;

		this.requestUrl = new URL(this.isServerTls?"https":"http", this.serverHost, this.serverPort, path);

		return 0;
	}

	private static final String gmt() {
		final DateTimeFormatter RFC_1123_DATE_TIME = DateTimeFormatter
			.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
		final ZonedDateTime dt = ZonedDateTime.now(ZoneId.of("GMT"));

		return dt.format(RFC_1123_DATE_TIME);
	}

	private byte sendStatusLine(HttpStatus status) throws IOException {
		final String statusLine = "HTTP/1.1 " + status.code() + " " + status.text();
		logger.info("[Server] StatusLine: {}", statusLine);

		return this.sendBytes((statusLine + CRLF).getBytes(StandardCharsets.US_ASCII));
	}

	private byte sendDateHeader() throws IOException {
		return this.sendBytes(String.format("Date: %s%s", gmt(), CRLF).getBytes(StandardCharsets.US_ASCII));
	}

	private byte sendETagHeader() throws IOException {
		return this.sendBytes(("ETag:\"" + UUID.randomUUID().toString() + "\"" + CRLF).getBytes(StandardCharsets.US_ASCII));
	}

	private byte sendContentHeader(final String type, final int length) throws IOException {
		this.sendBytes(("Content-Type: " + type + CRLF).getBytes(StandardCharsets.US_ASCII));
		this.sendBytes(("Content-Length: " + length + CRLF).getBytes(StandardCharsets.US_ASCII));

		return 0;
	}

	private byte sendPoweredByHeader() throws IOException {
		return this.sendBytes(("X-Powered-By: nostr-protocol" + CRLF).getBytes(StandardCharsets.US_ASCII));
	}

	private byte sendAccessControlAllowOriginHeader() throws IOException{
		this.sendBytes(("Access-Control-Allow-Origin: *" + CRLF).getBytes(StandardCharsets.US_ASCII));
		this.sendBytes(("Access-Control-Allow-Methods: GET" + CRLF).getBytes(StandardCharsets.US_ASCII));
		
		return 0;
	}

	private byte sendUpgradeWebsocketHeader() throws IOException {
		return this.sendBytes(("Upgrade: websocket" + CRLF).getBytes(StandardCharsets.US_ASCII));
	}

	private byte sendSecWebsocketVersionHeader() throws IOException {
		return this.sendBytes(("Sec-Websocket-Version: 13" + CRLF).getBytes(StandardCharsets.US_ASCII));
	}

	private byte sendSecWebsocketAcceptHeader(final String secWebsocketKey) throws IOException {
		final String secWebsocketAcceptValue = secWebsocketAccept(secWebsocketKey);
		return this.sendBytes(("Sec-Websocket-Accept: " + secWebsocketAcceptValue + CRLF).getBytes(StandardCharsets.US_ASCII));
	}

	private byte sendConnectionUpgraderHeader() throws IOException {
		return this.sendBytes(("Connection: Upgrade" + CRLF).getBytes(StandardCharsets.US_ASCII));
	}

	private byte sendConnectionCloseHeader() throws IOException {
		this.interrupt = true;
		return this.sendBytes(("Connection: close" + CRLF).getBytes(StandardCharsets.US_ASCII));
	}

	private byte sendOkOptions() throws IOException {
		this.sendStatusLine(HttpStatus.OK);
		this.sendBytes(("Allow: OPTIONS, GET" + CRLF).getBytes(StandardCharsets.US_ASCII));
		this.sendBytes(("Content-Length: 0" + CRLF).getBytes(StandardCharsets.US_ASCII));
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();

		return Q_OK_OPTIONS;
	}

	private byte sendAcmeChallengeData(final String relativePath) throws IOException {
		String contentType = null;
		if(relativePath.endsWith(".html") || relativePath.endsWith(".htm")) {
			contentType = "text/html; charset=UTF-8";
		} else if(relativePath.endsWith(".js")) {	
			contentType = "application/javascript; charset=UTF-8";
		} else if(relativePath.endsWith(".css")) {	
			contentType = "text/css; charset=UTF-8";
		} else if(relativePath.endsWith(".jpg") || relativePath.endsWith(".jpeg")) {
			contentType = "image/jpeg";
		} else if(relativePath.endsWith(".png") || relativePath.endsWith(".png")) {
			contentType = "image/png";
		} else if(relativePath.endsWith(".ico") || relativePath.endsWith(".ico")) {
			contentType = "image/ico";
		} else if(relativePath.endsWith(".gif") || relativePath.endsWith(".gif")) {
			contentType = "image/gif";
		} else {
			contentType = "application/octet-stream";
		}

		final File content = new File(AppProperties.getAcmeChallengePath(), relativePath);

		if( ! content.exists() ) return Q_NOT_FOUND;

		final ByteArrayOutputStream data = new ByteArrayOutputStream();
		try(final InputStream in = new FileInputStream(content)) {
			IOUtils.copy(in, data);
			data.flush();
		}

		this.httpResponseHeaders.put("Content-Type", Collections.singletonList(contentType));
		this.httpResponseHeaders.put("Content-Length", Collections.singletonList(Integer.toString(data.size())));
		this.httpResponseBody.write(data.toByteArray());

		return 0;
	}

	final ByteArrayOutputStream nirData = new ByteArrayOutputStream();

	private byte sendNirPage() throws IOException {
		synchronized(nirData) {
			if(nirData.size() == 0) {
				try(final InputStream in = new FileInputStream(nirFullpath) ) {
					IOUtils.copy(in, nirData);
				}
			}
		}

		final byte[] raw = nirData.toByteArray();

		this.httpResponseHeaders.put("Content-Type", Arrays.asList("application/nostr+json; charset=UTF-8"));
		this.httpResponseHeaders.put("Content-Length", Arrays.asList(Integer.toString(raw.length)));
		this.httpResponseBody.write(raw);

		return 0;
	}

	private byte sendIndexPage() throws IOException {
		final StringBuilder page = new StringBuilder("");
		final ByteArrayOutputStream html = new ByteArrayOutputStream();
		try(final InputStream in = getClass()
				.getResourceAsStream("/META-INF/resources/index.html")) {
			IOUtils.copy(in, html);

			final String content = new String(html.toByteArray(), StandardCharsets.UTF_8)
				.replaceAll("[\\r\\n]", "")
				.replaceAll("\\s+", " ")
				.replace("https://example.com", redirectPage);

			page.append(content);
		}

		final byte[] raw = page.toString().getBytes(StandardCharsets.UTF_8);

		this.httpResponseHeaders.put("Content-Type", Arrays.asList("text/html; charset=UTF-8"));
		this.httpResponseHeaders.put("Content-Length", Arrays.asList(Integer.toString(raw.length)));
		this.httpResponseBody.write(raw);

		return 0;
	}

	private byte sendFavicon() throws IOException {
		final byte[] raw = new byte[] {};

		this.httpResponseHeaders.put("Content-Type", Arrays.asList("image/icon"));
		this.httpResponseHeaders.put("Content-Length", Arrays.asList(Integer.toString(raw.length)));
		this.httpResponseBody.write(raw);

		return 0;
	}

	private byte sendCustomHeaders() throws IOException {
		for (final Map.Entry<String, List<String>> entry : this.httpResponseHeaders.entrySet()) {
			for (final String value : entry.getValue()) {
				sendBytes((entry.getKey() + ": " + value + CRLF).getBytes(StandardCharsets.US_ASCII));
			}
		}

		return 0;
	}

	private byte mountCustomBody() throws IOException {
		return this.sendBytes(this.httpResponseBody.toByteArray());
	}

	private byte mountHeadersTermination() throws IOException {
		return this.sendBytes(CRLF_RAW);
	}

	private byte sendBadRequest() throws IOException {
		return sendBadRequest(null);
	}

	private byte sendBadRequest(String cause) throws IOException {
		this.sendStatusLine(HttpStatus.BAD_REQUEST);
		this.sendDateHeader();

		if (cause == null) {
			this.sendConnectionCloseHeader();
			return this.mountHeadersTermination();
		}

		final byte[] raw = cause.getBytes(StandardCharsets.US_ASCII);

		this.sendContentHeader("text/plain", raw.length);
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();
		
		return this.sendBytes(raw);
	}

	private byte sendForbidden() throws IOException {
		this.sendStatusLine(HttpStatus.FORBIDDEN);
		this.sendDateHeader();
		this.sendConnectionCloseHeader();

		return this.mountHeadersTermination();
	}

	private byte sendResourceNotFound() throws IOException {
		final byte[] raw = "The requested resource could not be found".getBytes(StandardCharsets.US_ASCII);

		this.sendStatusLine(HttpStatus.NOT_FOUND);
		this.sendDateHeader();
		this.sendContentHeader("text/plain", raw.length);
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();

		return this.sendBytes(raw);
	}
	
	private byte sendVersionNotSupported() throws IOException {
		this.sendStatusLine(HttpStatus.HTTP_VERSION_NOT_SUPPORTED);
		this.sendDateHeader();
		this.sendConnectionCloseHeader();

		return this.mountHeadersTermination();
	}

	private byte sendMethodNotImplemented() throws IOException {
		this.sendStatusLine(HttpStatus.NOT_IMPLEMENTED);
		this.sendDateHeader();
		this.sendConnectionCloseHeader();

		return this.mountHeadersTermination();
	}

	private byte sendMethodNotAllowed() throws IOException {
		this.sendStatusLine(HttpStatus.METHOD_NOT_ALLOWED);
		this.sendDateHeader();
		this.sendConnectionCloseHeader();

		return this.mountHeadersTermination();
	}

	private byte sendServerError(String cause) throws IOException {
		this.sendStatusLine(HttpStatus.SERVER_ERROR);
		this.sendDateHeader();
		this.sendConnectionCloseHeader();

		if (cause == null) {
			return this.mountHeadersTermination();
		}

		final byte[] raw = cause.getBytes(StandardCharsets.US_ASCII);

		this.sendContentHeader("text/plain", raw.length);
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();

		return this.sendBytes(raw);
	}

	private boolean validateURI(String uri) {
		final Pattern uriPattern = Pattern.compile("^\\/\\S*$");
		final Matcher uriMatcher = uriPattern.matcher(uri);

		return uriMatcher.matches();
	}

	private byte sendResponse() throws IOException {
		this.sendStatusLine(HttpStatus.OK);

		this.sendDateHeader();
		this.sendAccessControlAllowOriginHeader();		
		this.sendETagHeader();
		this.sendCustomHeaders();
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();

		return this.mountCustomBody();
	}

	private byte checkSwitchingProtocol() throws IOException {
		final HttpStatus status = HttpStatus.SWITCHING_PROTOCOL.clone();

		final List<String> accept = Optional
			.ofNullable(this.httpRequestHeaders.get("accept"))
			.orElse(Collections.emptyList());

		final List<String> upgrade = Optional
			.ofNullable(this.httpRequestHeaders.get("upgrade"))
			.orElse(Collections.emptyList());

		final List<String> connection = Optional
			.ofNullable(this.httpRequestHeaders.get("connection"))
			.orElse(Collections.emptyList());

		final List<String> secWebsocketKey = Optional
			.ofNullable(this.httpRequestHeaders.get("sec-websocket-key"))
			.orElse(Collections.emptyList());

		final List<String> secWebsocketVersion = Optional
			.ofNullable(this.httpRequestHeaders.get("sec-websocket-version"))
			.orElse(Collections.emptyList());

		// final List<String> secWebsocketProtocol = Optional
		// 	.ofNullable(this.httpRequestHeaders.get("sec-websocket-protocol"))
		// 	.orElse(Collections.emptyList());

		if( connection.isEmpty() || ! "Upgrade".equalsIgnoreCase(connection.get(0)) ) {
			status.replace(HttpStatus.OK);
		} else if( upgrade.isEmpty() ) {
			status.replace(HttpStatus.UPGRADE_REQUIRED);
		} else {
			if( ! "websocket".equalsIgnoreCase(upgrade.get(0)) ) {
				status.replace(HttpStatus.BAD_REQUEST);
			} else if(secWebsocketVersion.isEmpty() || ! secWebsocketVersion.contains("13") ) {
				status.replace(HttpStatus.BAD_REQUEST);
			} else if(secWebsocketKey.isEmpty() ) {
				status.replace(HttpStatus.BAD_REQUEST);
			}
		}

		this.sendStatusLine(status);
		this.sendDateHeader();
		this.sendPoweredByHeader();
		this.sendAccessControlAllowOriginHeader();

		if( status.code() == HttpStatus.UPGRADE_REQUIRED.code() ) {
			this.sendUpgradeWebsocketHeader();
		} else if( status.code() == HttpStatus.BAD_REQUEST.code() ) {
			if( ! secWebsocketVersion.isEmpty() && ! secWebsocketVersion.contains("13") ) {
				this.sendSecWebsocketVersionHeader();
			}
		}

		if( status.code() == HttpStatus.SWITCHING_PROTOCOL.code() ) {
			this.sendConnectionUpgraderHeader();
			this.sendUpgradeWebsocketHeader();
			this.sendSecWebsocketAcceptHeader(secWebsocketKey.get(0));

			this.websocket = true;
		} else if( status.code() == HttpStatus.OK.code() ) {

			if( accept.contains("application/nostr+json") ) {
				this.sendNirPage();
			} else {
				this.sendIndexPage();
			}

			this.sendCustomHeaders();
			this.sendConnectionCloseHeader();
		} else {
			this.sendConnectionCloseHeader();
		}

		this.mountHeadersTermination();

		if( this.websocket ) {
			this.scheduleWebsocketPingClient();
			this.notifyWebsocketOpening();
		}

		return this.mountCustomBody();
	}

	private void notifyWebsocketOpening() {
		this.websocketContext.connect();
		this.websocketEventService.submit(() -> this.websocketHandler.onOpen(this.websocketContext));
	}

	private void notifyWebsocketFailure(final Exception failure) {
		if(this.websocket) {
			this.websocketContext.disconnect();
			final WebsocketException wsException = new WebsocketException(failure).setContext(this.websocketContext);
			this.websocketEventService.submit(() -> this.websocketHandler.onError(wsException));
		}
	}

	private void notifyWebsocketClosure() {
		if(this.websocket) {
			this.websocketContext.disconnect();
			this.websocketEventService.submit(() -> this.websocketHandler.onClose(this.websocketContext));
		}
	}

	private void notifyWebsocketTextMessage(final byte[] data) {
		this.websocketEventService.submit(() -> this.websocketHandler.onMessage(this.websocketContext, new TextMessage(data)));
	}

	private void notifyWebsocketBinaryMessage(final byte[] data) {
		this.websocketEventService.submit(() -> this.websocketHandler.onMessage(this.websocketContext, new BinaryMessage(data)));
	}

	static final int CLIENT_LIVENESS_MILLIS = AppProperties.getClientPingSecond() * 1000;
	private void scheduleWebsocketPingClient() {
		final Thread pingPong = new Thread(() -> {
			try {
				this.websocketPingClientEventFired();
			} catch (IOException e) { /***/ }
		});
		pingPong.setDaemon(true);

		this.pingService.scheduleAtFixedRate(
			pingPong,
			CLIENT_LIVENESS_MILLIS,
			CLIENT_LIVENESS_MILLIS,
			TimeUnit.MILLISECONDS
		);
		logger.info("[WS] PING client liveness set to {}ms.", CLIENT_LIVENESS_MILLIS);
	}

	static final long MAX_PACKET_RECEIVED_TIMEOUT_MILLIS = 300000; // 5 minutos

	private final AtomicInteger pingCounter = new AtomicInteger();
	private byte websocketPingClientEventFired() throws IOException {
		if(Thread.currentThread().isInterrupted()) return 0;

		final long timeDiff = System.currentTimeMillis() - this.lastPacketReceivedTime;

		if ( timeDiff < CLIENT_LIVENESS_MILLIS ) return 0;

		if( timeDiff > MAX_PACKET_RECEIVED_TIMEOUT_MILLIS ) {
			if(this.interrupt) return 0;

			return this.requestCloseDueInactivity();
		}

		final int c = pingCounter.getAndIncrement();
		if( c == 0 ) {
			logger.infof("[WS] Server -> Client [%s]: Hey, are you on?", this.remoteAddress);
		} else {
			logger.infof("[WS] Server -> Client [%s]: It seems you are off. Are you on?", this.remoteAddress);
		}

		return this.sendWebsocketPingClient();
	}

	private byte requestCloseDueInactivity() throws IOException {
		this.interrupt = true;

		final ByteBuffer closeCode = ByteBuffer.allocate(2);
		closeCode.putShort((short)1000);

		final ByteArrayOutputStream message = new ByteArrayOutputStream();
		message.write(closeCode.array());
		message.write("Closed due to inactivity".getBytes(StandardCharsets.UTF_8));

		logger.infof("[WS] Server -> Client [%s]: Send CLOSE frame due to client inactivity.", this.remoteAddress);
		return this.sendWebsocketCloseFrame(message.toByteArray());
	}

	private byte fetchWebsocketData() throws IOException {
		int bytesRead = 0;

		this.incomingData.shrink();

		while(true) {
			if(this.interrupt) return 0;

			try {
				bytesRead = this.in.read(this.incomingBytes);
				if( bytesRead <= 0 ) continue;

				this.incomingData.write(this.incomingBytes, 0, bytesRead);
			} catch(SocketTimeoutException timeout) {
				continue;
			} catch(IOException failure) {
				throw failure;
			}

			break;
		}

		return 0;
	}

	static final int CHECK_FIN = 0;
	static final int PAYLOAD_LENGTH = 1;
	static final int MASKING = 2;
	static final int PAYLOAD_CONSUMPTION = 3;

	static final int FIN_ON  = 0b10000000;

	static final byte OPCODE_BITSPACE_FLAG = 0b00001111;

	static final int OPCODE_CONTROL_FLAG = 0b1000;

	static final int UNMASK = 0b01111111;

	private long lastPacketReceivedTime = System.currentTimeMillis();

	private byte consumeWebsocketClientPacket() throws IOException {
		final ByteArrayOutputStream message = new ByteArrayOutputStream();
		final ByteArrayOutputStream controlMessage = new ByteArrayOutputStream();

		int stage = CHECK_FIN;
		
		boolean isFinal = false;

		int opcode = -1;
		int current_opcode = -1;
		Opcode c_opcode = null;

		StringBuilder byteLength = new StringBuilder("");
		int payloadLength = 0;
		int nextBytes = 0;

		final int[] decoder = new int[4];
		int decoderIndex = 0;

		fetchRawData:
		while(true) {
			this.fetchWebsocketData();
			if(this.interrupt) return 0;

			do {
				byte octet = this.incomingData.next();

				if( stage == CHECK_FIN ) {
					isFinal = (octet & FIN_ON) == FIN_ON;
					current_opcode = octet & OPCODE_BITSPACE_FLAG;
					c_opcode = Opcode.byCode(current_opcode);

					if(c_opcode.isReserved()) {
						logger.warning("[WS] Parsing error. Aborting connection at all");
						this.interrupt = true;
						return 0;
					}

					if(opcode == -1) {
						opcode = current_opcode;
					}
					stage = PAYLOAD_LENGTH;
					continue;
				}

				if( stage == PAYLOAD_LENGTH ) {
					if( nextBytes == 0 ) {
						int byteCheck = octet & UNMASK;

						if( byteCheck <= 125 ) {
							payloadLength = byteCheck;

							stage = MASKING;
							continue;
						} else if( byteCheck == 126 ) {
							nextBytes = 2;
							continue;
						} else if( byteCheck == 127 ) {
							nextBytes = 8;
							continue;
						}
					} else {
						final String rawBinaryOctet = "0000000"+Integer.toBinaryString(octet);
						final String binaryOctet = rawBinaryOctet.substring(rawBinaryOctet.length() - 8);
						byteLength.append(binaryOctet);

						if( --nextBytes == 0 ) {						
							payloadLength = Integer.parseInt(byteLength.toString(), 2);
							byteLength = new StringBuilder("");

							stage = MASKING;
							continue;
						}
					}
				}

				if( stage == MASKING ) {
					decoder[decoderIndex++] = octet;
					if(decoderIndex == decoder.length) {
						decoderIndex = 0;

						if(payloadLength == 0) {
							if( isFinal ) break fetchRawData;

							stage = CHECK_FIN;
							continue;
						}
						stage = PAYLOAD_CONSUMPTION;
						continue;
					}
				}

				if( stage == PAYLOAD_CONSUMPTION ) {
					/*
					* XOR bitwise operation
					*/
					int decoded = (octet ^ decoder[decoderIndex++ % decoder.length]);

					if( c_opcode.isControl() ) {
						controlMessage.write(decoded);
					} else {
						message.write(decoded);
					}

					if( --payloadLength == 0 ) {
						if( isFinal ) break fetchRawData;

						payloadLength = 0;
						stage = CHECK_FIN;
						continue;
					}
				}

			} while(this.incomingData.remaining() > 0);

		}

		this.lastPacketReceivedTime = System.currentTimeMillis();
		pingCounter.set(0);

		if( opcode == Opcode.OPCODE_TEXT.code() ) {
			this.notifyWebsocketTextMessage(message.toByteArray());
		}

		if( opcode == Opcode.OPCODE_BINARY.code() ) {
			this.notifyWebsocketBinaryMessage(message.toByteArray());
		}

		if( opcode == Opcode.OPCODE_PONG.code() ) {
			logger.infof("[WS] Client [%s] -> Server: I'm on!", this.remoteAddress);
			return 0;
		}

		if( opcode == Opcode.OPCODE_PING.code() ) {
			logger.info("[WS] Client [%s] -> Server: Hey, are you on?", this.remoteAddress);
			return this.sendWebsocketPongClient(message.toByteArray());
		}

		if( opcode == Opcode.OPCODE_CLOSE.code() ) {
			final short closeCode = parseCode(controlMessage.toByteArray());
			logger.info("[WS] Client sent CLOSE frame with code {}.", closeCode);

			if( this.interrupt ) return 0;

			logger.info("[WS] Send client back a CLOSE confirmation frame.");
			this.sendWebsocketCloseFrame(controlMessage.toByteArray());

			this.interrupt = true;
		}

		return 0;
	}

	private short parseCode(final byte[] raw) {
		final ByteBuffer code = ByteBuffer.wrap(raw);

		final short closeCode = raw.length >= 2 ? code.getShort() : 0;

		if( raw.length > 2 ) {
			final byte[] message = Arrays.copyOfRange(raw, 2, raw.length);
			logger.info(
				"[Server] Client send CLOSE frame with code {} and message {}",
				closeCode, new String(message, StandardCharsets.UTF_8));
		}

		return closeCode;
	}

	private byte sendWebsocketDataClient(final String message) throws IOException {
		final byte[] rawData = message.getBytes(StandardCharsets.UTF_8);

		return this.sendWebsocketClientRawData(Opcode.OPCODE_TEXT.code(), rawData);
	}

	// https://www.rfc-editor.org/rfc/rfc6455#section-7.4
	private byte sendWebsocketCloseFrame(short code) throws IOException {
		final ByteBuffer bb = ByteBuffer.allocate(2);
		bb.putShort(code);

		return this.sendWebsocketCloseFrame(bb.array());
	}

	private byte sendWebsocketCloseFrame(byte[] raw) throws IOException {
		return this.sendWebsocketClientRawData(Opcode.OPCODE_CLOSE.code(), raw);
	}

	private byte sendWebsocketPingClient() throws IOException {
		final byte[] message = "Liveness".getBytes(StandardCharsets.UTF_8);

		return this.sendWebsocketClientRawData(Opcode.OPCODE_PING.code(), message);
	}

	private byte sendWebsocketPongClient(final byte[] rawData) throws IOException {
		logger.info("[WS] Server -> Client [%s]: I'm on!", this.remoteAddress);
		return this.sendWebsocketClientRawData(Opcode.OPCODE_PONG.code(), rawData);
	}

	// TODO: Em revisão
	private byte sendWebsocketClientRawData(final byte opcode, final byte[] rawData) throws IOException {
		String binaryOpcode = "000"+Integer.toBinaryString(opcode);
		binaryOpcode = binaryOpcode.substring(binaryOpcode.length() - 4);

		// 1   : FIN (final frame): ON
		// 000 : extension (Not supported at this moment)
		// ????: opcode
		final String binaryFrame = "1000"+binaryOpcode;
		final int frame = Integer.parseInt(binaryFrame, 2);

		final ByteArrayOutputStream cache = new ByteArrayOutputStream();
		cache.write(frame);

		if(rawData.length <= 125) {
			cache.write(rawData.length);
		} else {
			final ByteBuffer dataLength = ByteBuffer.allocate(2);
			dataLength.putShort((short) rawData.length);

			// TODO: Send only package limited to '16 bits' length of data, indicated by a byte of value '126'.
			// TODO: In the future, send '64 bits' length of data, indicated by a byte of value '127'.
			cache.write(126);

			cache.write(dataLength.array());
		}

		cache.write(rawData);
		cache.flush();

		this.sendBytes(cache.toByteArray());
		
		return this.flushStream();
	}

}
