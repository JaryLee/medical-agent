package com.jarylee.medicalagent.research;

import java.security.SecureRandom;
import java.util.regex.Pattern;

public final class ProjectKey {
    private static final char[] CROCKFORD =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final Pattern FORMAT =
            Pattern.compile("^prj_[0-9A-HJKMNP-TV-Z]{26}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProjectKey() {
    }

    public static String generate() {
        char[] value = new char[30];
        value[0] = 'p';
        value[1] = 'r';
        value[2] = 'j';
        value[3] = '_';
        for (int index = 4; index < value.length; index++) {
            value[index] = CROCKFORD[RANDOM.nextInt(CROCKFORD.length)];
        }
        return new String(value);
    }

    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }
}
