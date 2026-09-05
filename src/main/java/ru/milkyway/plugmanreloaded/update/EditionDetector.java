package ru.milkyway.plugmanreloaded.update;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Locale;
import java.util.regex.Pattern;

public final class EditionDetector {

    private static final Pattern EDITION_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?:^|[._\\-\\[\\(])(prem|premium|paid|pro|plus|elite|ultimate|full)(?:[._\\-\\]\\)\\d]|\\.jar$|$)"
    );

    private static final Pattern STRICT_PREMIUM_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?:^|[._\\-\\[\\(])(prem|premium|paid)(?:[._\\-\\]\\)\\d]|\\.jar$|$)"
    );

    private EditionDetector() {}

    public static PluginEdition detect(File jar, String pluginName, String version, String mainClass, String website) {
        if (isPremium(jar, pluginName, version, mainClass, website)) {
            return PluginEdition.PREMIUM;
        }
        return PluginEdition.FREE;
    }

    private static boolean isPremium(File jar, String pluginName, String version, String mainClass, String website) {
        String normalizedName = NameUtil.normalizeName(pluginName);

        if (pluginName != null && STRICT_PREMIUM_TOKEN_PATTERN.matcher(pluginName).find()) {
            return true;
        }

        if (version != null && STRICT_PREMIUM_TOKEN_PATTERN.matcher(version).find()) {
            return true;
        }

        if (website != null && !website.isBlank()) {
            String lowerWeb = website.toLowerCase(Locale.ROOT);
            if (lowerWeb.contains("polymart.org") || lowerWeb.contains("builtbybit.com") || lowerWeb.contains("songoda.com/marketplace")) {
                return true;
            }
        }

        if (jar != null) {
            String jarName = jar.getName();
            if (jarName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                jarName = jarName.substring(0, jarName.length() - 4);
            }

            if (pluginName != null && !pluginName.isBlank()) {
                String remainder = stripPrefixIgnoreCase(jarName, pluginName);
                if (remainder.length() < jarName.length()) {
                    if (EDITION_TOKEN_PATTERN.matcher(remainder).find()) {
                        return true;
                    }
                }
            }

            if (STRICT_PREMIUM_TOKEN_PATTERN.matcher(jarName).find()) {
                if (pluginName == null || !NameUtil.normalizeName(jarName).equals(normalizedName)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String stripPrefixIgnoreCase(@Nullable String text, String prefix) {
        if (text == null || prefix == null) return text;
        if (text.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return text.substring(prefix.length());
        }
        return text;
    }
}

