package ru.milkyway.plugmanreloaded.update.input;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.update.SourceCatalog;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class UserCatalogWriter {

    private UserCatalogWriter() {
    }

    public static synchronized boolean write(File userCatalogFile, String pluginName, String mainClass, SourceCatalog.CatalogSource newSource) {
        return write(userCatalogFile, pluginName, mainClass, newSource, "ru");
    }

    public static synchronized boolean write(@Nullable File userCatalogFile, String pluginName, String mainClass, SourceCatalog.CatalogSource newSource, String language) {
        if (userCatalogFile == null || pluginName == null || pluginName.isBlank() || newSource == null) {
            return false;
        }

        File tempFile = new File(userCatalogFile.getParentFile(), userCatalogFile.getName() + ".tmp");
        try {
            if (!userCatalogFile.exists()) {
                File parent = userCatalogFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(userCatalogFile);
            if ((yaml.options().getHeader() == null || yaml.options().getHeader().isEmpty())) {
                String templateResource = "en".equalsIgnoreCase(language) ? "sources-custom-en.yml" : "sources-custom.yml";
                InputStream headerStream = UserCatalogWriter.class.getClassLoader().getResourceAsStream(templateResource);
                if (headerStream == null) {
                    headerStream = UserCatalogWriter.class.getClassLoader().getResourceAsStream("sources-custom.yml");
                }
                try (InputStream stream = headerStream) {
                    if (stream != null) {
                        YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                        yaml.options().setHeader(def.options().getHeader());
                        yaml.options().setFooter(def.options().getFooter());
                    }
                } catch (Exception e) {
                    Log.debug("usercatalogwriter.header-read-failed", e);
                }
            }

            String base = "plugins." + pluginName;

            if (mainClass != null && !mainClass.isBlank()) {
                yaml.set(base + ".main", mainClass);
            }

            List<Map<?, ?>> existingRaw = yaml.getMapList(base + ".sources");
            List<Map<String, Object>> combined = new ArrayList<>();

            Map<String, Object> targetMap = new LinkedHashMap<>();
            targetMap.put("id", newSource.sourceId());
            targetMap.put("ref", newSource.ref());
            if (newSource.url() != null && !newSource.url().isBlank()) {
                targetMap.put("url", newSource.url());
            }
            if (newSource.options() != null && !newSource.options().isEmpty()) {
                targetMap.putAll(newSource.options());
            }

            combined.add(targetMap);

            String targetId = newSource.sourceId().toLowerCase(Locale.ROOT);
            String targetRef = newSource.ref().toLowerCase(Locale.ROOT);

            for (Map<?, ?> raw : existingRaw) {
                Object rawId = raw.get("id");
                Object rawRef = raw.get("ref");
                String idStr = rawId != null ? String.valueOf(rawId).toLowerCase(Locale.ROOT) : "";
                String refStr = rawRef != null ? String.valueOf(rawRef).toLowerCase(Locale.ROOT) : "";

                if (idStr.equals(targetId) && refStr.equals(targetRef)) {
                    continue;
                }

                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        copy.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                combined.add(copy);
            }

            yaml.set(base + ".sources", combined);

            yaml.save(tempFile);
            try {
                Files.move(tempFile.toPath(), userCatalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception fallback) {
                Log.debug("usercatalogwriter.atomic-move-unsupported", fallback);
                Files.move(tempFile.toPath(), userCatalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            Log.warn("usercatalogwriter.save-failed", t, "plugin", pluginName, "file", userCatalogFile.getName());
            try {
                Files.deleteIfExists(tempFile.toPath());
            } catch (Exception cleanup) {
                Log.debug("usercatalogwriter.temp-file-delete-failed", cleanup, "file", tempFile.getName());
            }
            return false;
        }
    }
}

