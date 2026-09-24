package ru.milkyway.plugmanreloaded.download;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void followsAbsoluteRedirectAndDownloadsTheFinalJar(@TempDir Path tempDir) throws Exception {
        byte[] jarBytes = "fake-jar-content".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/final.jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final.jar", exchange -> {
            exchange.sendResponseHeaders(200, jarBytes.length);
            exchange.getResponseBody().write(jarBytes);
            exchange.close();
        });
        server.start();

        Path target = tempDir.resolve("Plugin.jar.tmp");
        DownloadClient.Downloaded result = DownloadClient.download(
                "http://127.0.0.1:" + port + "/redirect", target, "PlugManReloaded-Test");

        assertNotNull(result, "абсолютный редирект должен быть пройден успешно");
        assertEquals(jarBytes.length, result.size());
        assertTrue(target.toFile().exists());
    }

    @Test
    void resolvesRelativeLocationHeaderAgainstCurrentUrlInsteadOfFailing(@TempDir Path tempDir) throws Exception {
        byte[] jarBytes = "fake-jar-content-2".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/download/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/download/final.jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/download/final.jar", exchange -> {
            exchange.sendResponseHeaders(200, jarBytes.length);
            exchange.getResponseBody().write(jarBytes);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        Path target = tempDir.resolve("Plugin.jar.tmp");
        DownloadClient.Downloaded result = DownloadClient.download(
                "http://127.0.0.1:" + port + "/download/redirect", target, "PlugManReloaded-Test");

        assertNotNull(result,
                "относительный Location (без схемы/хоста) обязан резолвиться относительно текущего URL "
                        + "(new URL(currentUrl, location)), а не передаваться в new URL(location) напрямую — "
                        + "последнее бросает MalformedURLException на относительном пути и тихо проваливает "
                        + "скачивание, хотя сервер прислал корректный (пусть и нестандартный) редирект");
        assertEquals(jarBytes.length, result.size());
    }

    @Test
    void refusesWhenAnHttpsDownloadIsRedirectedToPlainHttp() {
        assertTrue(DownloadClient.isInsecureDowngrade(
                        "https://cdn.example.com/Plugin.jar", "http://evil.example.com/Plugin.jar"),
                "понижение https -> http по редиректу обязано отклоняться: файл потом ЗАГРУЖАЕТСЯ как "
                        + "плагин, а хэш присылают не все площадки, поэтому подмена по дороге = чужой код на сервере");

        assertTrue(DownloadClient.isInsecureDowngrade(
                        "https://cdn.example.com/a.jar", "http://cdn.example.com/a.jar"),
                "тот же хост роли не играет — важна именно потеря шифрования");
    }

    @Test
    void keepsWorkingForHonestRedirectsAndForDeliberateHttpSources() {
        assertFalse(DownloadClient.isInsecureDowngrade(
                        "https://cdn.example.com/a.jar", "https://other.example.com/a.jar"),
                "обычный https -> https редирект между площадкой и её CDN обязан проходить");

        assertFalse(DownloadClient.isInsecureDowngrade(
                        "http://jenkins.local:8080/job/x/artifact/a.jar", "http://jenkins.local:8080/real/a.jar"),
                "загрузку, которую администратор сам начал по http (внутренний Jenkins), не запрещаем");
    }

    @Test
    void unparsableUrlsAreTreatedAsUnsafe() {
        assertTrue(DownloadClient.isInsecureDowngrade("https://cdn.example.com/a.jar", "not a url"),
                "если схему разобрать не удалось, безопаснее отказаться, чем скачать неизвестно откуда");
    }

    @Test
    void acceptsOnlyHttpDownloadProtocols() {
        assertTrue(DownloadClient.isAllowedProtocol("https://example.com/plugin.jar"));
        assertTrue(DownloadClient.isAllowedProtocol("http://jenkins.local/plugin.jar"));
        assertFalse(DownloadClient.isAllowedProtocol("file:///server.properties"));
        assertFalse(DownloadClient.isAllowedProtocol("jar:https://example.com/archive.jar!/plugin.jar"));
        assertFalse(DownloadClient.isAllowedProtocol("not a url"));
    }

    @Test
    void rejectsTruncatedResponseAndRemovesPartialFile(@TempDir Path tempDir) throws Exception {
        byte[] bytes = "truncated".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/broken.jar", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length + 100);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        Path target = tempDir.resolve("Broken.jar.tmp");
        DownloadClient.Downloaded result = DownloadClient.download(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/broken.jar", target,
                "PlugManReloaded-Test");

        assertNull(result);
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".part")));
    }
}
