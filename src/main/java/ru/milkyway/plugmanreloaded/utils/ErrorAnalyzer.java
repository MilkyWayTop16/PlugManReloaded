package ru.milkyway.plugmanreloaded.utils;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.FailureReason;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ErrorAnalyzer {

    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("class file version (\\d+)\\.0.*recognizes.*class file versions up to (\\d+)\\.0");
    private static final Pattern API_VERSION_PATTERN = Pattern.compile("Unsupported API version (\\S+)");
    private static final Pattern MISSING_DEPS_PATTERN = Pattern.compile("Unknown/missing dependency plugins: \\[(.*?)\\]");
    private static final Pattern CLASS_NOT_FOUND_PATTERN = Pattern.compile("(?:ClassNotFoundException|NoClassDefFoundError):\\s*(\\S+)");
    private static final Pattern NO_SUCH_MEMBER_PATTERN = Pattern.compile("(?:NoSuchMethodError|NoSuchFieldError):\\s*(\\S+)");

    public record ErrorDetails(
            FailureReason reason,
            Map<String, String> placeholders,
            String plainDescription
    ) {}

    private ErrorAnalyzer() {}

    private record Signature(FailureReason reason, String logKey, List<String> markers) {

        boolean matches(String message) {
            for (String marker : markers) {
                if (message.contains(marker)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final List<Signature> SIGNATURES = List.of(
            new Signature(FailureReason.DATABASE_LOCKED, "erroranalyzer.database-locked",
                    List.of("database is locked", "SQLITE_BUSY")),
            new Signature(FailureReason.PORT_IN_USE, "erroranalyzer.port-in-use",
                    List.of("Address already in use", "BindException")),
            new Signature(FailureReason.CIRCULAR_DEPENDENCIES, "erroranalyzer.circular-deps",
                    List.of("Circular dependency")),
            new Signature(FailureReason.DUPLICATE_PLUGIN, "erroranalyzer.duplicate-plugin",
                    List.of("attempted to add duplicate plugin identifier", "is provided by both")),
            new Signature(FailureReason.PAPER_BOOTSTRAPPER, "erroranalyzer.paper-bootstrapper",
                    List.of("Plugin cannot register entrypoints other than PLUGIN during runtime",
                            "entrypoints other than PLUGIN")));

    public static ErrorDetails analyze(Throwable throwable, String pluginName, String fileName) {
        Throwable root = findRootCause(throwable);
        String msg = messageOf(root, throwable);

        Map<String, String> ph = new HashMap<>();
        if (pluginName != null) ph.put("plugin", pluginName);
        if (fileName != null) ph.put("file", fileName);

        ErrorDetails byPattern = matchPattern(root, msg, ph);
        if (byPattern != null) {
            return byPattern;
        }

        ErrorDetails byKeyword = matchKeyword(root, msg, ph);
        if (byKeyword != null) {
            return byKeyword;
        }

        ph.put("error", msg);
        return new ErrorDetails(FailureReason.LOAD_FAILED, ph, msg);
    }

    private static String messageOf(Throwable root, Throwable throwable) {
        String msg = extractMessage(root);
        if (msg.isBlank() || msg.equals(root.getClass().getSimpleName())) {
            String parentMsg = extractMessage(throwable);
            if (!parentMsg.isBlank()) {
                return parentMsg;
            }
        }
        return msg;
    }

    private static @Nullable ErrorDetails matchPattern(Throwable root, String msg, Map<String, String> ph) {
        Matcher java = JAVA_VERSION_PATTERN.matcher(msg);
        if (java.find()) {
            int required = Integer.parseInt(java.group(1)) - 44;
            int current = Integer.parseInt(java.group(2)) - 44;
            ph.put("required-java", String.valueOf(required));
            ph.put("current-java", String.valueOf(current));
            return detected(FailureReason.UNSUPPORTED_JAVA, ph, "erroranalyzer.unsupported-java",
                    "required", String.valueOf(required), "current", String.valueOf(current));
        }

        Matcher api = API_VERSION_PATTERN.matcher(msg);
        if (api.find()) {
            ph.put("api", api.group(1));
            return detected(FailureReason.UNSUPPORTED_API, ph, "erroranalyzer.unsupported-api", "api", api.group(1));
        }

        Matcher deps = MISSING_DEPS_PATTERN.matcher(msg);
        if (deps.find()) {
            ph.put("deps", deps.group(1));
            return detected(FailureReason.MISSING_DEPENDENCIES, ph, "erroranalyzer.missing-deps", "deps", deps.group(1));
        }

        Matcher notFound = CLASS_NOT_FOUND_PATTERN.matcher(msg);
        if (notFound.find()) {
            ph.put("class", notFound.group(1));
            return detected(FailureReason.CLASS_NOT_FOUND, ph, "erroranalyzer.class-not-found", "class", notFound.group(1));
        }

        Matcher member = NO_SUCH_MEMBER_PATTERN.matcher(msg);
        if (root instanceof NoSuchMethodError || root instanceof NoSuchFieldError || member.find()) {
            String name = member.find(0)
                    ? member.group(1)
                    : (!msg.isBlank() && !msg.equals(root.getClass().getSimpleName()) ? msg : "");
            ph.put("member", name);
            return detected(FailureReason.INCOMPATIBLE_CORE, ph, "erroranalyzer.incompatible-core", "member", name);
        }

        if (root instanceof IncompatibleClassChangeError) {
            return detected(FailureReason.INCOMPATIBLE_CLASS_CHANGE, ph, "erroranalyzer.incompatible-class-change");
        }
        return null;
    }

    private static @Nullable ErrorDetails matchKeyword(Throwable root, String msg, Map<String, String> ph) {
        if (msg.contains("Invalid plugin.yml") || msg.contains("paper-plugin.yml")
                || root.getClass().getSimpleName().equals("InvalidDescriptionException")) {
            return detected(FailureReason.INVALID_DESCRIPTION, ph, "erroranalyzer.invalid-description");
        }

        for (Signature signature : SIGNATURES) {
            if (signature.matches(msg)) {
                return detected(signature.reason(), ph, signature.logKey());
            }
        }
        return null;
    }

    private static ErrorDetails detected(FailureReason reason, Map<String, String> ph,
                                         String logKey, String... placeholders) {
        String description = LogCatalog.get(logKey, placeholders);
        ph.put("error", description);
        return new ErrorDetails(reason, ph, description);
    }

    public static String describe(@Nullable Throwable throwable, PlugManReloaded plugin) {
        if (throwable == null) return LogCatalog.get("erroranalyzer.unknown");
        ErrorDetails details = analyze(throwable, null, null);
        return details.plainDescription();
    }

    public static @Nullable Throwable findRootCause(@Nullable Throwable throwable) {
        if (throwable == null) return null;
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    public static String extractMessage(@Nullable Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        if (m != null && !m.isBlank()) return m;
        return t.getClass().getSimpleName();
    }
}

