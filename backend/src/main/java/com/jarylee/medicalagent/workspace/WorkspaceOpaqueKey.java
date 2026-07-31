package com.jarylee.medicalagent.workspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class WorkspaceOpaqueKey {
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private WorkspaceOpaqueKey() {}

    static String of(String prefix, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            byte[] hash = digest.digest();
            StringBuilder encoded = new StringBuilder(prefix);
            for (int index = 0; index < 26; index++) {
                int bit = index * 5;
                int byteIndex = bit / 8;
                int shift = 19 - (bit % 8);
                int window = (hash[byteIndex] & 0xff) << 16;
                if (byteIndex + 1 < hash.length) {
                    window |= (hash[byteIndex + 1] & 0xff) << 8;
                }
                if (byteIndex + 2 < hash.length) {
                    window |= hash[byteIndex + 2] & 0xff;
                }
                encoded.append(CROCKFORD.charAt((window >>> shift) & 31));
            }
            return encoded.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成工作台公开标识", exception);
        }
    }
}
