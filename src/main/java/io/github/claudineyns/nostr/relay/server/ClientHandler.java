package io.github.claudineyns.nostr.relay.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.claudineyns.nostr.relay.exceptions.CloseConnectionException;
import io.github.claudineyns.nostr.relay.types.HttpMethod;
import io.github.claudineyns.nostr.relay.types.HttpStatus;
import io.github.claudineyns.nostr.relay.utilities.LogService;

import static io.github.claudineyns.nostr.relay.utilities.Utils.secWebsocketAccept;

@SuppressWarnings("unused")
public class ClientHandler implements Runnable {
	private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

	private Socket client;

	private InputStream in;
	private OutputStream out;

	public ClientHandler(Socket c) {
		this.client = c;
	}

	private boolean interrupt = false;

	private boolean websocket = false;

	final int socket_timeout = 10000;

	@Override
	public void run() {
		try {
			this.makeItReady();
		} catch(IOException failure) {

		}
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
				if(!interrupt) {
					continue;
				}
			} catch (SocketTimeoutException e) {
				logger.warning(e.getMessage());
			} catch (CloseConnectionException e) {
				logger.warning("Connection closed");
			} catch (IOException e) {
				logger.warning("Request handling error: {}: {}", e.getClass().getCanonicalName(), e.getMessage());
			}
			break;
		}		
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
		this.consumeWebsocketClientPacket();

		return 0;
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
			case "/":
				return Q_SWITCHING_PROTOCOL;
			default:
				return this.sendIndexPage();
		}
	}

	private byte analyseRequestHeader(byte[] raw) throws IOException {
		final String CRLF_RE = "\\r\\n";

		// https://www.rfc-editor.org/rfc/rfc2616.html#section-2.2
		/*
		 	HTTP/1.1 header field values can be folded onto multiple lines if the
   			continuation line begins with a space or horizontal tab. All linear
   			white space, including folding, has the same semantics as SP.
   			A recipient MAY replace any linear white space with a single SP before
   			interpreting the field value or forwarding the message downstream.
		 */
		// https://www.rfc-editor.org/rfc/rfc2616.html#section-4.2
		/*
			Header fields can be extended over multiple lines by preceding each extra line with at least one SP or HT. 
		 */
		final String data = new String(raw, StandardCharsets.US_ASCII).replaceAll("\\r\\n[\\s\\t]+", "\u0000\u0000\u0000");
		final String[] entries = data.split(CRLF_RE);

		if (entries.length == 0) {
			return sendBadRequest("Invalid HTTP Request");
		}

		// https://www.rfc-editor.org/rfc/rfc2616.html#section-4.1
		/*
		 	In the interest of robustness, servers SHOULD ignore any empty
   			line(s) received where a Request-Line is expected. In other words, if
   			the server is reading the protocol stream at the beginning of a
   			message and receives a CRLF first, it should ignore the CRLF.
		 */
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
			final String value = entry.substring(entry.indexOf(':') + 1).trim().replaceAll("[\u0000]{3}", "\r\n ");
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
		final DateTimeFormatter RFC_1123_DATE_TIME = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
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
	private byte sendIndexPage() throws IOException {
		final String html = 
				  "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
				+ "<title>Nostr Relay</title></head><body>"
				+ "<h3 style=\"font-family: Courier, monospace; text-align: center; font-weight: normal;\">Nostr Relay</h3>"
				+ "</body></html>\n";
		final byte[] raw = html.getBytes(StandardCharsets.UTF_8);

		this.httpResponseHeaders.put("Content-Type", Collections.singletonList("text/html; charset=UTF-8"));
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
			
			this.websocket = true;
		} else {
			this.sendConnectionCloseHeader();
		}

		this.mountHeadersTermination();

		return 0;
	}

	private void consumeWebsocketClientPacket() throws IOException {
		final ByteArrayOutputStream message = new ByteArrayOutputStream();
		final ByteArrayOutputStream controlMessage = new ByteArrayOutputStream();

		final int CHECK_FIN = 0;
		final int PAYLOAD_LENGTH = 1;
		final int DECODER = 2;
		final int PAYLOAD_CONSUMPTION = 3;

		int stage = CHECK_FIN;

		final int FIN_ON  = 0b10000000;
		boolean isFinal = false;

		final int OPCODE_FLAG     = 0b00001111;
		final int OPCODE_CONTINUE = 0b0000;
		final int OPCODE_TEXT     = 0b0001;
		final int OPCODE_BINARY   = 0b0010;

		final int OPCODE_CONTROL_FLAG = 0b1000;
		final int OPCODE_CLOSE = 0b1000;

		int opcode = -1;
		int current_opcode = -1;

		final int UNMASK = 0b01111111;

		StringBuilder byteLength = new StringBuilder("");
		int payloadLength = 0;
		int nextBytes = 0;

		int[] decoder = new int[4];
		int decoderIndex = 0;

		int octet = -1;
		while ((octet = in.read()) != -1) {

			if( stage == CHECK_FIN ) {
				isFinal = (octet & FIN_ON) == FIN_ON;
				current_opcode = octet & OPCODE_FLAG;
				logger.info("[WS] final?: {}", isFinal);
				logger.info("[WS] opcode: {}", current_opcode);
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

						stage = DECODER;
						continue;
					} else if( byteCheck == 126 ) {
						nextBytes = 2;
						continue;
					} else if( byteCheck == 127 ) {
						nextBytes = 8;
						continue;
					}
				} else {
					final String binaryOctet = String.format("%8s", Integer.toBinaryString(octet))
						.replace(" ", "0");
					byteLength.append(binaryOctet);

					if( --nextBytes == 0 ) {						
						payloadLength = Integer.parseInt(byteLength.toString(), 2);
						byteLength = new StringBuilder("");

						stage = DECODER;
						continue;
					}
				}
			}

			if( stage == DECODER ) {
				decoder[decoderIndex++] = octet;
				if(decoderIndex == decoder.length) {
					decoderIndex = 0;

					stage = PAYLOAD_CONSUMPTION;
					continue;
				}
			}

			if( stage == PAYLOAD_CONSUMPTION ) {
				int decoded = (octet ^ decoder[decoderIndex++ & 0x3]);
				if( (current_opcode & OPCODE_CONTROL_FLAG) == OPCODE_CONTROL_FLAG ) {
					controlMessage.write(decoded);
				} else {
					message.write(decoded);
				}
				if( --payloadLength == 0 ) {
					if(isFinal) {
						break;
					} else {
						payloadLength = 0;

						stage = CHECK_FIN;
						continue;
					}
				}
			}

		}

		if( opcode == OPCODE_TEXT ) {
			final String data = new String(message.toByteArray(), StandardCharsets.UTF_8);
			logger.info("[WS] Text Message received:\n{}", data);
			this.sendWebsocketDataClient(data);
		}

		if( opcode == OPCODE_CLOSE ) {
			logger.info("[WS] Client sent close request");
			this.sendWebsocketCloseFrame();
			this.interrupt = true;
		}

	}

	private void sendWebsocketDataClient(final String message) throws IOException {
		final byte[] rawData = message.getBytes(StandardCharsets.UTF_8);

		final ByteArrayOutputStream cache = new ByteArrayOutputStream();

		// 1   : FIN (final) ON
		// 000 : extension (none at this moment)
		// 0001: type 'text'
		cache.write(0b10000001);

		/*
		 * Send payload message
		 */

		if(rawData.length <= 125) {
			final int q = rawData.length;
			cache.write(q);
		} else {
			final int q = 126;
			cache.write(q);
			String binaryLength = Integer.toBinaryString(rawData.length);
			while(binaryLength.length() % 16 != 0) {
				binaryLength = "0"+binaryLength;
			}
			cache.write( Integer.parseInt(binaryLength.substring(0, 8), 2) );
			cache.write( Integer.parseInt(binaryLength.substring(8), 2) );
		}

		cache.write(rawData);

		this.out.write(cache.toByteArray());
		this.out.flush();

	}

	private void sendWebsocketCloseFrame() throws IOException {
		final ByteArrayOutputStream cache = new ByteArrayOutputStream();

		final byte[] message = "Closed".getBytes(StandardCharsets.UTF_8);

		// 1   : FIN (final) ON
		// 000 : extension (none at this moment)
		// 1000: type 'Close'
		cache.write(0b10001000);

		// Send Message
		cache.write(message.length);
		cache.write(message);

		this.out.write(cache.toByteArray());
		this.out.flush();

	}

}
