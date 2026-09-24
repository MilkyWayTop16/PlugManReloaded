package ru.milkyway.plugmanreloaded;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigCompletenessTest {

    @Test
    void scanCodebaseForMissingConfigKeys() throws Exception {
        YamlConfiguration config;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        YamlConfiguration ruMessages;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("messages/ru-messages.yml")) {
            ruMessages = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        YamlConfiguration enMessages;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("messages/en-messages.yml")) {
            enMessages = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        Path srcRoot = Path.of("src/main/java");
        List<String> missingActions = new ArrayList<>();
        List<String> missingSettings = new ArrayList<>();

        Pattern sendActionPattern = Pattern.compile("sendAction\\s*\\([^,\"]*,\\s*\"([^\"]+)\"");
        Pattern sendActionShortPattern = Pattern.compile("sendAction\\s*\\(\\s*\"([^\"]+)\"");
        Pattern executeActionsPattern = Pattern.compile("executeActions\\s*\\([^,\"]*,\\s*\"([^\"]+)\"");
        Pattern pluginResultPattern = Pattern.compile("PluginResult\\s*\\.\\s*(?:ofError|ofSuccess)\\s*\\(\\s*\"([^\"]+)\"");
        Pattern configStringPattern = Pattern.compile("config\\s*\\.\\s*(?:getString|getStringList|getBoolean|getInt|contains)\\s*\\(\\s*\"([^\"]+)\"");

        Files.walk(srcRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);

                            checkAction(sendActionPattern.matcher(line), path, i + 1, missingActions, ruMessages);
                            checkAction(sendActionShortPattern.matcher(line), path, i + 1, missingActions, ruMessages);
                            checkAction(executeActionsPattern.matcher(line), path, i + 1, missingActions, ruMessages);
                            checkAction(pluginResultPattern.matcher(line), path, i + 1, missingActions, ruMessages);

                            checkAction(sendActionPattern.matcher(line), path, i + 1, missingActions, enMessages);
                            checkAction(sendActionShortPattern.matcher(line), path, i + 1, missingActions, enMessages);
                            checkAction(executeActionsPattern.matcher(line), path, i + 1, missingActions, enMessages);
                            checkAction(pluginResultPattern.matcher(line), path, i + 1, missingActions, enMessages);

                            checkConfigKey(configStringPattern.matcher(line), path, i + 1, missingSettings, config, ruMessages, enMessages);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        assertTrue(missingActions.isEmpty(), "Found missing actions in messages configs: " + missingActions);
        assertTrue(missingSettings.isEmpty(), "Found missing config keys in config files: " + missingSettings);
    }

    private void checkAction(Matcher matcher, Path path, int lineNum, List<String> report, YamlConfiguration config) {
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key.endsWith("-") || key.contains("{") || key.contains("%") || key.equals("name") || key.equals("bootstrapper") || key.equals("loader") || key.equals("main") || key.equals("depend") || key.equals("provides") || key.equals("softdepend") || key.equals("load") || key.equals("version")) {
                continue;
            }

            boolean found = config.contains("actions." + key) || config.contains("actions." + key + ".format")
                    || config.contains(key) || config.contains(key + ".format");

            if (!found && key.contains("canceled")) {
                String alt = key.replace("canceled", "cancelled");
                if (config.contains("actions." + alt) || config.contains(alt)) found = true;
            }
            if (!found && key.contains("cancelled")) {
                String alt = key.replace("cancelled", "canceled");
                if (config.contains("actions." + alt) || config.contains(alt)) found = true;
            }

            if (!found) {
                report.add(String.format("Action '%s' (line %d in %s)", key, lineNum, path.getFileName()));
            }
        }
    }

    private void checkConfigKey(Matcher matcher, Path path, int lineNum, List<String> report, YamlConfiguration... configs) {
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key.contains("{") || key.contains("%") || key.equals("name") || key.equals("bootstrapper") || key.equals("loader") || key.equals("main") || key.equals("depend") || key.equals("provides") || key.equals("softdepend") || key.equals("load") || key.equals("version") || key.startsWith("dependencies.")) {
                continue;
            }

            boolean found = false;
            for (YamlConfiguration c : configs) {
                if (c.contains(key)) {
                    found = true;
                    break;
                }
            }
            if (!found && !key.equals("actions.link.hover") && !key.equals("actions.download.units.thousand")
                    && !key.equals("actions.download.units.million") && !key.equals("actions.update.summary-entry.command")) {
                report.add(path.getFileName() + ":" + lineNum + " -> " + key);
            }
        }
    }
}
