package cc.starsector.client.editor;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * ONE bounded HTTP request on the loopback editor socket (W15 v1):
 * request line ≤ {@value #MAX_REQUEST_LINE} B, headers ≤ {@value #MAX_HEADER_BYTES} B total and
 * ≤ {@value #MAX_HEADERS} lines, body ≤ {@value #MAX_BODY_BYTES} B and ONLY with an explicit
 * {@code Content-Length} (no chunked transfer), methods GET/POST only, socket read timeout set
 * by the host (5 s). Every bound violation is an {@link Refused} carrying the 4xx status the
 * host replies with before closing. Replies are HTTP/1.0 {@code Connection: close}.
 *
 * <p>Classloader-B hygiene: the socket streams
 * are read through {@code BufferedInputStream} (allowed) with byte-level line scanning — no
 * {@code BufferedReader} over a mixed text/binary stream, no {@code ByteArrayOutputStream}, no
 * {@code Writer}; the only catch types are {@code IOException} / {@code RuntimeException}.
 */
public final class HttpExchange {
    static final int MAX_REQUEST_LINE = 2048;
    static final int MAX_HEADER_BYTES = 8192;
    static final int MAX_HEADERS = 64;
    static final int MAX_BODY_BYTES = 65536;

    /** A request the parser refuses; {@code status} is the reply code (4xx). */
    public static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public final String method;
    /** Path without the query string, percent-decoded ({@code /} kept as sent). */
    public final String path;
    /** Raw query string after {@code ?} (empty when absent). */
    public final String query;
    /** Header names lower-cased; first occurrence wins. */
    public final Map<String, String> headers;
    public final byte[] body;

    private HttpExchange(String method, String path, String query, Map<String, String> headers,
            byte[] body) {
        this.method = method;
        this.path = path;
        this.query = query;
        this.headers = headers;
        this.body = body;
    }

    public String header(String lowerName) {
        return headers.get(lowerName);
    }

    /** One {@code name=value} query parameter, percent-decoded; {@code null} when absent. */
    public String queryParam(String name) {
        if (query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(name)) {
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    return URLDecoder.decode(v, StandardCharsets.UTF_8);
                } catch (RuntimeException bad) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Parse one request from {@code raw}; throws {@link Refused} on any bound. */
    public static HttpExchange read(InputStream raw) throws IOException {
        InputStream in = new BufferedInputStream(raw, 8192);
        String requestLine = readLine(in, MAX_REQUEST_LINE, 414);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new Refused(400, "empty request line");
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || parts.length > 3) {
            throw new Refused(400, "malformed request line");
        }
        String method = parts[0];
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new Refused(405, "method not allowed");
        }
        String target = parts[1];
        if (!target.startsWith("/") || target.length() > MAX_REQUEST_LINE) {
            throw new Refused(400, "bad request target");
        }
        String rawPath = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            rawPath = target.substring(0, q);
            query = target.substring(q + 1);
        }
        String path;
        try {
            path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (RuntimeException bad) {
            throw new Refused(400, "bad percent-encoding");
        }
        Map<String, String> headers = new HashMap<>();
        int headerBytes = 0;
        int headerCount = 0;
        while (true) {
            String line = readLine(in, MAX_HEADER_BYTES, 431);
            if (line == null) {
                throw new Refused(400, "headers truncated");
            }
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new Refused(431, "headers too large");
            }
            if (line.isEmpty()) {
                break;
            }
            if (++headerCount > MAX_HEADERS) {
                throw new Refused(431, "too many headers");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new Refused(400, "malformed header");
            }
            String name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.putIfAbsent(name, value);
        }
        byte[] body = new byte[0];
        if ("POST".equals(method)) {
            if (headers.containsKey("transfer-encoding")) {
                throw new Refused(411, "chunked bodies are not accepted");
            }
            String lenText = headers.get("content-length");
            if (lenText == null) {
                throw new Refused(411, "length required");
            }
            int len;
            try {
                len = Integer.parseInt(lenText);
            } catch (NumberFormatException bad) {
                throw new Refused(400, "bad content-length");
            }
            if (len < 0 || len > MAX_BODY_BYTES) {
                throw new Refused(413, "body too large");
            }
            body = new byte[len];
            int got = 0;
            while (got < len) {
                int n = in.read(body, got, len - got);
                if (n < 0) {
                    throw new Refused(400, "body truncated");
                }
                got += n;
            }
        }
        return new HttpExchange(method, path, query, headers, body);
    }

    /** One CRLF/LF-terminated ISO-8859-1 line, at most {@code max} bytes; null at EOF. */
    private static String readLine(InputStream in, int max, int overflowStatus) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int end = sb.length();
                if (end > 0 && sb.charAt(end - 1) == '\r') {
                    sb.setLength(end - 1);
                }
                return sb.toString();
            }
            if (sb.length() >= max) {
                throw new Refused(overflowStatus, "line too long");
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ---------------------------------------------------------------- replies

    /**
     * The browser-facing policy on EVERY reply (the ServerOpsWeb idiom): scripts,
     * styles and images only from this origin (the SPA ships no inline script/style), no
     * plugins, never framed. The SPA never needs an exception.
     */
    public static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    public static void reply(OutputStream out, int status, String contentType, byte[] body)
            throws IOException {
        reply(out, status, contentType, body, null);
    }

    /** {@code extraHeader} = one complete {@code Name: value} line or null (a redirect's Location). */
    static void reply(OutputStream out, int status, String contentType, byte[] body, String extraHeader)
            throws IOException {
        reply(out, status, contentType, body, extraHeader, "no-store");
    }

    /** A 200 the browser may keep for the session (game sprites: immutable while the game runs). */
    public static void replyCacheable(OutputStream out, String contentType, byte[] body) throws IOException {
        reply(out, 200, contentType, body, null, "private, max-age=86400");
    }

    private static void reply(OutputStream out, int status, String contentType, byte[] body, String extraHeader,
            String cacheControl) throws IOException {
        String head = "HTTP/1.0 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: " + cacheControl + "\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "X-Frame-Options: DENY\r\n"
                + "Content-Security-Policy: " + CSP + "\r\n"
                + (extraHeader == null ? "" : extraHeader + "\r\n")
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** A 302 to a path of OUR making (never request-derived beyond the token the request already carried). */
    public static void replyRedirect(OutputStream out, String location) throws IOException {
        reply(out, 302, "text/plain; charset=utf-8", ("see " + location).getBytes(StandardCharsets.UTF_8),
                "Location: " + location);
    }

    public static void replyText(OutputStream out, int status, String text) throws IOException {
        reply(out, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    public static void replyJson(OutputStream out, int status, String json) throws IOException {
        reply(out, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 414: return "URI Too Long";
            case 422: return "Unprocessable Entity";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }
}
