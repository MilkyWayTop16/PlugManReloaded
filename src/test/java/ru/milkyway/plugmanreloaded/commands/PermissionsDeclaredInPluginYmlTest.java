package ru.milkyway.plugmanreloaded.commands;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionsDeclaredInPluginYmlTest {

    private static final String QUOTE = String.valueOf((char) 34);
    private static final String DOT = String.valueOf((char) 92) + ".";
    private static final String WS = String.valueOf((char) 92) + "s";

    @Test
    void everyPermissionUsedInCodeIsDeclaredUnderAdmin() throws Exception {
        Pattern used = Pattern.compile(QUOTE + "(plugmanreloaded" + DOT + "[a-z.]+)" + QUOTE);
        Set<String> inCode = new TreeSet<>();

        try (Stream<Path> stream = Files.walk(Path.of("src/main/java"))) {
            for (Path path : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = used.matcher(Files.readString(path, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    inCode.add(matcher.group(1));
                }
            }
        }
        inCode.remove("plugmanreloaded.admin");

        String yml = Files.readString(Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8);
        Set<String> declared = new LinkedHashSet<>();
        Matcher declaredMatcher = Pattern.compile("(plugmanreloaded" + DOT + "[a-z.]+)" + WS + "*:" + WS + "*true").matcher(yml);
        while (declaredMatcher.find()) {
            declared.add(declaredMatcher.group(1));
        }

        Set<String> missing = new TreeSet<>(inCode);
        missing.removeAll(declared);

        assertEquals(Set.of(), missing,
                "Эти права требуются в коде, но не объявлены детьми plugmanreloaded.admin в plugin.yml. "
                        + "Администратор с plugmanreloaded.admin (без опки) увидит команду в подсказках, "
                        + "но получит отказ при выполнении: " + missing);
    }
}
