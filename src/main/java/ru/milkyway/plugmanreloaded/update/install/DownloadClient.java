package ru.milkyway.plugmanreloaded.update.install;

import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.utils.HashUtil;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class DownloadClient {

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final long MAX_SIZE_BYTES = 150L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private DownloadClient() {}

    public record Downloaded(Path file, String sha1, String sha256, long size) {}

    static boolean isInsecureDowngrade(String originalUrl, String candidateUrl) {
        try {
            boolean startedSecure = "https".equalsIgnoreCase(new URL(originalUrl).getProtocol());
            return startedSecure && !"https".equalsIgnoreCase(new URL(candidateUrl).getProtocol());
        } catch (Throwable t) {
            Log.debug("downloadclient.scheme-compare-failed", t, "original", originalUrl, "candidate", candidateUrl);
            return true;
        }
    }

    public static @Nullable Downloaded download(String url, Path target, String userAgent) {
        HttpURLConnection connection = null;
        try {
            String current = url;

            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                URL currentUrl = new URL(current);
                if (isInsecureDowngrade(url, current)) {
                    Log.warn("downloadclient.insecure-downgrade", "protocol", currentUrl.getProtocol());
                    return null;
                }
                connection = (HttpURLConnection) currentUrl.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept", "application/java-archive, application/octet-stream, */*");

                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || location.isBlank()) {
                        Log.warn("downloadclient.redirect-no-location");
                        return null;
                    }
                    current = new URL(currentUrl, location).toString();
                    continue;
                }

                if (status < 200 || status >= 300) {
                    Log.warn("downloadclient.bad-status", "status", String.valueOf(status));
                    return null;
                }

                long declared = connection.getContentLengthLong();
                if (declared > MAX_SIZE_BYTES) {
                    Log.warn("downloadclient.file-too-large-declared", "mb", String.valueOf(declared / 1024 / 1024));
                    return null;
                }

                return transfer(connection, target);
            }

            Log.warn("downloadclient.too-many-redirects");
            return null;

        } catch (Throwable t) {
            Log.warn("downloadclient.download-failed", t, "error", t.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static @Nullable Downloaded transfer(HttpURLConnection connection, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        long total = 0;

        try (InputStream raw = connection.getInputStream();
             DigestInputStream first = new DigestInputStream(raw, sha1);
             DigestInputStream second = new DigestInputStream(first, sha256);
             OutputStream out = Files.newOutputStream(part)) {

            byte[] buffer = new byte[32768];
            int read;
            while ((read = second.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SIZE_BYTES) {
                    Log.warn("downloadclient.size-limit-exceeded");
                    Files.deleteIfExists(part);
                    return null;
                }
                out.write(buffer, 0, read);
            }
        } catch (Throwable t) {
            Files.deleteIfExists(part);
            throw t;
        }

        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        double mb = total / (1024.0 * 1024.0);
        Log.debug("downloadclient.downloaded", "mb", String.format(Locale.ROOT, "%.2f", mb), "file", target.getFileName().toString());
        return new Downloaded(target, HashUtil.toHex(sha1.digest()), HashUtil.toHex(sha256.digest()), total);
    }
}

