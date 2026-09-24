package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.configs.MainConfig;
import ru.milkyway.plugmanreloaded.api.FailureReason;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.bridge.PlatformBridge;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.WeakHashMap;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleManagerTest {

    private Server originalServer;

    @BeforeEach
    void saveServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        originalServer = (Server) serverField.get(null);
    }

    @AfterEach
    void restoreServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, originalServer);
    }

    @BeforeAll
    static void installBukkitStub() {
        ru.milkyway.plugmanreloaded.BukkitServerMock.ensureInitialized();
    }

    @Test
    @DisplayName("Test self-protection and protected error keys")
    void testProtectionAndErrorKeys() {
        LifecycleManager plm = new LifecycleManager(null);

        assertTrue(plm.isProtected("PlugManReloaded"));
        assertTrue(plm.isProtected("plugmanreloaded"));
        assertFalse(plm.isProtected((String) null));

        assertEquals(ru.milkyway.plugmanreloaded.api.FailureReason.PLUGIN_IGNORED, plm.protectedReason((org.bukkit.plugin.Plugin) null));
    }

    @Test
    @DisplayName("Verify path traversal prevention in isValidPluginsPath")
    void testPathTraversalSecurity() throws Exception {
        LifecycleManager plm = new LifecycleManager(null);

        Method isValidPluginsPath = LifecycleManager.class.getDeclaredMethod("isValidPluginsPath", File.class);
        isValidPluginsPath.setAccessible(true);

        assertFalse((boolean) isValidPluginsPath.invoke(plm, (File) null));
        assertFalse((boolean) isValidPluginsPath.invoke(plm, new File("some_file.jar")));
    }

    @Test
    @DisplayName("Verify LifecycleManager uses WeakHashMap for pluginFileCache")
    void testPluginFileCacheIsWeakHashMap() throws Exception {
        LifecycleManager plm = new LifecycleManager(null);
        Field field = LifecycleManager.class.getDeclaredField("pluginFileCache");
        field.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) field.get(plm);
        assertNotNull(cache);

        Field mField = cache.getClass().getDeclaredField("m");
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
        long offset = unsafe.objectFieldOffset(mField);
        Object unwrapped = unsafe.getObject(cache, offset);
        assertTrue(unwrapped instanceof WeakHashMap,
                "pluginFileCache обязан использовать WeakHashMap для предотвращения утечек Metaspace");
    }

    @Test
    @DisplayName("Verify invalidatePluginFile removes target plugin from cache")
    void testInvalidatePluginFileEvictsTargetFromCache() throws Exception {
        LifecycleManager plm = new LifecycleManager(null);
        Plugin dummyPlugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                LifecycleManagerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "DummyPlugin";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 42;
                    default -> null;
                });
        File dummyFile = new File("dummy.jar");
        plm.getPluginFileCache().put(dummyPlugin, dummyFile);
        assertTrue(plm.getPluginFileCache().containsKey(dummyPlugin));

        plm.invalidatePluginFile(dummyPlugin);
        assertFalse(plm.getPluginFileCache().containsKey(dummyPlugin),
                "invalidatePluginFile обязан удалять плагин из pluginFileCache");
    }

    @Test
    @DisplayName("Do not unload a provider when one of its bulk-unload dependents failed")
    void testBulkUnloadKeepsProviderWhenDependentFails() throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("Dependent", "name: Dependent\nmain: test.Dependent\nversion: 1.0\ndepend: [Provider]\n", true);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        List<String> unloadCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(unloadCalls, Map.of(), "Dependent"));

        ru.milkyway.plugmanreloaded.api.BulkOperationResult result =
                lifecycleManager.bulkUnload(List.of(provider, dependent));

        assertEquals(List.of("Dependent"), unloadCalls);
        assertTrue(result.failedPlugins().contains("Provider"));
        assertFalse(result.successfulPlugins().contains("Provider"));
    }

    @Test
    @DisplayName("Do not bulk-unload a provider while a protected dependent remains loaded")
    void testBulkUnloadKeepsProviderWithProtectedDependent() throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("PlugManReloaded", "name: PlugManReloaded\nmain: test.PlugManReloaded\nversion: 1.0\ndepend: [Provider]\n", true);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        List<String> unloadCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(unloadCalls, Map.of(), null));

        ru.milkyway.plugmanreloaded.api.BulkOperationResult result =
                lifecycleManager.bulkUnload(List.of(provider, dependent));

        assertTrue(unloadCalls.isEmpty());
        assertTrue(result.failedPlugins().contains("Provider"));
    }

    @Test
    @DisplayName("Do not bulk-unload a provider while a disabled dependent remains loaded")
    void testBulkUnloadKeepsProviderWithDisabledDependent() throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("Dependent", "name: Dependent\nmain: test.Dependent\nversion: 1.0\ndepend: [Provider]\n", false);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        List<String> unloadCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(unloadCalls, Map.of(), null));

        ru.milkyway.plugmanreloaded.api.BulkOperationResult result = lifecycleManager.bulkUnload(List.of(provider));

        assertTrue(unloadCalls.isEmpty());
        assertTrue(result.failedPlugins().contains("Provider"));
    }

    @Test
    @DisplayName("Do not disable a provider when one of its bulk-disable dependents fails")
    void testBulkDisableKeepsProviderWhenDependentFails() throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("Dependent", "name: Dependent\nmain: test.Dependent\nversion: 1.0\ndepend: [Provider]\n", true);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        List<String> disableCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(disableCalls, Map.of(), "Dependent"));

        ru.milkyway.plugmanreloaded.api.BulkOperationResult result =
                lifecycleManager.bulkDisable(List.of(provider, dependent));

        assertEquals(List.of("Dependent"), disableCalls);
        assertTrue(result.failedPlugins().contains("Provider"));
        assertFalse(result.successfulPlugins().contains("Provider"));
    }

    @Test
    @DisplayName("Do not bulk-disable a provider while a protected dependent remains enabled")
    void testBulkDisableKeepsProviderWithProtectedDependent() throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("PlugManReloaded", "name: PlugManReloaded\nmain: test.PlugManReloaded\nversion: 1.0\ndepend: [Provider]\n", true);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        List<String> disableCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(disableCalls, Map.of(), null));

        ru.milkyway.plugmanreloaded.api.BulkOperationResult result =
                lifecycleManager.bulkDisable(List.of(provider, dependent));

        assertTrue(disableCalls.isEmpty());
        assertTrue(result.failedPlugins().contains("Provider"));
    }

    @Test
    @DisplayName("Allow bulk-disable when a dependent was already disabled")
    void testBulkDisableAllowsAlreadyDisabledDependent() throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("Dependent", "name: Dependent\nmain: test.Dependent\nversion: 1.0\ndepend: [Provider]\n", false);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        List<String> disableCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(disableCalls, Map.of(), null));

        ru.milkyway.plugmanreloaded.api.BulkOperationResult result = lifecycleManager.bulkDisable(List.of(provider));

        assertEquals(List.of("Provider"), disableCalls);
        assertTrue(result.successfulPlugins().contains("Provider"));
    }

    @Test
    @DisplayName("Skip hard dependents after provider enable failure while keeping soft dependents optional")
    void testBulkEnableRespectsFailedHardDependency() throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", false);
        Plugin dependent = fakePlugin("Dependent", "name: Dependent\nmain: test.Dependent\nversion: 1.0\ndepend: [Provider]\n", false);
        Plugin optional = fakePlugin("Optional", "name: Optional\nmain: test.Optional\nversion: 1.0\nsoftdepend: [Provider]\n", false);
        installServer(provider, dependent, optional);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        List<String> enableCalls = new ArrayList<>();
        setBridge(lifecycleManager, (PlatformBridge) Proxy.newProxyInstance(
                LifecycleManagerTest.class.getClassLoader(),
                new Class<?>[]{PlatformBridge.class},
                (proxy, method, args) -> {
                    if (!"enablePlugin".equals(method.getName())) return PluginResult.ofSuccess("test.success");
                    Plugin target = (Plugin) args[0];
                    enableCalls.add(target.getName());
                    return "Provider".equals(target.getName())
                            ? PluginResult.ofError(FailureReason.ENABLE_FAILED, "plugin", target.getName())
                            : PluginResult.ofSuccess("enable.success", "plugin", target.getName());
                }));

        ru.milkyway.plugmanreloaded.api.BulkOperationResult result =
                lifecycleManager.bulkEnable(List.of(optional, dependent, provider));

        assertEquals(List.of("Provider", "Optional"), enableCalls);
        assertTrue(result.failedPlugins().contains("Dependent"));
        assertTrue(result.successfulPlugins().contains("Optional"));
    }

    @Test
    @DisplayName("Do not bulk-reload a provider when a dependent fails preflight")
    void testBulkReloadKeepsProviderWhenDependentJarIsMissing(@TempDir Path tempDir) throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("Dependent", "name: Dependent\nmain: test.Dependent\nversion: 1.0\ndepend: [Provider]\n", true);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        File providerJar = createPluginJar(tempDir.resolve("Provider.jar").toFile(), "Provider", null);
        List<String> unloadCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(unloadCalls, Map.of("Provider", providerJar), null));

        ru.milkyway.plugmanreloaded.api.BulkOperationResult result =
                lifecycleManager.bulkReload(List.of(provider, dependent));

        assertTrue(unloadCalls.isEmpty());
        assertTrue(result.failedPlugins().contains("Dependent"));
        assertTrue(result.failedPlugins().contains("Provider"));
    }

    @Test
    @DisplayName("Do not bulk-reload a provider while a protected dependent remains loaded")
    void testBulkReloadKeepsProviderWithProtectedDependent(@TempDir Path tempDir) throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("PlugManReloaded", "name: PlugManReloaded\nmain: test.PlugManReloaded\nversion: 1.0\ndepend: [Provider]\n", true);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        File providerJar = createPluginJar(tempDir.resolve("Provider.jar").toFile(), "Provider", null);
        List<String> unloadCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(unloadCalls, Map.of("Provider", providerJar), null));

        ru.milkyway.plugmanreloaded.api.BulkOperationResult result =
                lifecycleManager.bulkReload(List.of(provider, dependent));

        assertTrue(unloadCalls.isEmpty());
        assertTrue(result.failedPlugins().contains("Provider"));
    }

    @Test
    @DisplayName("Do not begin cascade reload when a dependent cannot be preflighted")
    void testCascadeDoesNotUnloadProviderWhenDependentJarIsMissing(@TempDir Path tempDir) throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("Dependent", "name: Dependent\nmain: test.Dependent\nversion: 1.0\ndepend: [Provider]\n", true);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        File providerJar = createPluginJar(tempDir.resolve("Provider.jar").toFile(), "Provider", null);
        List<String> unloadCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(unloadCalls, Map.of("Provider", providerJar), null));
        Method executeCascade = LifecycleManager.class.getDeclaredMethod("executeCascade", Plugin.class, boolean.class);
        executeCascade.setAccessible(true);

        PluginResult result = (PluginResult) executeCascade.invoke(lifecycleManager, provider, false);

        assertFalse(result.success());
        assertTrue(unloadCalls.isEmpty());
    }

    @Test
    @DisplayName("Do not cascade-reload a provider while a protected dependent remains loaded")
    void testCascadeKeepsProviderWithProtectedDependent(@TempDir Path tempDir) throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin dependent = fakePlugin("PlugManReloaded", "name: PlugManReloaded\nmain: test.PlugManReloaded\nversion: 1.0\ndepend: [Provider]\n", true);
        installServer(provider, dependent);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        File providerJar = createPluginJar(tempDir.resolve("Provider.jar").toFile(), "Provider", null);
        List<String> unloadCalls = new ArrayList<>();
        setBridge(lifecycleManager, bridge(unloadCalls, Map.of("Provider", providerJar), null));
        Method executeCascade = LifecycleManager.class.getDeclaredMethod("executeCascade", Plugin.class, boolean.class);
        executeCascade.setAccessible(true);

        PluginResult result = (PluginResult) executeCascade.invoke(lifecycleManager, provider, false);

        assertFalse(result.success());
        assertTrue(unloadCalls.isEmpty());
    }

    @Test
    @DisplayName("Pass the replacement plugin instance to the reload event")
    void testReloadEventUsesReplacementInstance() throws Exception {
        Plugin oldPlugin = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        Plugin newPlugin = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 2.0\n", true);
        List<Object> events = new ArrayList<>();
        installServer(events, newPlugin, oldPlugin);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        setBridge(lifecycleManager, (PlatformBridge) Proxy.newProxyInstance(
                LifecycleManagerTest.class.getClassLoader(),
                new Class<?>[]{PlatformBridge.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPluginFile" -> null;
                    case "reloadPlugin" -> PluginResult.ofSuccess("reload.success");
                    default -> PluginResult.ofSuccess("test.success");
                }));

        PluginResult result = lifecycleManager.reload(oldPlugin);
        ru.milkyway.plugmanreloaded.api.event.PluginReloadedEvent event = events.stream()
                .filter(ru.milkyway.plugmanreloaded.api.event.PluginReloadedEvent.class::isInstance)
                .map(ru.milkyway.plugmanreloaded.api.event.PluginReloadedEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertTrue(result.success());
        assertSame(newPlugin, event.getPlugin());
    }

    @Test
    @DisplayName("Report failed restoration of a plugin that was previously disabled")
    void testReportsFailedDisabledStateRestoration(@TempDir Path tempDir) throws Exception {
        Plugin provider = fakePlugin("Provider", "name: Provider\nmain: test.Provider\nversion: 1.0\n", true);
        installServer(provider);
        LifecycleManager lifecycleManager = createTestLifecycleManager();
        File providerJar = createPluginJar(tempDir.resolve("Provider.jar").toFile(), "Provider", null);
        setBridge(lifecycleManager, (PlatformBridge) Proxy.newProxyInstance(
                LifecycleManagerTest.class.getClassLoader(),
                new Class<?>[]{PlatformBridge.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "loadPlugin" -> PluginResult.ofSuccess("load.success");
                    case "disablePlugin" -> PluginResult.ofError(FailureReason.DISABLE_FAILED, "plugin", "Provider");
                    default -> PluginResult.ofSuccess("test.success");
                }));
        Map<String, File> files = Map.of("Provider", providerJar);
        Map<String, Boolean> wasEnabled = Map.of("Provider", false);
        Map<String, ru.milkyway.plugmanreloaded.api.DependencyNode> graph = lifecycleManager.getDependencyManager().buildGraph(true);

        Class<?> cascadePlanType = Class.forName(LifecycleManager.class.getName() + "$CascadePlan");
        java.lang.reflect.Constructor<?> cascadeConstructor = cascadePlanType.getDeclaredConstructor(
                List.class, Map.class, Map.class, List.class, Map.class);
        cascadeConstructor.setAccessible(true);
        Object cascadePlan = cascadeConstructor.newInstance(List.of("Provider"), files, wasEnabled, List.of(), graph);

        Method loadAll = LifecycleManager.class.getDeclaredMethod("loadAll", cascadePlanType);
        loadAll.setAccessible(true);
        List<?> cascadeFailures = (List<?>) loadAll.invoke(lifecycleManager, cascadePlan);

        Class<?> unloadPhaseType = Class.forName(LifecycleManager.class.getName() + "$UnloadPhase");
        java.lang.reflect.Constructor<?> unloadConstructor = unloadPhaseType.getDeclaredConstructor(List.class, String.class, String.class);
        unloadConstructor.setAccessible(true);
        Object unloadPhase = unloadConstructor.newInstance(List.of("Provider"), "Dependent", "cancelled");
        Method rollback = LifecycleManager.class.getDeclaredMethod("rollback", unloadPhaseType, cascadePlanType);
        rollback.setAccessible(true);
        String rollbackResult = (String) rollback.invoke(lifecycleManager, unloadPhase, cascadePlan);

        Class<?> bulkPlanType = Class.forName(LifecycleManager.class.getName() + "$BulkPlan");
        java.lang.reflect.Constructor<?> bulkConstructor = bulkPlanType.getDeclaredConstructor(
                List.class, Map.class, Map.class, Map.class);
        bulkConstructor.setAccessible(true);
        Object bulkPlan = bulkConstructor.newInstance(List.of(provider), files, wasEnabled, graph);
        List<String> bulkFailures = new ArrayList<>();
        Map<String, String> bulkReasons = new HashMap<>();
        Method loadForBulk = LifecycleManager.class.getDeclaredMethod(
                "loadForBulk", List.class, java.util.Set.class, bulkPlanType, List.class, Map.class);
        loadForBulk.setAccessible(true);
        List<?> bulkSuccesses = (List<?>) loadForBulk.invoke(
                lifecycleManager, List.of("Provider"), java.util.Set.of("Provider"), bulkPlan, bulkFailures, bulkReasons);

        assertTrue(cascadeFailures.contains("Provider"));
        assertTrue(rollbackResult.contains("Provider"));
        assertTrue(bulkFailures.contains("Provider"));
        assertFalse(bulkSuccesses.contains("Provider"));
    }

    private static Plugin fakePlugin(String name, String yaml, boolean enabled) throws Exception {
        PluginDescriptionFile description = new PluginDescriptionFile(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return (Plugin) Proxy.newProxyInstance(
                LifecycleManagerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getDescription" -> description;
                    case "isEnabled" -> enabled;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    case "toString" -> name;
                    default -> null;
                });
    }

    private static void installServer(Plugin... plugins) throws Exception {
        installServer(null, plugins);
    }

    private static void installServer(List<Object> events, Plugin... plugins) throws Exception {
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                LifecycleManagerTest.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlugins" -> plugins;
                    case "getPlugin" -> {
                        String name = args != null && args.length > 0 ? (String) args[0] : null;
                        Plugin found = null;
                        if (name != null) {
                            for (Plugin candidate : plugins) {
                                if (candidate.getName().equalsIgnoreCase(name)) {
                                    found = candidate;
                                    break;
                                }
                            }
                        }
                        yield found;
                    }
                    case "callEvent" -> {
                        if (events != null && args != null && args.length > 0) events.add(args[0]);
                        yield null;
                    }
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
        Server server = (Server) Proxy.newProxyInstance(
                LifecycleManagerTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> "getPluginManager".equals(method.getName()) ? pluginManager : null);
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
    }

    private static PlatformBridge bridge(List<String> unloadCalls, Map<String, File> pluginFiles,
                                          String failedUnload) {
        return (PlatformBridge) Proxy.newProxyInstance(
                LifecycleManagerTest.class.getClassLoader(),
                new Class<?>[]{PlatformBridge.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPluginFile" -> pluginFiles.get(((Plugin) args[0]).getName());
                    case "unloadPlugin", "disablePlugin" -> {
                        Plugin target = (Plugin) args[0];
                        unloadCalls.add(target.getName());
                        yield target.getName().equals(failedUnload)
                                ? PluginResult.ofError("disablePlugin".equals(method.getName())
                                ? FailureReason.DISABLE_FAILED : FailureReason.UNLOAD_FAILED,
                                "plugin", target.getName())
                                : PluginResult.ofSuccess("unload.success", "plugin", target.getName());
                    }
                    case "isPaperPlugin" -> false;
                    default -> PluginResult.ofSuccess("test.success");
                });
    }

    private static void setBridge(LifecycleManager lifecycleManager, PlatformBridge bridge) throws Exception {
        Field bridgeField = LifecycleManager.class.getDeclaredField("bridge");
        bridgeField.setAccessible(true);
        bridgeField.set(lifecycleManager, bridge);
    }

    private static LifecycleManager createTestLifecycleManager() throws Exception {
        ConfigManager configManager = new ConfigManager(null);
        sun.misc.Unsafe unsafe = getUnsafe();
        MainConfig mainConfig = (MainConfig) unsafe.allocateInstance(MainConfig.class);
        Field ignoredPlugins = MainConfig.class.getDeclaredField("ignoredPlugins");
        ignoredPlugins.setAccessible(true);
        ignoredPlugins.set(mainConfig, new java.util.HashSet<>());
        Field mainConfigField = ConfigManager.class.getDeclaredField("mainConfig");
        mainConfigField.setAccessible(true);
        mainConfigField.set(configManager, mainConfig);
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        PluginDescriptionFile pluginDescription = new PluginDescriptionFile(
                "LifecycleTestPlugin", "1.0", "test.LifecycleTestPlugin");
        Field description = JavaPlugin.class.getDeclaredField("description");
        description.setAccessible(true);
        description.set(plugin, pluginDescription);
        Field pluginMeta = JavaPlugin.class.getDeclaredField("pluginMeta");
        pluginMeta.setAccessible(true);
        pluginMeta.set(plugin, pluginDescription);
        Field pluginConfigManager = PlugManReloaded.class.getDeclaredField("configManager");
        pluginConfigManager.setAccessible(true);
        pluginConfigManager.set(plugin, configManager);
        return new LifecycleManager(plugin);
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (sun.misc.Unsafe) unsafeField.get(null);
    }

    private static File createPluginJar(File file, String name, String dependency) throws Exception {
        String yaml = "name: " + name + "\nversion: 1.0\nmain: test." + name
                + "\ndescription: A test plugin descriptor used for lifecycle preflight verification\n"
                + (dependency != null ? "depend: [" + dependency + "]\n" : "");
        try (ZipOutputStream jar = new ZipOutputStream(new FileOutputStream(file))) {
            jar.putNextEntry(new ZipEntry("plugin.yml"));
            jar.write(yaml.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }
}
