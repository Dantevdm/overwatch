package com.overwatch.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP client for the BFF.
 *
 * <p>The JDK's own {@link HttpClient} and Jackson, and nothing else. There is
 * one service to talk to and one content type; a framework here would cost more
 * in startup time than it saves in lines, and startup time is most of why a CLI
 * is nicer than a browser tab.
 *
 * <p>Everything is returned as a {@link JsonNode} rather than mapped onto
 * records mirroring the API's DTOs. That is a deliberate choice for a client
 * that lives in the same repository as the server: duplicating twenty DTOs here
 * would mean every field the API adds needs adding twice, and a CLI whose job
 * is to print what the server said does not benefit from a second, staler copy
 * of the server's shapes. {@link #at} does the missing-field handling that a
 * mapped type would have given for free.
 */
public final class Api {

    /**
     * Ten seconds. Long enough for a cold JIT and a 50-row page with a sort,
     * short enough that a wedged service fails while you are still watching.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Reads the filename out of a Content-Disposition header, quoted or not. */
    private static final Pattern FILENAME = Pattern.compile("filename=\"?([^\";]+)\"?");

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final HttpClient http;
    private final String baseUrl;

    public Api(String baseUrl) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                // Follow redirects: a stack behind a reverse proxy that adds a
                // trailing slash would otherwise hand back a 301 the client
                // reports as an empty body.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public JsonNode get(String path) {
        return send(request(path).GET().build());
    }

    public JsonNode get(String path, Map<String, ?> query) {
        return get(path + queryString(query));
    }

    public JsonNode post(String path) {
        return send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    public JsonNode post(String path, Map<String, ?> query) {
        return post(path + queryString(query));
    }

    public JsonNode postJson(String path, Object body) {
        try {
            return send(request(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build());
        } catch (IOException e) {
            throw new CliException("Could not serialise the request body: " + e.getMessage());
        }
    }

    /**
     * PATCH with a JSON body.
     *
     * <p>Every PATCH this API exposes takes a {@code @RequestBody} — the state
     * of a rule, the disposition of an alert — so the body is the point. Sending
     * the fields as a query string instead answers 400 for every call, which is
     * how this was found: the shape was right and the transport was not.
     *
     * <p>No {@code .PATCH()} on the builder: the JDK client only names the four
     * methods it considers standard, and {@code method("PATCH", …)} is the
     * documented way through.
     */
    public JsonNode patch(String path, Map<String, ?> body) {
        try {
            return send(request(path)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body)))
                    .build());
        } catch (IOException e) {
            throw new CliException("Could not serialise the request body: " + e.getMessage());
        }
    }

    /** Plain text, for the actuator endpoints that are not JSON. */
    public String getText(String path) {
        try {
            HttpResponse<String> response =
                    http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw unreachable(e);
        }
    }

    /**
     * Download a binary body, returning the bytes and the name the server chose.
     *
     * <p>Read into memory rather than streamed to the file. A report is tens of
     * kilobytes and the alternative is a half-written file on disk when the API
     * answers 500 — the failure a person is most likely to hit here is "the
     * stack is not up", and it should leave nothing behind.
     */
    public Download download(String path, Map<String, ?> query) {
        try {
            // Overrides the client-wide `Accept: application/json`. Without this
            // the API correctly answers 406 for a PDF, which is a confusing way
            // to be told the client asked for the wrong thing.
            HttpResponse<byte[]> response = http.send(
                    request(path + queryString(query))
                            .setHeader("Accept", "*/*")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new CliException("The API answered " + response.statusCode()
                        + " for " + path + ".");
            }
            return new Download(response.body(), filenameFrom(response).orElse(null));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw unreachable(e);
        }
    }

    /**
     * A downloaded file: its bytes, and the name the server suggested for it.
     *
     * <p>Copied in and out. A record holding an array hands every caller a
     * writable reference to the same buffer, and the cost of not doing that here
     * is two copies of a few tens of kilobytes — cheaper than the class of bug
     * where one caller's edit shows up in another caller's file.
     */
    public record Download(byte[] bytes, String suggestedName) {

        public Download {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * The name from {@code Content-Disposition}, if the server sent one.
     *
     * <p>Taken from the server rather than invented here, because it carries the
     * generation timestamp — two reports saved an hour apart should not be
     * "report.pdf" and "report 2.pdf".
     */
    private static Optional<String> filenameFrom(HttpResponse<byte[]> response) {
        return response.headers().firstValue("Content-Disposition")
                .map(FILENAME::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                // Named, so a request from the CLI is distinguishable from the
                // browser's in the API's access log. Useful the first time
                // someone asks which client caused a spike.
                .header("User-Agent", "overwatch-cli/" + Ow.VERSION);
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw unreachable(e);
        }

        if (response.statusCode() >= 400) {
            throw new CliException(describeFailure(response));
        }
        String body = response.body();
        if (body == null || body.isBlank()) return MissingNode.getInstance();
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new CliException("The API returned something that is not JSON: " + e.getMessage());
        }
    }

    /**
     * Turn a 4xx/5xx into one readable line.
     *
     * <p>The API answers errors as JSON with a {@code detail} or {@code error}
     * field, and that sentence is written for a person — so it is worth more
     * than the status code. Falls back to the raw body, truncated, because an
     * HTML error page from a proxy is not worth 40 lines of terminal.
     */
    private String describeFailure(HttpResponse<String> response) {
        String detail = null;
        try {
            JsonNode node = JSON.readTree(response.body());
            for (String field : new String[] { "detail", "error", "message" }) {
                if (node.hasNonNull(field)) { detail = node.get(field).asText(); break; }
            }
        } catch (IOException | RuntimeException ignored) {
            // Not JSON. The raw body below is the best available.
        }
        if (detail == null) {
            String body = response.body() == null ? "" : response.body().strip();
            detail = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        }
        String where = response.uri().getPath();
        return "The API answered " + response.statusCode() + " for " + where
                + (detail.isBlank() ? "" : ": " + detail);
    }

    private CliException unreachable(Exception cause) {
        return new CliException(
                "Could not reach the API at " + baseUrl + " (" + cause.getMessage() + ")."
                        + System.lineSeparator()
                        + "Is the stack up? `make up`, then `make urls` for the address on this "
                        + "machine — the API port moves if 8080 was taken. Override it with "
                        + "--api or OVERWATCH_API.");
    }

    private static String queryString(Map<String, ?> query) {
        if (query == null || query.isEmpty()) return "";
        StringBuilder out = new StringBuilder("?");
        for (Map.Entry<String, ?> entry : query.entrySet()) {
            // Nulls are how a command says "I have no opinion about this
            // filter", so they are dropped rather than sent as the string
            // "null" — which the API would treat as a filter value.
            if (entry.getValue() == null) continue;
            if (out.length() > 1) out.append('&');
            out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
               .append('=')
               .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return out.length() == 1 ? "" : out.toString();
    }

    /**
     * Walk a dotted path, returning a missing node rather than throwing.
     *
     * <p>{@code at(alert, "hits.0.reason")} either gives the reason or gives
     * nothing. A CLI printing a table must not die on one absent field in one
     * row, and this is the whole reason JsonNode is workable here.
     */
    public static JsonNode at(JsonNode node, String path) {
        JsonNode current = node;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return MissingNode.getInstance();
            }
            current = segment.chars().allMatch(Character::isDigit)
                    ? current.path(Integer.parseInt(segment))
                    : current.path(segment);
        }
        return current == null ? MissingNode.getInstance() : current;
    }

    public static String text(JsonNode node, String path, String fallback) {
        JsonNode found = at(node, path);
        return found.isMissingNode() || found.isNull() ? fallback : found.asText();
    }

    public static double number(JsonNode node, String path) {
        return at(node, path).asDouble(0);
    }

    public static long integer(JsonNode node, String path) {
        return at(node, path).asLong(0);
    }

    /** An ordered map, so a body's fields print in the order they were written. */
    public static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
