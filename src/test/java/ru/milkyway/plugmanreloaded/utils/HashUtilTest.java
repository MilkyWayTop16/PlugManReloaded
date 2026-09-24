package ru.milkyway.plugmanreloaded.utils;

import ru.milkyway.plugmanreloaded.update.PluginMatcher;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashUtilTest {

    @Test
    void encodesKnownBytesToLowercaseHex() {
        assertEquals("", PluginMatcher.toHex(new byte[0]));
        assertEquals("00", PluginMatcher.toHex(new byte[]{0}));
        assertEquals("ff", PluginMatcher.toHex(new byte[]{(byte) 0xFF}));
        assertEquals("0aff", PluginMatcher.toHex(new byte[]{0x0A, (byte) 0xFF}));
        assertEquals("", PluginMatcher.toHex(null));
    }

    @Test
    void producesExactlyTwoHexCharsPerByteForARealDigest() throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha256.digest("PlugManReloaded".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hex = PluginMatcher.toHex(digest);

        assertEquals(digest.length * 2, hex.length(), "каждый байт обязан давать ровно 2 hex-символа, без пропусков и лишних цифр");
        assertEquals(hex.toLowerCase(java.util.Locale.ROOT), hex, "вывод обязан быть в нижнем регистре");
        assertEquals("", hex.replaceAll("[0-9a-f]", ""), "в выводе не должно быть ничего, кроме hex-цифр 0-9a-f");

        StringBuilder independent = new StringBuilder();
        for (byte b : digest) {
            independent.append(Character.forDigit((b >> 4) & 0xF, 16));
            independent.append(Character.forDigit(b & 0xF, 16));
        }
        assertEquals(independent.toString(), hex);
    }
}
