package com.matheusmelo.kybr.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The idempotency request hash: lowercase hex SHA-256 over
 * {@code method|routeTemplate|binding|body}, where body is the canonical JSON (keys sorted at every
 * level, no whitespace). Pure: the caller passes the application {@link JsonMapper}, the same one
 * that binds request bodies, so hashing and binding accept and reject exactly the same bodies
 * (strict parsing: duplicate keys and trailing tokens are rejected).
 *
 * <ul>
 *   <li>An empty (or absent) body hashes the empty string.
 *   <li>A body the mapper can't parse is hashed over its raw bytes, so the request still reaches
 *       validation, which rejects it.
 * </ul>
 */
public final class RequestHash {

    private static final char SEPARATOR = '|';

    private RequestHash() {
    }

    public static String of(JsonMapper mapper, String method, String routeTemplate, String binding, byte[] body) {
        MessageDigest sha256 = sha256();
        sha256.update((method + SEPARATOR + routeTemplate + SEPARATOR + binding + SEPARATOR)
                .getBytes(StandardCharsets.UTF_8));
        if (body != null && body.length > 0) {
            sha256.update(canonicalJson(mapper, body).map(json -> json.getBytes(StandardCharsets.UTF_8)).orElse(body));
        }
        return HexFormat.of().formatHex(sha256.digest());
    }

    /** The canonical JSON of {@code body}, or empty when the application mapper rejects it. */
    public static Optional<String> canonicalJson(JsonMapper mapper, byte[] body) {
        try {
            JsonNode tree = mapper.readTree(body);
            if (tree == null || tree.isMissingNode()) {
                return Optional.empty();
            }
            return Optional.of(mapper.writeValueAsString(sorted(mapper, tree)));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static JsonNode sorted(JsonMapper mapper, JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                fields.put(field.getKey(), sorted(mapper, field.getValue()));
            }
            ObjectNode out = mapper.createObjectNode();
            fields.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode element : node) {
                out.add(sorted(mapper, element));
            }
            return out;
        }
        return node;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
