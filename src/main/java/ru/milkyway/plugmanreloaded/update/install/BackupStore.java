package ru.milkyway.plugmanreloaded.update.install;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.MetaspaceCleanup;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class BackupStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;
    private final int keepDays;
    private final int maxPerPlugin;

    public BackupStore(File pluginsDirectory, int keepDays, int maxPerPlugin) {
        this.directory = pluginsDirectory.toPath().resolve(".plugmanreloaded-backups");
        this.keepDays = Math.max(1, keepDays);
        this.maxPerPlugin = Math.max(1, maxPerPlugin);
    }

    public BackupStore(File pluginsDirectory, int maxPerPlugin) {
        this(pluginsDirectory, 1, maxPerPlugin);
    }

    public @Nullable Path backup(String pluginName, String version, File jar) {
        try {
            Files.createDirectories(directory);
            String safeVersion = sanitize(version);
            String fileName = sanitize(pluginName) + "-" + safeVersion + "-" + LocalDateTime.now().format(STAMP) + ".jar";
            Path destination = directory.resolve(fileName);

            Files.copy(jar.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setLastModifiedTime(destination, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (Throwable t) {
                Log.debug("backupstore.mtime-update-failed", t, "file", destination.getFileName().toString());
            }
            prune(pluginName);
            return destination;
        } catch (Throwable t) {
            Log.warn("backupstore.backup-failed", t, "plugin", pluginName, "error", t.getMessage());
            return null;
        }
    }

    public @Nullable Path backupFolder(String pluginName, @Nullable File dataFolder) {
        if (dataFolder == null || !dataFolder.exists() || !dataFolder.isDirectory()) {
            return null;
        }
        try {
            Files.createDirectories(directory);
            String fileName = sanitize(pluginName) + "-data-" + LocalDateTime.now().format(STAMP) + ".zip";
            Path destination = directory.resolve(fileName);

            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(destination))) {
                Path sourcePath = dataFolder.toPath();
                Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String entryName = sourcePath.relativize(file).toString().replace('\\', '/');
                        try {
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(file, zos);
                            zos.closeEntry();
                        } catch (Throwable t) {
                            Log.debug("backupstore.unreadable-file-skipped", t, "file", entryName, "error", t.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        Log.debug("backupstore.file-access-error", exc, "file", file.getFileName().toString(), "error", exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (!dir.equals(sourcePath)) {
                            String entryName = sourcePath.relativize(dir).toString().replace('\\', '/') + "/";
                            zos.putNextEntry(new ZipEntry(entryName));
                            zos.closeEntry();
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            try {
                Files.setLastModifiedTime(destination, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (Throwable t) {
                Log.debug("backupstore.mtime-update-failed", t, "file", destination.getFileName().toString());
            }
            prune(pluginName);
            return destination;
        } catch (Throwable t) {
            Log.warn("backupstore.folder-backup-failed", t, "plugin", pluginName, "error", t.getMessage());
            return null;
        }
    }

    public boolean restore(@Nullable Path backup, File target) {
        if (backup == null) return false;
        for (int i = 0; i < 4; i++) {
            try {
                Files.copy(backup, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Throwable t) {
                Log.debug("backupstore.restore-attempt-failed", t, "attempt", String.valueOf(i + 1), "file", target.getName());
                MetaspaceCleanup.runNow();
                try {
                    Thread.sleep(50L * (i + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Log.error("backupstore.restore-failed", "backup", backup.getFileName().toString(), "target", target.getName());
        return false;
    }

    public boolean restoreFolder(@Nullable Path zipBackup, File targetDataFolder) {
        if (zipBackup == null || !Files.exists(zipBackup) || targetDataFolder == null) {
            return false;
        }
        try {
            if (!targetDataFolder.exists()) {
                targetDataFolder.mkdirs();
            }
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipBackup))) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                Path targetRoot = targetDataFolder.toPath().normalize();
                while ((entry = zis.getNextEntry()) != null) {
                    Path entryPath = targetRoot.resolve(entry.getName()).normalize();
                    if (!entryPath.startsWith(targetRoot)) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Path parent = entryPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        try (var os = Files.newOutputStream(entryPath)) {
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                os.write(buffer, 0, len);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }
            return true;
        } catch (Throwable t) {
            Log.error("backupstore.folder-restore-failed", t, "backup", zipBackup.getFileName().toString(), "error", t.getMessage());
            return false;
        }
    }

    public void pruneAll() {
        if (!Files.exists(directory)) return;
        long cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(keepDays);
        try (var stream = Files.list(directory)) {
            stream.forEach(path -> {
                try {
                    long backupTime = parseBackupTimestamp(path);
                    if (backupTime < cutoffMillis) {
                        Files.deleteIfExists(path);
                    }
                } catch (Throwable t) {
                    Log.debug("backupstore.backup-check-failed", t, "file", path.getFileName().toString());
                }
            });
        } catch (Throwable t) {
            Log.debug("backupstore.prune-all-failed", t);
        }
    }

    private void prune(String pluginName) {
        if (!Files.exists(directory)) return;

        String prefix = sanitize(pluginName).toLowerCase(Locale.ROOT) + "-";
        List<Path> owned = new ArrayList<>();
        long cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(keepDays);

        try (var stream = Files.list(directory)) {
            stream.forEach(path -> {
                try {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.startsWith(prefix)) {
                        owned.add(path);
                    }
                    long backupTime = parseBackupTimestamp(path);
                    if (backupTime < cutoffMillis) {
                        Files.deleteIfExists(path);
                    }
                } catch (Throwable t) {
                    Log.debug("backupstore.backup-check-failed", t, "file", path.getFileName().toString());
                }
            });
        } catch (Throwable t) {
            Log.debug("backupstore.list-failed", t, "plugin", pluginName);
            return;
        }

        List<Path> remainingOwned = new ArrayList<>();
        for (Path p : owned) {
            if (Files.exists(p)) remainingOwned.add(p);
        }

        if (remainingOwned.size() > maxPerPlugin) {
            remainingOwned.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (int i = 0; i < remainingOwned.size() - maxPerPlugin; i++) {
                try {
                    Files.deleteIfExists(remainingOwned.get(i));
                } catch (Throwable t) {
                    Log.debug("backupstore.old-copy-delete-failed", t, "file", remainingOwned.get(i).getFileName().toString());
                }
            }
        }
    }

    private long parseBackupTimestamp(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".jar") || name.endsWith(".zip")) {
            int lastDash = name.lastIndexOf('-');
            if (lastDash != -1 && lastDash + 15 + 4 == name.length()) {
                String stampStr = name.substring(lastDash + 1, name.length() - 4);
                try {
                    LocalDateTime ldt = LocalDateTime.parse(stampStr, STAMP);
                    return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (Throwable t) {
                    Log.debug("backupstore.timestamp-unparseable", t, "name", name);
                }
            }
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Throwable t) {
            return System.currentTimeMillis();
        }
    }

    private static String sanitize(@Nullable String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}

