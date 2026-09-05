package ru.milkyway.plugmanreloaded.utils;

import org.jetbrains.annotations.Nullable;

public final class HashUtil {

    private HashUtil() {}

    public static String toHex(@Nullable byte[] digest) {
        if (digest == null) return "";
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            String part = Integer.toHexString(b & 0xFF);
            if (part.length() == 1) {
                builder.append('0');
            }
            builder.append(part);
        }
        return builder.toString();
    }
}

