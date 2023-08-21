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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.claudineyns.nostr.relay.exceptions.CloseConnectionException;
import io.github.claudineyns.nostr.relay.utilities.LogService;

public class ClientHandler implements Runnable {
	private final LogService logger = LogService.getInstance("HTTP-SERVER");

	private Socket client;

	private InputStream in;
	private OutputStream out;

	public ClientHandler(Socket c) {
		this.client = c;
	}

	private boolean interrupt = false;

	final int socket_timeout = 10000;

	@Override
	public void run() {
		try {
			this.client.setSoTimeout(socket_timeout);
			this.in = client.getInputStream();
			this.out = client.getOutputStream();
		} catch(IOException e) {
			logger.warning("Request startup error: {}", e.getMessage());
			return;
		}

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
				logger.warning("Request handling error: {}", e.getMessage());
			}
			break;
		}

		try {
			client.close();
		} catch (IOException e) { /***/ }

		logger.info("Client connection terminated.");
	}

	private static enum HttpMethod {
		OPTIONS, HEAD, GET, POST, PUT, DELETE, TRACE, CONNECT;

		static HttpMethod from(final String method) {
			if (method == null) {
				return null;
			}
			for (final HttpMethod m : values()) {
				if (m.name().equalsIgnoreCase(method)) {
					return m;
				}
			}
			return null;
		}

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
		this.cleanup();
		
		this.startHandleHttpRequest();

		if (this.requestMethod != null) {
			this.continueHandleHttpRequest();
		}
		out.flush();

		return this.checkCloseConnection();
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
	static final byte Q_SERVER_ERROR = 1;

	private byte validateMessagePayloadRequirement() throws IOException {
		final boolean bodyExpected 
				=	HttpMethod.POST.equals(this.requestMethod) 
				||	HttpMethod.PUT.equals(this.requestMethod);

		final boolean contentLengthProvided = this.httpRequestHeaders.containsKey("content-length");
		final boolean transferEncodingProvided = this.httpRequestHeaders.containsKey("transfer-encoding");

		if (bodyExpected) {
			if( ! contentLengthProvided && ! transferEncodingProvided ) {
				this.sendLengthRequired();
				return 1;
			}
		}

		return 0;
	}

	private byte continueHandleHttpRequest() throws IOException {
		final byte payloadRequirements = this.validateMessagePayloadRequirement();

		if (payloadRequirements == 1) { return 0; }

		this.extractBodyPayload();

		this.httpResponseHeaders.put("Content-Length", Collections.singletonList("0"));

		byte returnCode = 0;
		switch (this.requestMethod) {
		case GET:
			returnCode = this.handleGetRequests();
			break;
		default:
			return this.sendMethodNotAllowed();
		}

		if (returnCode == Q_BAD_REQUEST) {
			return this.sendBadRequest("Invalid Request Data");
		}

		if (returnCode == Q_NOT_FOUND) {
			return this.sendResourceNotFound();
		}

		if (returnCode != 0) {
			return this.sendServerError(null);
		}

		return sendResponse();
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
				return this.sendIndexPage();
			default:
				return Q_NOT_FOUND;
		}
	}

	private byte extractBodyPayload() throws IOException {
		final List<String> transferEncoding = this.httpRequestHeaders.get("transfer-encoding");

		if( transferEncoding == null ) {
			return extractBodyByContent();
		}

		return extractBodyByTransfer(transferEncoding);
	}

	private byte extractBodyByContent() throws IOException {
		final List<String> contentLength = this.httpRequestHeaders.get("content-length");

		if (contentLength == null || contentLength.isEmpty()) {
			return 0;
		}

		final int length = Integer.parseInt(contentLength.get(0));
		int remainingLength = length;

		final int maxLength = 1024;
		final byte[] chunkData = new byte[maxLength];

		while (remainingLength > 0) {
			final int chunkSize = remainingLength > maxLength ? maxLength : remainingLength;

			this.in.read(chunkData, 0, chunkSize);
			this.httpRequestBody.write(chunkData, 0, chunkSize);

			remainingLength -= chunkSize;
		}

		return 0;
	}

	static final byte HEX_BASE = 16;

	private byte extractBodyByTransfer(final List<String> transferEncoding) throws IOException {
		StringBuilder chunkLengthStage = new StringBuilder("");

		// Octets used to check end of line
		int octet0 = 0;
		int octet1 = 0;

		int octet = 0;
		while (true) {
			octet = this.in.read();

			octet0 = octet1;
			octet1 = octet;

			if( octet == '\r' ) { continue; }

			if ( octet != '\n' && octet0 != '\r' ) {
				chunkLengthStage.append( (char)octet );
				continue;
			}

			final int chunkLength = Integer.parseInt(chunkLengthStage.toString(), HEX_BASE);

			if(chunkLength == 0) {
				/*
				 * Read the last two octets, which are expected to be: '\r' (char 13) and '\n' (char 10),
				 * ending the full chunk payload parsing 
				 */
				this.in.readNBytes(2);
				break;
			}

			final byte[] chunkData = this.in.readNBytes(chunkLength);
			this.httpRequestBody.write(chunkData);
			
			/*
			 * Read the next two octets, which are expected to be: '\r' (char 13) and '\n' (char 10),
			 * ending the actual chunk data
			 */
			this.in.readNBytes(2);

			// reset the chunk length control
			chunkLengthStage = new StringBuilder("");

			// reset the octets used to check end of line
			octet0 = 0;
			octet1 = 0;
		}

		return 0;
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
			this.interrupt = true;
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
			this.interrupt = true;
			return sendBadRequest("Invalid HTTP Method Sintax");
		}

		logger.info(methodLine);

		final String httpVersion = methodContent[2];
		if ( ! "HTTP/1.1".equalsIgnoreCase(httpVersion) ) {
			this.interrupt = true;
			return sendVersionNotSupported();
		}

		final String methodLineLower = methodLine.toUpperCase();
		final String method = methodLineLower.substring(0, methodLineLower.indexOf(" "));

		final HttpMethod httpMethod = HttpMethod.from(method);
		if (httpMethod == null) {
			this.interrupt = true;
			return sendMethodNotImplemented();
		}

		if (!methodLineLower.toUpperCase().startsWith(httpMethod.name() + " ")) {
			this.interrupt = true;
			return sendBadRequest("Invalid HTTP Method Sintax");
		}

		final String uri = methodContent[1];
		if (!validateURI(uri)) {
			this.interrupt = true;
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

	private byte sendStatusLine(final String statusLine) throws IOException {
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

	private byte sendConnectionCloseHeader() throws IOException {
		out.write(("Connection: Close" + CRLF).getBytes(StandardCharsets.US_ASCII));
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
		this.sendStatusLine("HTTP/1.1 400 Bad Request");
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

		this.sendStatusLine("HTTP/1.1 404 Not Found");
		this.sendDateHeader();
		this.sendContentHeader("text/plain", raw.length);
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();

		out.write(raw);

		return 0;
	}
	
	private byte sendVersionNotSupported() throws IOException {
		this.sendStatusLine("HTTP/1.1 505 HTTP Version not supported");
		this.sendDateHeader();
		this.sendConnectionCloseHeader();

		return this.mountHeadersTermination();
	}

	private byte sendMethodNotImplemented() throws IOException {
		this.sendStatusLine("HTTP/1.1 501 Not Implemented");
		this.sendDateHeader();
		this.sendConnectionCloseHeader();

		return this.mountHeadersTermination();
	}

	private byte sendMethodNotAllowed() throws IOException {
		this.sendStatusLine("HTTP/1.1 405 Method Not Allowed");
		this.sendDateHeader();
		this.sendConnectionCloseHeader();

		return this.mountHeadersTermination();
	}

	private byte sendLengthRequired() throws IOException {
		this.sendStatusLine("HTTP/1.1 411 Length Required");
		this.sendDateHeader();
		this.sendConnectionCloseHeader();

		return this.mountHeadersTermination();
	}

	private byte sendServerError(String cause) throws IOException {
		this.sendStatusLine("HTTP/1.1 500 Server Error");
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
		this.sendStatusLine("HTTP/1.1 200 OK");

		this.sendDateHeader();
		this.sendETagHeader();
		this.sendCustomHeaders();
		this.sendConnectionCloseHeader();
		this.mountHeadersTermination();

		return this.mountCustomBody();
	}

}
