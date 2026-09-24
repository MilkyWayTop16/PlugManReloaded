package ru.milkyway.plugmanreloaded.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class PlugManAPITest {

    private DummyAPI dummyApi;

    @BeforeEach
    void setUp() {
        dummyApi = new DummyAPI();
        PlugManProvider.register(dummyApi);
    }

    @AfterEach
    void tearDown() {
        PlugManProvider.unregister();
    }

    @Test
    void testProviderAccess() {
        assertSame(dummyApi, PlugManAPI.get());
        assertSame(dummyApi, PlugManProvider.get());
    }

    @Test
    void testProviderThrowsWhenUnregistered() {
        PlugManProvider.unregister();
        assertThrows(IllegalStateException.class, PlugManAPI::get);
    }

    @Test
    void testApiDelegation() {
        PlugManAPI api = PlugManAPI.get();
        PluginResult res = api.loadPlugin("TestPlugin");
        assertTrue(res.success());
        assertEquals("loaded", res.messageKey());

        PluginResult reloadRes = api.reloadPlugin("TestPlugin");
        assertTrue(reloadRes.success());
        assertEquals(50L, reloadRes.elapsedMs());

        assertTrue(api.isPluginLoaded("TestPlugin"));
        assertTrue(api.isPluginEnabled("TestPlugin"));
        assertFalse(api.isPluginProtected("TestPlugin"));
    }

    @Test
    void testDirectApiMethods() {
        PlugManAPI api = PlugManAPI.get();
        assertTrue(api.isHotSwapEnabled());
        api.setHotSwapEnabled(false);
        assertNotNull(api.getNode("TestPlugin"));
    }

    @Test
    void testPluginResultMethods() {
        PluginResult success = PluginResult.ofSuccess("msg.success", Map.of("key", "val"), 123L);
        assertTrue(success.success());
        assertEquals(123L, success.elapsedMs());
        assertEquals("val", success.placeholders().get("key"));

        PluginResult error = PluginResult.ofError("msg.error", new RuntimeException("fail"), Map.of());
        assertFalse(error.success());
        assertNotNull(error.error());
    }

    @Test
    void testDependencyNode() {
        DependencyNode node = new DependencyNode("Vault");
        node.addHardDependency("Core");
        node.addSoftDependency("PAPI");
        node.addDependent("Shop");

        assertEquals("Vault", node.getName());
        assertTrue(node.getHardDependencies().contains("Core"));
        assertTrue(node.getSoftDependencies().contains("PAPI"));
        assertTrue(node.getDependents().contains("Shop"));
    }

    @Test
    void testUpdateInfo() {
        UpdateInfo info = new UpdateInfo(
                "Essentials",
                "2.20.0",
                "2.21.0",
                "modrinth",
                "https://modrinth.com/plugin/essentials",
                true,
                false,
                true
        );

        assertEquals("Essentials", info.pluginName());
        assertEquals("2.20.0", info.currentVersion());
        assertEquals("2.21.0", info.newVersion());
        assertTrue(info.downloadable());
        assertFalse(info.isPremium());
        assertTrue(info.hasUpdate());
    }

    private static class DummyAPI implements PlugManAPI {

        @Override
        public PluginResult loadPlugin(File file) {
            return PluginResult.ofSuccess("loaded");
        }

        @Override
        public PluginResult loadPlugin(String pluginName) {
            return PluginResult.ofSuccess("loaded");
        }

        @Override
        public PluginResult unloadPlugin(org.bukkit.plugin.Plugin plugin, boolean deep) {
            return PluginResult.ofSuccess("unloaded");
        }

        @Override
        public PluginResult unloadPlugin(String pluginName, boolean deep) {
            return PluginResult.ofSuccess("unloaded");
        }

        @Override
        public PluginResult reloadPlugin(org.bukkit.plugin.Plugin plugin) {
            return PluginResult.ofSuccess("reloaded", Map.of(), 50L);
        }

        @Override
        public PluginResult reloadPlugin(String pluginName) {
            return PluginResult.ofSuccess("reloaded", Map.of(), 50L);
        }

        @Override
        public PluginResult restartPlugin(org.bukkit.plugin.Plugin plugin) {
            return PluginResult.ofSuccess("restarted");
        }

        @Override
        public PluginResult restartPlugin(String pluginName) {
            return PluginResult.ofSuccess("restarted");
        }

        @Override
        public PluginResult enablePlugin(org.bukkit.plugin.Plugin plugin) {
            return PluginResult.ofSuccess("enabled");
        }

        @Override
        public PluginResult enablePlugin(String pluginName) {
            return PluginResult.ofSuccess("enabled");
        }

        @Override
        public PluginResult disablePlugin(org.bukkit.plugin.Plugin plugin) {
            return PluginResult.ofSuccess("disabled");
        }

        @Override
        public PluginResult disablePlugin(String pluginName) {
            return PluginResult.ofSuccess("disabled");
        }

        @Override
        public PluginResult deletePlugin(org.bukkit.plugin.Plugin plugin) {
            return PluginResult.ofSuccess("deleted");
        }

        @Override
        public PluginResult deletePlugin(String pluginName) {
            return PluginResult.ofSuccess("deleted");
        }

        @Override
        public boolean isPluginLoaded(String pluginName) {
            return true;
        }

        @Override
        public boolean isPluginEnabled(String pluginName) {
            return true;
        }

        @Override
        public boolean isPluginProtected(String pluginName) {
            return false;
        }

        @Override
        public DependencyNode getNode(String pluginName) {
            return new DependencyNode(pluginName);
        }

        @Override
        public Set<String> getDependents(String pluginName) {
            return Set.of();
        }

        @Override
        public Set<String> getDependencies(String pluginName) {
            return Set.of();
        }

        @Override
        public List<String> getCascadeUnloadOrder(String pluginName) {
            return List.of(pluginName);
        }

        @Override
        public List<String> getCascadeLoadOrder(String pluginName) {
            return List.of(pluginName);
        }

        @Override
        public Map<String, DependencyNode> getFullGraph() {
            return Map.of();
        }

        @Override
        public Optional<PluginInfo> getPluginInfo(String pluginName) {
            return Optional.empty();
        }

        @Override
        public Optional<PluginInfo> getPluginInfo(org.bukkit.plugin.Plugin plugin) {
            return Optional.empty();
        }

        @Override
        public Optional<File> findJarFile(String pluginName) {
            return Optional.empty();
        }

        @Override
        public List<String> getLoadablePluginNames() {
            return List.of();
        }

        @Override
        public List<PluginInfo> getAllPlugins() {
            return List.of();
        }

        @Override
        public CompletableFuture<Optional<UpdateInfo>> checkUpdate(org.bukkit.plugin.Plugin plugin) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<UpdateInfo>> checkUpdate(String pluginName) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<UpdateInfo>> checkAllUpdates() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public boolean isHotSwapEnabled() {
            return true;
        }

        @Override
        public void setHotSwapEnabled(boolean enabled) {
        }
    }
}
