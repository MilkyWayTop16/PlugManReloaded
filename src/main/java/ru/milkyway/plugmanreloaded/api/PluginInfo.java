package ru.milkyway.plugmanreloaded.api;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.utils.JarValidator;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.PluginMetaHelper;

public record PluginInfo(
        String name,
        String version,
        String mainClass,
        List<String> authors,
        String description,
        String website,
        List<String> depends,
        List<String> softDepends,
        Map<String, Map<String, Object>> commands,
        Set<String> permissions,
        File file,
        long fileSizeBytes,
        boolean enabled,
        boolean paperPlugin,
        boolean hasBootstrapper
) {

    public String fileSizeFormatted() {
        return PluginMetaHelper.formatFileSize(fileSizeBytes);
    }

    public static @Nullable PluginInfo fromPlugin(@Nullable Plugin plugin, File file, boolean isPaper) {
        if (plugin == null) return null;
        PluginDescriptionFile desc = plugin.getDescription();

        long size = (file != null && file.exists()) ? file.length() : 0;
        Set<String> perms = desc.getPermissions().stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());

        boolean hasBoot = file != null && JarValidator.hasPaperBootstrapper(file);

        return new PluginInfo(
                plugin.getName(),
                desc.getVersion(),
                desc.getMain(),
                desc.getAuthors(),
                desc.getDescription() != null ? desc.getDescription() : "",
                desc.getWebsite() != null ? desc.getWebsite() : "",
                desc.getDepend(),
                desc.getSoftDepend(),
                desc.getCommands(),
                perms,
                file,
                size,
                plugin.isEnabled(),
                isPaper,
                hasBoot
        );
    }

    public static @Nullable PluginInfo fromJarFile(@Nullable File file) {
        if (file == null || !file.exists()) return null;

        Descriptor descriptor = readDescriptor(file);
        return new PluginInfo(
                descriptor.name(),
                descriptor.version(),
                descriptor.main(),
                descriptor.authors(),
                descriptor.description(),
                descriptor.website(),
                descriptor.depends(),
                descriptor.softDepends(),
                Collections.emptyMap(),
                Collections.emptySet(),
                file,
                file.length(),
                false,
                descriptor.paperPlugin(),
                descriptor.hasBootstrapper()
        );
    }

    private record Descriptor(String name, String version, String main, List<String> authors,
                              String description, String website, List<String> depends,
                              List<String> softDepends, boolean paperPlugin, boolean hasBootstrapper) {

        static Descriptor unknown(File file, boolean paperPlugin) {
            return new Descriptor(file.getName(), "1.0", "", Collections.emptyList(),
                    "", "", Collections.emptyList(), Collections.emptyList(), paperPlugin, false);
        }
    }

    private static Descriptor readDescriptor(File file) {
        boolean isPaper = false;
        try (JarFile jar = new JarFile(file)) {
            JarEntry paperEntry = jar.getJarEntry("paper-plugin.yml");
            isPaper = paperEntry != null;

            JarEntry entry = isPaper ? paperEntry : jar.getJarEntry("plugin.yml");
            if (entry == null) {
                return Descriptor.unknown(file, false);
            }

            try (InputStream is = jar.getInputStream(entry)) {
                return readYaml(ru.milkyway.plugmanreloaded.utils.JarValidator.loadSafeYaml(is), file, isPaper);
            }
        } catch (Exception | LinkageError t) {
            Log.debug("plugininfo.plugin-yml-read-failed", t);
            return Descriptor.unknown(file, isPaper);
        }
    }

    private static Descriptor readYaml(YamlConfiguration yaml, File file, boolean isPaper) {
        Descriptor fallback = Descriptor.unknown(file, isPaper);
        return new Descriptor(
                yaml.getString("name", fallback.name()),
                yaml.getString("version", fallback.version()),
                yaml.getString("main", fallback.main()),
                readAuthors(yaml),
                yaml.getString("description", fallback.description()),
                yaml.getString("website", fallback.website()),
                yaml.isList("depend") ? yaml.getStringList("depend") : Collections.emptyList(),
                yaml.isList("softdepend") ? yaml.getStringList("softdepend") : Collections.emptyList(),
                isPaper,
                isPaper && hasBootstrapperEntry(yaml)
        );
    }

    private static List<String> readAuthors(YamlConfiguration yaml) {
        if (yaml.isList("authors")) {
            return yaml.getStringList("authors");
        }
        String single = yaml.getString("author");
        return single != null ? List.of(single) : Collections.emptyList();
    }

    private static boolean hasBootstrapperEntry(YamlConfiguration yaml) {
        String bootstrapper = yaml.getString("bootstrapper");
        String loader = yaml.getString("loader");
        return (bootstrapper != null && !bootstrapper.isBlank())
                || (loader != null && !loader.isBlank());
    }

}

