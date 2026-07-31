package com.jarylee.medicalagent.auth;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class IdentityNormalizer {
    private static final Pattern HOSPITAL_CODE =
            Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,63}$");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cc}");

    private IdentityNormalizer() {
    }

    public static String hospitalCode(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (!HOSPITAL_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "医院编码必须为 2～64 位大写字母、数字、下划线或连字符");
        }
        return normalized;
    }

    public static String username(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty() || normalized.length() > 100
                || CONTROL_CHARACTER.matcher(normalized).find()) {
            throw new IllegalArgumentException(
                    "用户名必须为 1～100 个非控制字符");
        }
        return normalized;
    }

    public static String usernameLookup(String value) {
        return username(value).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("标识不能为空");
        }
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC);
    }
}
