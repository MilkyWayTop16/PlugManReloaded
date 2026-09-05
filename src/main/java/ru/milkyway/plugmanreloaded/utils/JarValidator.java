package ru.milkyway.plugmanreloaded.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.api.FailureReason;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.bridge.PlatformDetector;
import ru.milkyway.plugmanreloaded.update.ServerProfile;

public final class JarValidator {

    public enum PreFlightStatus {
        VALID,
        FILE_NOT_FOUND,
        CORRUPTED_JAR,
        NO_DESCRIPTOR,
        NAME_MISMATCH,
        INCOMPATIBLE_JAVA,
        MISSING_DEPENDENCIES,
        REQUIRES_COLD_RESTART,
        STARTUP_ONLY_LOAD
    }

    public record PreFlightReport(
            PreFlightStatus status,
            String declaredName,
            String declaredVersion,
            int requiredJava,
            int currentJava,
            List<String> missingDependencies,
            boolean isPaperPlugin,
            boolean hasBootstrapper,
            String errorMessage
    ) {
        public boolean isValid() {
            return status == PreFlightStatus.VALID;
        }

        public String pluginNameOr(File file) {
            return declaredName != null ? declaredName : file.getName();
        }

        public PluginResult toFailure(File file) {
            String name = pluginNameOr(file);
            return switch (status) {
                case INCOMPATIBLE_JAVA -> PluginResult.ofError(FailureReason.UNSUPPORTED_JAVA,
                        "plugin", name, "file", file.getName(),
                        "required-java", String.valueOf(requiredJava),
                        "current-java", String.valueOf(currentJava));
                case MISSING_DEPENDENCIES -> PluginResult.ofError(FailureReason.MISSING_DEPENDENCIES,
                        "plugin", name, "file", file.getName(),
                        "deps", String.join(", ", missingDependencies),
                        "dependencies", String.join(", ", missingDependencies));
                case CORRUPTED_JAR -> PluginResult.ofError(FailureReason.JAR_CORRUPTED,
                        "plugin", name, "file", file.getName());
                case FILE_NOT_FOUND -> PluginResult.ofError(FailureReason.FILE_NOT_FOUND,
                        "plugin", name, "file", file.getName());
                case NO_DESCRIPTOR -> PluginResult.ofError(FailureReason.INVALID_DESCRIPTION,
                        "plugin", name, "file", file.getName());
                default -> PluginResult.ofError(FailureReason.LOAD_FAILED,
                        "plugin", name, "file", file.getName(),
                        "error", errorMessage != null ? errorMessage : LogCatalog.get("jarvalidator.unknown"));
            };
        }

        public static PreFlightReport valid(String declaredName, String declaredVersion, int requiredJava, int currentJava, boolean isPaperPlugin, boolean hasBootstrapper) {
            return new PreFlightReport(PreFlightStatus.VALID, declaredName, declaredVersion, requiredJava, currentJava, Collections.emptyList(), isPaperPlugin, hasBootstrapper, null);
        }

        public static PreFlightReport error(PreFlightStatus status, String declaredName, String errorMessage) {
            return new PreFlightReport(status, declaredName, null, 0, 0, Collections.emptyList(), false, false, errorMessage);
        }
    }

    private JarValidator() {}

    public static PreFlightReport validatePreFlight(@Nullable File file, String expectedPluginName, boolean isReloadOrRestart) {
        if (file == null || !file.exists()) {
            return PreFlightReport.error(PreFlightStatus.FILE_NOT_FOUND, expectedPluginName, LogCatalog.get("jarvalidator.missing-file"));
        }
        if (!file.isFile()) {
            return PreFlightReport.error(PreFlightStatus.FILE_NOT_FOUND, expectedPluginName, LogCatalog.get("jarvalidator.path-not-file"));
        }
        if (file.length() < 100) {
            return PreFlightReport.error(PreFlightStatus.CORRUPTED_JAR, expectedPluginName, LogCatalog.get("jarvalidator.too-small", "size", String.valueOf(file.length())));
        }

        try (JarFile jar = new JarFile(file)) {
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            JarEntry paperPluginYml = jar.getJarEntry("paper-plugin.yml");
            boolean isPaper = paperPluginYml != null;

            if (pluginYml == null && paperPluginYml == null) {
                if (jar.getJarEntry("fabric.mod.json") != null) {
                    return PreFlightReport.error(PreFlightStatus.NO_DESCRIPTOR, expectedPluginName, LogCatalog.get("jarvalidator.fabric"));
                }
                if (jar.getJarEntry("quilt.mod.json") != null) {
                    return PreFlightReport.error(PreFlightStatus.NO_DESCRIPTOR, expectedPluginName, LogCatalog.get("jarvalidator.quilt"));
                }
                if (jar.getJarEntry("META-INF/neoforge.mods.toml") != null) {
                    return PreFlightReport.error(PreFlightStatus.NO_DESCRIPTOR, expectedPluginName, LogCatalog.get("jarvalidator.neoforge"));
                }
                if (jar.getJarEntry("META-INF/mods.toml") != null || jar.getJarEntry("mcmod.info") != null) {
                    return PreFlightReport.error(PreFlightStatus.NO_DESCRIPTOR, expectedPluginName, LogCatalog.get("jarvalidator.forge"));
                }
                if (jar.getJarEntry("velocity-plugin.json") != null) {
                    return PreFlightReport.error(PreFlightStatus.NO_DESCRIPTOR, expectedPluginName, LogCatalog.get("jarvalidator.velocity"));
                }
                if (jar.getJarEntry("bungee.yml") != null || jar.getJarEntry("waterfall.yml") != null) {
                    return PreFlightReport.error(PreFlightStatus.NO_DESCRIPTOR, expectedPluginName, LogCatalog.get("jarvalidator.bungeecord"));
                }
                return PreFlightReport.error(PreFlightStatus.NO_DESCRIPTOR, expectedPluginName, LogCatalog.get("jarvalidator.no-descriptor"));
            }

            JarEntry descriptorEntry = pluginYml != null ? pluginYml : paperPluginYml;
            String rawDescriptor;
            try (InputStream is = jar.getInputStream(descriptorEntry)) {
                rawDescriptor = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            YamlConfiguration yaml = loadSafeYaml(rawDescriptor);

            String declaredName = yaml.getString("name");
            if (declaredName == null || declaredName.isBlank()) {
                declaredName = extractRawField(rawDescriptor, "name");
            }
            if (declaredName == null || declaredName.isBlank()) {
                return PreFlightReport.error(PreFlightStatus.NO_DESCRIPTOR, expectedPluginName, LogCatalog.get("jarvalidator.no-name"));
            }
            declaredName = declaredName.trim();

            String declaredVersion = yaml.getString("version");
            if (declaredVersion == null || declaredVersion.isBlank()) {
                declaredVersion = extractRawField(rawDescriptor, "version");
            }
            if (declaredVersion == null || declaredVersion.isBlank()) {
                declaredVersion = "1.0";
            }

            if (expectedPluginName != null && !expectedPluginName.isBlank()) {
                if (!declaredName.equalsIgnoreCase(expectedPluginName.trim())) {
                    return PreFlightReport.error(PreFlightStatus.NAME_MISMATCH, declaredName,
                            LogCatalog.get("jarvalidator.name-mismatch", "declared", declaredName, "expected", expectedPluginName));
                }
            }

            int reqJava = readRequiredJavaVersion(file);
            int currJava = ServerProfile.detectJavaVersion();
            if (reqJava > 0 && currJava > 0 && reqJava > currJava) {
                return new PreFlightReport(PreFlightStatus.INCOMPATIBLE_JAVA, declaredName, declaredVersion, reqJava, currJava,
                        Collections.emptyList(), isPaper, false,
                        LogCatalog.get("jarvalidator.incompatible-java", "required", String.valueOf(reqJava), "current", String.valueOf(currJava)));
            }

            boolean hasBoot = hasPaperBootstrapper(file);
            if (isReloadOrRestart && (hasBoot || (isPaper && PlatformDetector.isModernPaper()))) {
                return new PreFlightReport(PreFlightStatus.REQUIRES_COLD_RESTART, declaredName, declaredVersion, reqJava, currJava,
                        Collections.emptyList(), isPaper, true,
                        LogCatalog.get("jarvalidator.requires-cold-restart"));
            }

            List<String> missing = readMissingDependencies(file);
            if (!missing.isEmpty()) {
                return new PreFlightReport(PreFlightStatus.MISSING_DEPENDENCIES, declaredName, declaredVersion, reqJava, currJava,
                        missing, isPaper, hasBoot, LogCatalog.get("jarvalidator.missing-dependencies", "deps", String.join(", ", missing)));
            }

            return PreFlightReport.valid(declaredName, declaredVersion, reqJava, currJava, isPaper, hasBoot);
        } catch (ZipException e) {
            return PreFlightReport.error(PreFlightStatus.CORRUPTED_JAR, expectedPluginName, LogCatalog.get("jarvalidator.corrupted-jar", "error", e.getMessage() != null ? e.getMessage() : ""));
        } catch (IOException e) {
            return PreFlightReport.error(PreFlightStatus.CORRUPTED_JAR, expectedPluginName, LogCatalog.get("jarvalidator.io-error", "error", e.getMessage() != null ? e.getMessage() : ""));
        } catch (Throwable t) {
            return PreFlightReport.error(PreFlightStatus.CORRUPTED_JAR, expectedPluginName, LogCatalog.get("jarvalidator.unexpected", "error", t.getMessage() != null ? t.getMessage() : ""));
        }
    }

    public static boolean isValidPluginJar(@Nullable File file) {
        if (file == null) {
            Log.debug("jarvalidator.null-file");
            return false;
        }
        if (!file.exists()) {
            Log.debug("jarvalidator.file-missing", "file", file.getName());
            return false;
        }
        if (!file.isFile()) {
            Log.debug("jarvalidator.not-a-file", "file", file.getName());
            return false;
        }
        if (file.length() < 100) {
            Log.debug("jarvalidator.file-too-small", "file", file.getName(), "size", String.valueOf(file.length()));
            return false;
        }

        try (JarFile jar = new JarFile(file)) {
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            JarEntry paperPluginYml = jar.getJarEntry("paper-plugin.yml");
            boolean valid = pluginYml != null || paperPluginYml != null;
            if (!valid) {
                Log.debug("jarvalidator.no-descriptor", "file", file.getName());
            }
            return valid;
        } catch (ZipException e) {
            Log.debug("jarvalidator.corrupted-archive", "file", file.getName(), "error", e.getMessage());
            return false;
        } catch (IOException e) {
            Log.debug("jarvalidator.io-error", "file", file.getName(), "error", e.getMessage());
            return false;
        } catch (Throwable t) {
            Log.debug("jarvalidator.unexpected-validation-error", "file", file.getName(), "error", t.getMessage());
            return false;
        }
    }

    public static YamlConfiguration loadSafeYaml(@Nullable InputStream stream) {
        if (stream == null) return new YamlConfiguration();
        try {
            String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return loadSafeYaml(raw);
        } catch (Throwable t) {
            Log.debug("jarvalidator.descriptor-read-failed", t);
            return new YamlConfiguration();
        }
    }

    public static YamlConfiguration loadSafeYaml(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return new YamlConfiguration();
        try {
            String sanitized = raw.contains("!@") ? raw.replaceAll("!@[A-Za-z0-9_.-]+", "") : raw;
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(sanitized);
            return yaml;
        } catch (Throwable t) {
            Log.debug("jarvalidator.yaml-parse-error", "error", t.getMessage());
            return new YamlConfiguration();
        }
    }

    public static @Nullable String extractRawField(@Nullable String raw, String fieldName) {
        if (raw == null || raw.isBlank()) return null;
        var matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(fieldName) + "\\s*:\\s*['\"]?([^'\"\\r\\n#]+)").matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    public static @Nullable String readPluginName(@Nullable File file) {
        if (file == null || !file.isFile()) return null;
        try (JarFile jar = new JarFile(file)) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) entry = jar.getJarEntry("paper-plugin.yml");
            if (entry == null) return null;

            try (InputStream is = jar.getInputStream(entry)) {
                String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                YamlConfiguration yaml = loadSafeYaml(raw);
                String name = yaml.getString("name");
                if (name == null || name.isBlank()) {
                    name = extractRawField(raw, "name");
                }
                return (name != null && !name.isBlank()) ? name.trim() : null;
            }
        } catch (Throwable t) {
            Log.debug("jarvalidator.name-read-failed", "file", file.getName(), "error", t.getMessage());
            return null;
        }
    }

    public static boolean isPaperPlugin(@Nullable File file) {
        if (file == null || !file.isFile()) return false;
        try (JarFile jar = new JarFile(file)) {
            return jar.getJarEntry("paper-plugin.yml") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final Map<String, Boolean> BOOTSTRAPPER_CACHE = new ConcurrentHashMap<>();

    public static boolean hasPaperBootstrapper(@Nullable File file) {
        if (file == null || !file.isFile()) return false;

        String cacheKey = file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length();
        Boolean cached = BOOTSTRAPPER_CACHE.get(cacheKey);
        if (cached != null) return cached;

        try (JarFile jar = new JarFile(file)) {
            JarEntry paperEntry = jar.getJarEntry("paper-plugin.yml");
            if (paperEntry == null) {
                BOOTSTRAPPER_CACHE.put(cacheKey, false);
                return false;
            }
            if (PlatformDetector.isModernPaper()) {
                BOOTSTRAPPER_CACHE.put(cacheKey, true);
                return true;
            }
            try (InputStream is = jar.getInputStream(paperEntry)) {
                YamlConfiguration yaml = loadSafeYaml(is);
                String bootstrapper = yaml.getString("bootstrapper");
                String loader = yaml.getString("loader");
                boolean hasBoot = (bootstrapper != null && !bootstrapper.isBlank()) || (loader != null && !loader.isBlank());
                BOOTSTRAPPER_CACHE.put(cacheKey, hasBoot);
                return hasBoot;
            }
        } catch (Throwable t) {
            BOOTSTRAPPER_CACHE.put(cacheKey, false);
            return false;
        }
    }

    public static int readRequiredJavaVersion(@Nullable File file) {
        if (file == null || !file.isFile()) return 0;
        try (JarFile jar = new JarFile(file)) {
            List<String> entryClasses = new ArrayList<>();

            JarEntry paperEntry = jar.getJarEntry("paper-plugin.yml");
            if (paperEntry != null) {
                try (InputStream is = jar.getInputStream(paperEntry)) {
                    String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    YamlConfiguration yaml = loadSafeYaml(raw);
                    addIfNotEmpty(entryClasses, yaml.getString("main"));
                    addIfNotEmpty(entryClasses, yaml.getString("bootstrapper"));
                    addIfNotEmpty(entryClasses, yaml.getString("loader"));
                    if (entryClasses.isEmpty()) {
                        addIfNotEmpty(entryClasses, extractRawField(raw, "main"));
                        addIfNotEmpty(entryClasses, extractRawField(raw, "bootstrapper"));
                        addIfNotEmpty(entryClasses, extractRawField(raw, "loader"));
                    }
                }
            }

            JarEntry bukkitEntry = jar.getJarEntry("plugin.yml");
            if (bukkitEntry != null) {
                try (InputStream is = jar.getInputStream(bukkitEntry)) {
                    String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    YamlConfiguration yaml = loadSafeYaml(raw);
                    addIfNotEmpty(entryClasses, yaml.getString("main"));
                    if (entryClasses.isEmpty()) {
                        addIfNotEmpty(entryClasses, extractRawField(raw, "main"));
                    }
                }
            }

            for (String mainClass : entryClasses) {
                if (mainClass == null || mainClass.isBlank()) continue;
                String classPath = mainClass.trim().replace('.', '/') + ".class";
                JarEntry classEntry = jar.getJarEntry(classPath);
                if (classEntry != null) {
                    try (InputStream is = jar.getInputStream(classEntry)) {
                        int v = readClassBytecodeVersion(is);
                        if (v > 0) return v;
                    }
                }
            }

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (!e.isDirectory() && name.endsWith(".class")
                        && !name.startsWith("META-INF/")
                        && !name.endsWith("module-info.class")
                        && !name.endsWith("package-info.class")) {
                    try (InputStream is = jar.getInputStream(e)) {
                        int v = readClassBytecodeVersion(is);
                        if (v > 0) return v;
                    }
                }
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void addIfNotEmpty(List<String> list, String value) {
        if (value != null && !value.isBlank() && !list.contains(value.trim())) {
            list.add(value.trim());
        }
    }

    private static int readClassBytecodeVersion(InputStream is) throws IOException {
        byte[] header = is.readNBytes(8);
        if (header.length == 8 && (header[0] & 0xFF) == 0xCA && (header[1] & 0xFF) == 0xFE
                && (header[2] & 0xFF) == 0xBA && (header[3] & 0xFF) == 0xBE) {
            int major = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
            return major > 44 ? major - 44 : 0;
        }
        return 0;
    }

    public static List<String> readDeclaredDependencies(@Nullable File file) {
        if (file == null || !file.isFile()) return Collections.emptyList();
        try (JarFile jar = new JarFile(file)) {
            List<String> required = new ArrayList<>();

            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            if (pluginYml != null) {
                try (InputStream is = jar.getInputStream(pluginYml)) {
                    YamlConfiguration yaml = loadSafeYaml(is);
                    if (yaml.isList("depend")) {
                        for (String dep : yaml.getStringList("depend")) {
                            if (dep != null && !dep.isBlank() && !required.contains(dep.trim())) {
                                required.add(dep.trim());
                            }
                        }
                    } else if (yaml.isString("depend")) {
                        String s = yaml.getString("depend");
                        if (s != null && !s.isBlank() && !required.contains(s.trim())) {
                            required.add(s.trim());
                        }
                    }
                }
            }

            JarEntry paperYml = jar.getJarEntry("paper-plugin.yml");
            if (paperYml != null) {
                try (InputStream is = jar.getInputStream(paperYml)) {
                    YamlConfiguration yaml = loadSafeYaml(is);
                    if (yaml.isConfigurationSection("dependencies.server")) {
                        for (String key : yaml.getConfigurationSection("dependencies.server").getKeys(false)) {
                            boolean isRequired = yaml.getBoolean("dependencies.server." + key + ".required", true);
                            if (isRequired && !required.contains(key.trim())) {
                                required.add(key.trim());
                            }
                        }
                    }
                    if (yaml.isConfigurationSection("dependencies.bootstrap")) {
                        for (String key : yaml.getConfigurationSection("dependencies.bootstrap").getKeys(false)) {
                            boolean isRequired = yaml.getBoolean("dependencies.bootstrap." + key + ".required", true);
                            if (isRequired && !required.contains(key.trim())) {
                                required.add(key.trim());
                            }
                        }
                    }
                }
            }

            return required;
        } catch (Throwable t) {
            Log.debug("jarvalidator.dependencies-read-failed", t, "file", file.getName());
            return Collections.emptyList();
        }
    }

    public static List<String> readDeclaredProvides(@Nullable File file) {
        if (file == null || !file.isFile()) return Collections.emptyList();
        try (JarFile jar = new JarFile(file)) {
            List<String> provides = new ArrayList<>();
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            if (pluginYml != null) {
                try (InputStream is = jar.getInputStream(pluginYml)) {
                    YamlConfiguration yaml = loadSafeYaml(is);
                    if (yaml.isList("provides")) {
                        for (String p : yaml.getStringList("provides")) {
                            if (p != null && !p.isBlank() && !provides.contains(p.trim())) {
                                provides.add(p.trim());
                            }
                        }
                    } else if (yaml.isString("provides")) {
                        String s = yaml.getString("provides");
                        if (s != null && !s.isBlank() && !provides.contains(s.trim())) {
                            provides.add(s.trim());
                        }
                    }
                }
            }
            JarEntry paperYml = jar.getJarEntry("paper-plugin.yml");
            if (paperYml != null) {
                try (InputStream is = jar.getInputStream(paperYml)) {
                    YamlConfiguration yaml = loadSafeYaml(is);
                    if (yaml.isList("provides")) {
                        for (String p : yaml.getStringList("provides")) {
                            if (p != null && !p.isBlank() && !provides.contains(p.trim())) {
                                provides.add(p.trim());
                            }
                        }
                    }
                }
            }
            return provides;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static List<String> readDeclaredSoftDependencies(@Nullable File file) {
        if (file == null || !file.isFile()) return Collections.emptyList();
        try (JarFile jar = new JarFile(file)) {
            List<String> optional = new ArrayList<>();
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            if (pluginYml != null) {
                try (InputStream is = jar.getInputStream(pluginYml)) {
                    YamlConfiguration yaml = loadSafeYaml(is);
                    if (yaml.isList("softdepend")) {
                        for (String dep : yaml.getStringList("softdepend")) {
                            if (dep != null && !dep.isBlank() && !optional.contains(dep.trim())) {
                                optional.add(dep.trim());
                            }
                        }
                    } else if (yaml.isString("softdepend")) {
                        String s = yaml.getString("softdepend");
                        if (s != null && !s.isBlank() && !optional.contains(s.trim())) {
                            optional.add(s.trim());
                        }
                    }
                }
            }
            JarEntry paperYml = jar.getJarEntry("paper-plugin.yml");
            if (paperYml != null) {
                try (InputStream is = jar.getInputStream(paperYml)) {
                    YamlConfiguration yaml = loadSafeYaml(is);
                    if (yaml.isConfigurationSection("dependencies.server")) {
                        for (String key : yaml.getConfigurationSection("dependencies.server").getKeys(false)) {
                            boolean isRequired = yaml.getBoolean("dependencies.server." + key + ".required", true);
                            if (!isRequired && !optional.contains(key.trim())) {
                                optional.add(key.trim());
                            }
                        }
                    }
                }
            }
            return optional;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static boolean hasWorldGenerator(@Nullable File file) {
        if (file == null || !file.isFile()) return false;
        try (JarFile jar = new JarFile(file)) {
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            if (pluginYml != null) {
                try (InputStream is = jar.getInputStream(pluginYml)) {
                    YamlConfiguration yaml = loadSafeYaml(is);
                    String load = yaml.getString("load");
                    if ("STARTUP".equalsIgnoreCase(load)) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isDependencySatisfied(@Nullable String dependencyName) {
        if (dependencyName == null || dependencyName.isBlank()) return true;
        String clean = dependencyName.trim();
        if (Bukkit.getPluginManager().getPlugin(clean) != null) {
            return true;
        }
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p != null) {
                if (p.getName().equalsIgnoreCase(clean)) return true;
                for (String provided : p.getDescription().getProvides()) {
                    if (provided != null && provided.equalsIgnoreCase(clean)) {
                        return true;
                    }
                }
                try {
                    Object meta = ReflectionHelper.invokeMethod(p, "getPluginMeta");
                    if (meta != null) {
                        List<?> provided = ReflectionHelper.invokeMethod(meta, "getProvidedPlugins");
                        if (provided != null) {
                            for (Object o : provided) {
                                if (o != null && o.toString().equalsIgnoreCase(clean)) {
                                    return true;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    public static List<String> readMissingDependencies(File file) {
        List<String> required = readDeclaredDependencies(file);
        if (required.isEmpty()) return Collections.emptyList();

        List<String> missing = new ArrayList<>();
        for (String dep : required) {
            if (dep == null || dep.isBlank()) continue;
            String cleanDep = dep.trim();
            if (!isDependencySatisfied(cleanDep)) {
                missing.add(cleanDep);
            }
        }
        return missing;
    }

    public static boolean waitForFileWrite(File file, int maxAttempts, long intervalMs) {
        for (int i = 0; i < maxAttempts; i++) {
            if (isValidPluginJar(file)) {
                return true;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return isValidPluginJar(file);
    }
}

