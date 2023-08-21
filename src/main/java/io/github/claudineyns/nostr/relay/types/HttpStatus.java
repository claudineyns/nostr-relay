package io.github.claudineyns.nostr.relay.types;

public class HttpStatus {
	public static final HttpStatus SWITCHING_PROTOCOL = new HttpStatus(101, "Switching Protocols");
	public static final HttpStatus OK = new HttpStatus(200, "OK");
	public static final HttpStatus BAD_REQUEST = new HttpStatus(400, "Bad Request");
	public static final HttpStatus NOT_FOUND = new HttpStatus(404, "Not Found");
    public static final HttpStatus METHOD_NOT_ALLOWED = new HttpStatus(405, "Method Not Allowed");
	public static final HttpStatus UPGRADE_REQUIRED = new HttpStatus(426, "Upgrade Required");
	public static final HttpStatus SERVER_ERROR = new HttpStatus(500, "Server Error");
	public static final HttpStatus NOT_IMPLEMENTED = new HttpStatus(501, "Not Implemented");
	public static final HttpStatus HTTP_VERSION_NOT_SUPPORTED = new HttpStatus(505, "HTTP Version not supported");

    private int _code;
    private String _text;

    public HttpStatus(final int code, final String text) {
        this._code = code;
        this._text = text;
    }

    public HttpStatus clone() {
        return new HttpStatus(this._code, this._text);
    }

    public void replace(HttpStatus status) {
        this._code = status.code();
        this._text = status.text();
    }

    public int code() {
        return _code;
    }

    public String text() {
        return _text;
    }
}