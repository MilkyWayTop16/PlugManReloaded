package ru.milkyway.plugmanreloaded.download;

import com.sun.net.httpserver.HttpServer;
import ru.milkyway.plugmanreloaded.download.DownloadModels.SearchResultEntry;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class PluginDownloaderInspectionTest {

    @BeforeAll
    static void installBukkitStub() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        if (serverField.get(null) != null) {
            return;
        }

        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class[]{PluginManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlugin" -> null;
                    case "getPlugins" -> new Plugin[0];
                    default -> null;
                }
        );
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class[]{Server.class},
                (proxy, method, args) -> method.getName().equals("getPluginManager") ? pluginManager : null
        );
        serverField.set(null, server);
    }

    @Test
    void inspectionAllowsJarWithDependenciesThatWillBeResolvedByTransaction(@TempDir Path inspectionDir) throws Exception {
        byte[] jar = pluginJar("TargetPlugin", "Vault");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/target.jar", exchange -> {
            exchange.sendResponseHeaders(200, jar.length);
            exchange.getResponseBody().write(jar);
            exchange.close();
        });
        server.start();

        try {
            SearchResultEntry entry = new SearchResultEntry(
                    "direct", "target", "TargetPlugin", "author", "1.0", "description",
                    "https://example.test/target", "http://127.0.0.1:" + server.getAddress().getPort() + "/target.jar",
                    0L, 0, 0.0, List.of(), List.of(), List.of(), null, null, "target.jar", false, true
            );

            PluginDownloader.StageAttempt attempt = new PluginDownloader(null, null, "test-agent")
                    .stageForInspection(entry, inspectionDir);

            assertNotNull(attempt.item());
        } finally {
            server.stop(0);
        }
    }

    private static byte[] pluginJar(String name, String dependency) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write(("name: " + name + "\nmain: example." + name + "\nversion: 1.0\ndepend: [" + dependency + "]\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
