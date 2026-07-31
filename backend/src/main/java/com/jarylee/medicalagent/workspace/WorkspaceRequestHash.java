package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

final class WorkspaceRequestHash {
    private WorkspaceRequestHash() {}

    static String sha256(
            ObjectMapper json,
            String actionCode,
            long expectedVersion,
            JsonNode body) {
        try {
            ObjectNode request = json.createObjectNode();
            request.put("actionCode", actionCode);
            request.put("expectedReadModelVersion", expectedVersion);
            request.set("body", body == null
                    ? json.createObjectNode() : body.deepCopy());
            byte[] canonical = json.writeValueAsBytes(canonicalize(json, request));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 V2 动作请求哈希", exception);
        }
    }

    private static JsonNode canonicalize(ObjectMapper json, JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(
                    entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, value) ->
                    result.set(key, canonicalize(json, value)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(value -> result.add(canonicalize(json, value)));
            return result;
        }
        return node.deepCopy();
    }
}
