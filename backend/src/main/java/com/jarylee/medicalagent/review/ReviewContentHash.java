package com.jarylee.medicalagent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

public final class ReviewContentHash {
    private ReviewContentHash() {}

    public static String sha256(ObjectMapper json, String outputJson) {
        try {
            JsonNode parsed = json.readTree(outputJson);
            if (!(parsed instanceof ObjectNode root)) {
                throw new IllegalStateException("Agent 任务输出必须是 JSON 对象");
            }
            ObjectNode reviewable = root.deepCopy();
            reviewable.remove("expertReview");
            reviewable.remove("documentExport");
            byte[] canonical = json.writeValueAsBytes(canonicalize(json, reviewable));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算审核内容哈希", exception);
        }
    }

    private static JsonNode canonicalize(ObjectMapper json, JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            var sorted = new TreeMap<String, JsonNode>();
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
