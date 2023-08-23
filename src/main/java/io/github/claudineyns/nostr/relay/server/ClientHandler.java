package io.github.claudineyns.nostr.relay.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.claudineyns.nostr.relay.exceptions.CloseConnectionException;
import io.github.claudineyns.nostr.relay.types.HttpMethod;
import io.github.claudineyns.nostr.relay.types.HttpStatus;
import io.github.claudineyns.nostr.relay.types.Opcode;
import io.github.claudineyns.nostr.relay.utilities.AppProperties;
import io.github.claudineyns.nostr.relay.utilities.LogService;
import io.github.claudineyns.nostr.relay.websocket.BinaryMessage;
import io.github.claudineyns.nostr.relay.websocket.TextMessage;
import io.github.claudineyns.nostr.relay.websocket.WebsocketException;

import static io.github.claudineyns.nostr.relay.utilities.Utils.secWebsocketAccept;

@SuppressWarnings("unused")
public class ClientHandler implements Runnable {
	private final LogService logger = LogService.getInstance(getClass().getCanonicalName());
	private final ScheduledExecutorService pingService = Executors.newScheduledThreadPool(5);
	private final ExecutorService websocketEventService = Executors.newCachedThreadPool();
	private final WebsocketContext websocketContext = new WebsocketContext() {
		public synchronized void broadcast() {
		}
	};

	private final Socket client;
	private InputStream in;
	private OutputStream out;

	private final WebsocketHandler websocketHandler;

	public ClientHandler(final Socket c, final WebsocketHandler websocketHandler) {
		this.client = c;
		this.websocketHandler = websocketHandler;
	}

	private boolean interrupt = false;

	private boolean websocket = false;

	final int socket_timeout = 10000;

	@Override
	public void run() {
		try {
			this.makeItReady();
		} catch(IOException failure) { /****/ }
	}

	private void makeItReady() throws IOException {
		this.startStreams();
		this.handleStream();
		this.endStreams();
	}

	private void startStreams() throws IOException {
		try {
			// this.client.setSoTimeout(socket_timeout); // <-- websocket: do not apply
			this.in = client.getInputStream();
			this.out = client.getOutputStream();
		} catch(IOException failure) {
			logger.warning("Request startup error: {}: {}", failure.getClass().getCanonicalName(), failure.getMessage());
			throw failure;
		}
	}

	private void handleStream() throws IOException {
		while(true) {
			try {
				this.handle();
				if( ! interrupt ) continue;

			} catch (SocketTimeoutException failure) {
				logger.warning(failure.getMessage());

				this.notifyWebsocketFailure(failure);
			} catch (CloseConnectionException failure) {
				logger.warning("Connection closed");

				this.notifyWebsocketClosure();
			} catch (IOException failure) {
				logger.warning(
					"I/O Request handling error: {}: {}",
					failure.getClass().getCanonicalName(),
					failure.getMessage());

				this.notifyWebsocketFailure(failure);
			} catch (Exception failure) {
				logger.error(
					"Unpredictable error occured: {}: {}",
					failure.getClass().getCanonicalName(),
					failure.getMessage());

				this.notifyWebsocketFailure(failure);
			}

			break;
		}

		this.notifyWebsocketClosure();

		this.pingService.shutdown();
	}

	private void endStreams() throws IOException {
		try {
			client.close();
		} catch (IOException e) {
			logger.warning("{}: {}", e.getClass().getCanonicalName(), e.getMessage());
		}

		logger.info("Client connection terminated.");
	}

	private static final String CRLF = "\r\n";
	private static final byte[] CRLF_RAW = CRLF.getBytes(StandardCharsets.US_ASCII);

	private boolean isUrlAsterisk = false;

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
		out.flush();

		return this.checkCloseConnection();
	}

	private byte handleWebsocketClientPacket() throws IOException {
		return this.consumeWebsocketClientPacket();
	}
	
	private byte checkCloseConnection() throws IOException {
		final List<String> connectionHeader = this.httpRequestHeaders.get("connection");
		if ( connectionHeader != null && ! connectionHeader.isEmpty() && "close".equalsIgnoreCase(connectionHeader.get(0)) ) {
			logger.warning("Client has requested server to close connection");
			throw new CloseConnectionException();
		}

		return 0;
	}

	private void startHandleHttpRequest() throws IOException {
		final ByteArrayOutputStream cache = new ByteArrayOutputStream();
		
		final int[] octets = new int[] {0, 0, 0, 0};

		int octet = -1;
		while ((octet = in.read()) != -1) {
			cache.write(octet);

			octets[0] = octets[1];
			octets[1] = octets[2];
			octets[2] = octets[3];
			octets[3] = octet;

			if (	octets[0] == '\r' 
				&&	octets[1] == '\n'
				&&	octets[2] == '\r' 
				&&	octets[3] == '\n'
			) {

				final byte[] rawHeaders = cache.toByteArray();
				this.httpRawRequestHeaders.write(rawHeaders);
				this.analyseRequestHeader(Arrays.copyOfRange(rawHeaders, 0, rawHeaders.length - 4));
				break;

			}

		}
	}
	
	static final byte Q_BAD_REQUEST = -1;
	static final byte Q_NOT_FOUND = -2;
	static final byte Q_SERVER_ERROR = -3;
	static final byte Q_SWITCHING_PROTOCOL = 1;

	private byte continueHandleHttpRequest() throws IOException {
		byte returnCode = 0;
		switch (this.requestMethod) {
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
			case 0:
				return sendResponse();
			default:
				return this.sendServerError(null);
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
		return this.isUrlAsterisk ? "*" : this.requestUrl.getPath();
	}

	private byte doHandleGetRequests() throws IOException {
		final String path = getPath(); 

		switch (path) {
			case "/live":
				return this.sendLivePage();
			case "/favicon.ico":
				return this.sendFavicon();
			case "/":
				return Q_SWITCHING_PROTOCOL;
			default:
				return Q_NOT_FOUND;
		}
	}

	private byte analyseRequestHeader(byte[] raw) throws IOException {
		final String CRLF_RE = "\\r\\n";

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

		logger.info(methodLine);

		final String httpVersion = methodContent[2];
		if ( ! "HTTP/1.1".equalsIgnoreCase(httpVersion) ) {
			return sendVersionNotSupported();
		}

		final String methodLineLower = methodLine.toUpperCase();
		final String method = methodLineLower.substring(0, methodLineLower.indexOf(" "));

		final HttpMethod httpMethod = HttpMethod.from(method);
		if (httpMethod == null) {
			return sendMethodNotImplemented();
		}

		if (!methodLineLower.toUpperCase().startsWith(httpMethod.name() + " ")) {
			return sendBadRequest("Invalid HTTP Method Sintax");
		}

		final String uri = methodContent[1];
		if (!validateURI(uri)) {
			return sendBadRequest("Invalid HTTP URI");
		}

		httpRequestHeaders.put(null, Collections.singletonList(methodLine));
		for (int i = startLine + 1; i < entries.length; ++i) {
			final String entry = entries[i];
			final String header = entry.substring(0, entry.indexOf(':')).toLowerCase();
			final String value = entry
				.substring(entry.indexOf(':') + 1)
				.trim()
				.replaceAll("[\u0000]{3}", "\r\n ");
			httpRequestHeaders.putIfAbsent(header, new LinkedList<>());
			httpRequestHeaders.get(header).add(value);
		}

		if( this.httpRequestHeaders.containsKey("user-agent")) {
			logger.info(this.httpRequestHeaders.get("user-agent").get(0));
		}

		this.requestMethod = httpMethod;
		
		this.isUrlAsterisk = uri.equals("*");

		if ( ! isUrlAsterisk) {
			this.requestUrl = new URL("http://localhost" + uri);
		}

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
		logger.info(statusLine);
		out.write((statusLine + CRLF).getBytes(StandardCharsets.US_ASCII));

		return 0;
	}

	private byte sendDateHeader() throws IOException {
		out.write(String.format("Date: %s%s", gmt(), CRLF).getBytes(StandardCharsets.US_ASCII));

		return 0;
	}

	private byte sendETagHeader() throws IOException {
		out.write(("ETag:\"" + UUID.randomUUID().toString() + "\"" + CRLF).getBytes(StandardCharsets.US_ASCII));

		return 0;
	}

	private byte sendContentHeader(final String type, final int length) throws IOException {
		out.write(("Content-Type: " + type + CRLF).getBytes(StandardCharsets.US_ASCII));
		out.write(("Content-Length: " + length + CRLF).getBytes(StandardCharsets.US_ASCII));

		return 0;
	}

	private byte sendAccessControlAllowOriginHeader() throws IOException{
		out.write(("Access-Control-Allow-Origin: *" + CRLF).getBytes(StandardCharsets.US_ASCII));
		
		return 0;
	}

	private byte sendUpgradeWebsocketHeader() throws IOException {
		out.write(("Upgrade: websocket" + CRLF).getBytes(StandardCharsets.US_ASCII));

		return 0;
	}

	private byte sendSecWebsocketVersionHeader() throws IOException {
		out.write(("Sec-Websocket-Version: 13" + CRLF).getBytes(StandardCharsets.US_ASCII));

		return 0;
	}

	private byte sendSecWebsocketAcceptHeader(final String secWebsocketKey) throws IOException {
		final String secWebsocketAcceptValue = secWebsocketAccept(secWebsocketKey);
		out.write(("Sec-Websocket-Accept: " + secWebsocketAcceptValue + CRLF).getBytes(StandardCharsets.US_ASCII));

		return 0;
	}

	private byte sendConnectionUpgraderHeader() throws IOException {
		out.write(("Connection: Upgrade" + CRLF).getBytes(StandardCharsets.US_ASCII));

		return 0;
	}

	private byte sendConnectionCloseHeader() throws IOException {
		out.write(("Connection: close" + CRLF).getBytes(StandardCharsets.US_ASCII));
		this.interrupt = true;

		return 0;
	}

	private byte sendLivePage() throws IOException {
		final String html = 
				  "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
				+ "<title>Nostr Relay</title></head><body>"
				+ "<h3 style=\"font-family: Courier, monospace; text-align: center; font-weight: normal;\">Nostr Relay is live</h3>"
				+ "</body></html>\n";
		final byte[] raw = html.getBytes(StandardCharsets.UTF_8);

		this.httpResponseHeaders.put("Content-Type", Collections.singletonList("text/html; charset=UTF-8"));
		this.httpResponseHeaders.put("Content-Length", Collections.singletonList(Integer.toString(raw.length)));
		this.httpResponseBody.write(raw);

		return 0;
	}

	private byte sendFavicon() throws IOException {
		final byte[] raw = new byte[] {};

		this.httpResponseHeaders.put("Content-Type", Collections.singletonList("image/icon"));
		this.httpResponseHeaders.put("Content-Length", Collections.singletonList(Integer.toString(raw.length)));
		this.httpResponseBody.write(raw);

		return 0;
	}

	private byte sendCustomHeaders() throws IOException {
		for (final Map.Entry<String, List<String>> entry : this.httpResponseHeaders.entrySet()) {
			for (final String value : entry.getValue()) {
				out.write((entry.getKey() + ": " + value + CRLF).getBytes(StandardCharsets.US_ASCII));
			}
		}

		return 0;
	}

	private byte mountCustomBody() throws IOException {
		out.write(this.httpResponseBody.toByteArray());

		return 0;
	}

	private byte mountHeadersTermination() throws IOException {
		out.write(CRLF_RAW);

		return 0;
	}

	private byte sendBadRequest(String cause) throws IOException {
		this.sendStatusLine(HttpStatus.BAD_REQUEST);
		this.sendDateHeader();

		if (cause == null) {
			return this.mountHeadersTermination();
		}

		final byte[] raw = cause.getBytes(StandardCharsets.US_ASCII);

		this.sendContentHeader("text/plain", raw.length);
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();
		out.write(raw);

		return 0;
	}

	private byte sendResourceNotFound() throws IOException {
		final byte[] raw = "The requested resource could not be found".getBytes(StandardCharsets.US_ASCII);

		this.sendStatusLine(HttpStatus.NOT_FOUND);
		this.sendDateHeader();
		this.sendContentHeader("text/plain", raw.length);
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();

		out.write(raw);

		return 0;
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

		out.write(raw);

		return 0;
	}

	private boolean validateURI(String uri) {
		final Pattern uriPattern = Pattern.compile("^\\/\\S*$|^\\*$");
		final Matcher uriMatcher = uriPattern.matcher(uri);

		return uriMatcher.matches();
	}

	private byte sendResponse() throws IOException {
		this.sendStatusLine(HttpStatus.OK);

		this.sendDateHeader();
		this.sendETagHeader();
		this.sendCustomHeaders();
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();

		return this.mountCustomBody();
	}

	private byte checkSwitchingProtocol() throws IOException {
		final HttpStatus status = HttpStatus.SWITCHING_PROTOCOL.clone();

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

		final List<String> secWebsocketProtocol = Optional
			.ofNullable(this.httpRequestHeaders.get("sec-websocket-protocol"))
			.orElse(Collections.emptyList());

		if( upgrade.isEmpty() || ! "websocket".equalsIgnoreCase(upgrade.get(0)) ) {
			status.replace(HttpStatus.UPGRADE_REQUIRED);
		} else {
			if(connection.isEmpty() || ! "Upgrade".equalsIgnoreCase(connection.get(0))) {
				status.replace(HttpStatus.BAD_REQUEST);
			} else if(secWebsocketVersion.isEmpty() || ! secWebsocketVersion.contains("13") ) {
				status.replace(HttpStatus.BAD_REQUEST);
			} else if(secWebsocketKey.isEmpty() ) {
				status.replace(HttpStatus.BAD_REQUEST);
			}
		}

		this.sendStatusLine(status);
		this.sendDateHeader();

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
			this.sendAccessControlAllowOriginHeader();

			this.websocket = true;
		} else {
			this.sendConnectionCloseHeader();
		}

		this.mountHeadersTermination();

		Optional
			.ofNullable(this.httpRequestHeaders.get("origin"))
			.ifPresent(lista -> lista.stream().forEach(q -> System.out.printf("Origin: %s%n", q) ));

		if( this.websocket ) {
			this.scheduleWebsocketPingClient();
			this.notifyWebsocketOpening();
		}

		return 0;
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

	static final int CLIENT_LIVENESS = AppProperties.getClientPingSecond();
	private void scheduleWebsocketPingClient() {
		final Thread pingPong = new Thread(this::websocketPingClientEventFired);
		pingPong.setDaemon(true);

		this.pingService.scheduleAtFixedRate(pingPong, CLIENT_LIVENESS, CLIENT_LIVENESS, TimeUnit.SECONDS);
		logger.info("[WS] PING client liveness set to {} seconds", CLIENT_LIVENESS);
	}

	private void websocketPingClientEventFired() {
		if(Thread.currentThread().isInterrupted()) return;

		logger.info("[WS] Send frame PING to client");

		try {
			this.sendWebsocketPingClient();
		} catch(IOException e) {
			logger.warning(
				"[WS] A failure ocurred during ping to client: {}: {}",
				e.getClass().getCanonicalName(),
				e.getMessage());
		}
	}

	private byte consumeWebsocketClientPacket() throws IOException {
		final ByteArrayOutputStream message = new ByteArrayOutputStream();
		final ByteArrayOutputStream controlMessage = new ByteArrayOutputStream();

		final int CHECK_FIN = 0;
		final int PAYLOAD_LENGTH = 1;
		final int MASKING = 2;
		final int PAYLOAD_CONSUMPTION = 3;

		int stage = CHECK_FIN;

		final int FIN_ON  = 0b10000000;
		boolean isFinal = false;

		final byte OPCODE_BITSPACE_FLAG = 0b00001111;

		final int OPCODE_CONTROL_FLAG = 0b1000;

		int opcode = -1;
		int current_opcode = -1;
		Opcode c_opcode = null;

		final int UNMASK = 0b01111111;

		StringBuilder byteLength = new StringBuilder("");
		int payloadLength = 0;
		int nextBytes = 0;

		int[] decoder = new int[4];
		int decoderIndex = 0;

		int octet = -1;
		while ((octet = in.read()) != -1) {
			// System.out.printf("%d, ", octet);
			// if(opcode != 0) continue;

			if( stage == CHECK_FIN ) {
				isFinal = (octet & FIN_ON) == FIN_ON;
				current_opcode = octet & OPCODE_BITSPACE_FLAG;
				c_opcode = Opcode.byCode(current_opcode);
				logger.info(
					"[WS] fin/opcode frame received: {}, {}",
					isFinal,
					c_opcode.name());

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
						if( isFinal ){
							break;
						} else {
							stage = CHECK_FIN;
							continue;
						}
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
					if(isFinal) break;

					payloadLength = 0;
					stage = CHECK_FIN;
					continue;
				}
			}

		}

		if( opcode == Opcode.OPCODE_TEXT.code() ) {
			this.notifyWebsocketTextMessage(message.toByteArray());
		}

		if( opcode == Opcode.OPCODE_BINARY.code() ) {
			this.notifyWebsocketBinaryMessage(message.toByteArray());
		}

		if( opcode == Opcode.OPCODE_PONG.code() ) {
			logger.info("[WS] Client sent PONG frame.");
			return 0;
		}

		if( opcode == Opcode.OPCODE_PING.code() ) {
			logger.info("[WS] Client sent PING frame. Send back a PONG frame.");
			return this.sendWebsocketPongClient(message.toByteArray());
		}

		if( opcode == Opcode.OPCODE_CLOSE.code() ) {
			final String data = new String(controlMessage.toByteArray(), StandardCharsets.UTF_8);
			logger.info("[WS] Client sent CLOSE frame with data {}.", data);
			logger.info("[WS] Send back a CLOSE confirmation frame.");
			this.sendWebsocketCloseFrame();
			this.interrupt = true;
		}

		return 0;
	}

	private byte sendWebsocketDataClient(final String message) throws IOException {
		final byte[] rawData = message.getBytes(StandardCharsets.UTF_8);

		return this.sendWebsocketClientRawData(Opcode.OPCODE_TEXT.code(), rawData);
	}

	private byte sendWebsocketCloseFrame() throws IOException {
		final byte[] message = "closed".getBytes(StandardCharsets.UTF_8);

		return this.sendWebsocketClientRawData(Opcode.OPCODE_CLOSE.code(), message);
	}

	private byte sendWebsocketPingClient() throws IOException {
		final byte[] message = "Liveness".getBytes(StandardCharsets.UTF_8);

		return this.sendWebsocketClientRawData(Opcode.OPCODE_PING.code(), message);
	}

	private byte sendWebsocketPongClient(final byte[] rawData) throws IOException {
		return this.sendWebsocketClientRawData(Opcode.OPCODE_PONG.code(), rawData);
	}

	private byte sendWebsocketClientRawData(final byte opcode, final byte[] rawData) throws IOException {
		String binaryOpcode = "000"+Integer.toBinaryString(opcode);
		binaryOpcode = binaryOpcode.substring(binaryOpcode.length() - 4);

		// 1   : FIN (final frame): ON
		// 000 : extension (Not supported at this moment)
		// ????: opcode
		final String binaryFrame = "1000"+binaryOpcode; ;
		final int frame = Integer.parseInt(binaryFrame, 2);

		final ByteArrayOutputStream cache = new ByteArrayOutputStream();
		cache.write(frame);

		if(rawData.length <= 125) {
			cache.write(rawData.length);
		} else {
			// TODO: Send only package limited to '16 bits' length of data, indicated by a byte of value '126'.
			// TODO: In the future, send '64 bits' length of data, indicated by a byte of value '127'.
			cache.write(126);

			final String rawBinaryLength = "000000000000000"+Integer.toBinaryString(rawData.length);
			final String binaryLength = rawBinaryLength.substring(rawBinaryLength.length() - 16);

			final int byte1Length = Integer.parseInt(binaryLength.substring(0, 8), 2);
			final int byte2Length = Integer.parseInt(binaryLength.substring(8), 2);
			cache.write(byte1Length);
			cache.write(byte2Length);
		}

		cache.write(rawData);
		cache.flush();

		this.out.write(cache.toByteArray());
		this.out.flush();

		return 0;
	}

}
