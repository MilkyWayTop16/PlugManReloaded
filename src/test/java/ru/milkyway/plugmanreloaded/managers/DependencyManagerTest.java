package ru.milkyway.plugmanreloaded.managers;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import ru.milkyway.plugmanreloaded.api.DependencyNode;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class DependencyManagerTest {

    @Test
    void testDependencyNodeBasicOperations() {
        DependencyNode node = new DependencyNode("TestPlugin");
        assertEquals("TestPlugin", node.getName());
        assertTrue(node.getHardDependencies().isEmpty());
        assertTrue(node.getSoftDependencies().isEmpty());
        assertTrue(node.getDependents().isEmpty());

        node.addHardDependency("Vault");
        node.addSoftDependency("ProtocolLib");
        node.addDependent("DependentPlugin");

        assertTrue(node.getHardDependencies().contains("Vault"));
        assertTrue(node.getSoftDependencies().contains("ProtocolLib"));
        assertTrue(node.getDependents().contains("DependentPlugin"));
    }

    private static PluginDescriptionFile desc(String yaml) throws Exception {
        return new PluginDescriptionFile(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Plugin fakePlugin(PluginDescriptionFile description) {
        return (Plugin) Proxy.newProxyInstance(
                DependencyManagerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> description.getName();
                    case "getDescription" -> description;
                    case "isEnabled" -> true;
                    case "hashCode" -> description.getName().hashCode();
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    case "toString" -> description.getName();
                    default -> null;
                });
    }

    private static void installServerWith(Plugin... plugins) throws Exception {
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                DependencyManagerTest.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> method.getName().equals("getPlugins") ? plugins : null);

        Server server = (Server) Proxy.newProxyInstance(
                DependencyManagerTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> method.getName().equals("getPluginManager") ? pluginManager : null);

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
    }

    @org.junit.jupiter.api.AfterEach
    void resetAfterEach() {
        ru.milkyway.plugmanreloaded.BukkitServerMock.resetServer();
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanupServer() throws Exception {
        ru.milkyway.plugmanreloaded.BukkitServerMock.resetServer();
    }

    @Test
    void testMultiProviderCollisionResolution() throws Exception {
        Plugin economyA = fakePlugin(desc("name: EconomyA\nmain: t.A\nversion: 1.0\nprovides: [Vault]\n"));
        Plugin economyB = fakePlugin(desc("name: EconomyB\nmain: t.B\nversion: 1.0\nprovides: [Vault]\n"));
        Plugin shopGui = fakePlugin(desc("name: ShopGUI\nmain: t.S\nversion: 1.0\ndepend: [Vault]\n"));
        installServerWith(economyA, economyB, shopGui);

        DependencyManager manager = new DependencyManager(null);

        assertTrue(manager.getDependents("EconomyA", true).contains("ShopGUI"),
                "Первый провайдер Vault обязан быть связан с ShopGUI");
        assertTrue(manager.getDependents("EconomyB", true).contains("ShopGUI"),
                "Второй провайдер Vault обязан быть связан с ShopGUI");
    }

    @Test
    void testSortPluginsTopologicallyResolvesProvidesAlias() throws Exception {
        Plugin vault = fakePlugin(desc("name: VaultSlim\nmain: t.VS\nversion: 1.0\nprovides: [Vault]\n"));
        Plugin shop = fakePlugin(desc("name: ShopGUI\nmain: t.S\nversion: 1.0\ndepend: [Vault]\n"));
        installServerWith(vault, shop);

        List<Plugin> sorted = new DependencyManager(null).sortPluginsTopologically(List.of(shop, vault));

        assertSame(vault, sorted.get(0));
        assertSame(shop, sorted.get(1));
    }

    @Test
    void testCascadeOrderResolvesProvidesAlias() throws Exception {
        Plugin vault = fakePlugin(desc("name: VaultSlim\nmain: t.VS\nversion: 1.0\nprovides: [Vault]\n"));
        Plugin shop = fakePlugin(desc("name: ShopGUI\nmain: t.S\nversion: 1.0\ndepend: [Vault]\n"));
        installServerWith(vault, shop);

        List<String> order = new DependencyManager(null).calculateCascadeOrder("VaultSlim", false);

        assertEquals(List.of("VaultSlim", "ShopGUI"), order);
    }

    @Test
    void testFailedProvidesProviderBlocksHardDependent() throws Exception {
        Plugin vault = fakePlugin(desc("name: VaultSlim\nmain: t.VS\nversion: 1.0\nprovides: [Vault]\n"));
        Plugin shop = fakePlugin(desc("name: ShopGUI\nmain: t.S\nversion: 1.0\ndepend: [Vault]\n"));
        installServerWith(vault, shop);
        Map<String, ru.milkyway.plugmanreloaded.api.DependencyNode> graph = new DependencyManager(null).buildGraph(false);
        LifecycleManager lifecycleManager = new LifecycleManager(null);
        java.lang.reflect.Method method = LifecycleManager.class.getDeclaredMethod(
                "dependencyAlreadyFailed", Map.class, String.class, Set.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(lifecycleManager, graph, "ShopGUI", Set.of("vaultslim")));
    }

    @Test
    void testRealPluginNameWinsOverProvidesAlias() throws Exception {
        Plugin realVault = fakePlugin(desc("name: Vault\nmain: t.V\nversion: 1.0\n"));
        Plugin impostor = fakePlugin(desc("name: Impostor\nmain: t.I\nversion: 1.0\nprovides: [Vault]\n"));
        Plugin shopGui = fakePlugin(desc("name: ShopGUI\nmain: t.S\nversion: 1.0\ndepend: [Vault]\n"));
        installServerWith(realVault, impostor, shopGui);

        DependencyManager manager = new DependencyManager(null);

        assertTrue(manager.getDependents("Vault", true).contains("ShopGUI"),
                "Реальный плагин с этим именем обязан выигрывать у алиаса provides");
        assertFalse(manager.getDependents("Impostor", true).contains("ShopGUI"),
                "Алиас не должен перехватывать зависимость, когда есть настоящий плагин Vault");
    }

    @Test
    void testDiamondDependencyCascadeOrder() throws Exception {
        Plugin rootA = fakePlugin(desc("name: RootA\nmain: t.A\nversion: 1.0\n"));
        Plugin midB = fakePlugin(desc("name: MidB\nmain: t.B\nversion: 1.0\ndepend: [RootA]\n"));
        Plugin midC = fakePlugin(desc("name: MidC\nmain: t.C\nversion: 1.0\ndepend: [RootA]\n"));
        Plugin leafD = fakePlugin(desc("name: LeafD\nmain: t.D\nversion: 1.0\ndepend: [MidB, MidC]\n"));
        installServerWith(rootA, midB, midC, leafD);

        DependencyManager manager = new DependencyManager(null);
        List<String> order = manager.calculateCascadeOrder("RootA", true);

        assertEquals("RootA", order.get(0));
        assertEquals("LeafD", order.get(3));
        assertTrue(order.indexOf("RootA") < order.indexOf("MidB"));
        assertTrue(order.indexOf("RootA") < order.indexOf("MidC"));
        assertTrue(order.indexOf("MidB") < order.indexOf("LeafD"));
        assertTrue(order.indexOf("MidC") < order.indexOf("LeafD"));
    }

    @Test
    void testSoftDependencyCycleHandling() throws Exception {
        Plugin cycleA = fakePlugin(desc("name: CycleA\nmain: t.A\nversion: 1.0\nsoftdepend: [CycleB]\n"));
        Plugin cycleB = fakePlugin(desc("name: CycleB\nmain: t.B\nversion: 1.0\nsoftdepend: [CycleA]\n"));
        installServerWith(cycleA, cycleB);

        DependencyManager manager = new DependencyManager(null);
        assertDoesNotThrow(() -> {
            List<String> order = manager.calculateCascadeOrder("CycleA", true);
            assertNotNull(order);
            assertFalse(order.isEmpty());
        });
    }

    @Test
    void testSelfDependencyInCascadeOrder() throws Exception {
        Plugin selfPlugin = fakePlugin(desc("name: SelfPlugin\nmain: t.SP\nversion: 1.0\nsoftdepend: [SelfPlugin]\n"));
        Plugin childPlugin = fakePlugin(desc("name: ChildPlugin\nmain: t.CP\nversion: 1.0\ndepend: [SelfPlugin]\n"));
        installServerWith(selfPlugin, childPlugin);

        DependencyManager manager = new DependencyManager(null);
        List<String> order = manager.calculateCascadeOrder("SelfPlugin", true);
        assertNotNull(order);
        assertEquals(2, order.size());
        assertEquals("SelfPlugin", order.get(0));
        assertEquals("ChildPlugin", order.get(1));
    }

    @Test
    void testCircularDependencyResolution() {
        DependencyManager manager = new DependencyManager(null);
        assertDoesNotThrow(() -> manager.getDependents("NonExistentPlugin"));
        assertDoesNotThrow(() -> manager.calculateCascadeOrder("NonExistentPlugin"));
        assertDoesNotThrow(() -> manager.sortPluginsTopologically(Collections.emptyList()));
        assertDoesNotThrow(() -> manager.sortNamesTopologically(Collections.emptyList()));
    }

    @Test
    void testSortNamesTopologically() throws Exception {
        Plugin blib = fakePlugin(desc("name: BLib\nmain: t.BLib\nversion: 1.0\n"));
        Plugin bmenu = fakePlugin(desc("name: BMenu\nmain: t.BMenu\nversion: 1.0\ndepend: [BLib]\n"));
        Plugin bcases = fakePlugin(desc("name: BCases\nmain: t.BCases\nversion: 1.0\ndepend: [BLib]\n"));
        Plugin papi = fakePlugin(desc("name: PlaceholderAPI\nmain: t.PAPI\nversion: 1.0\n"));
        installServerWith(blib, bmenu, bcases, papi);

        DependencyManager manager = new DependencyManager(null);
        List<String> sorted = manager.sortNamesTopologically(List.of("BCases", "BMenu", "BLib", "PlaceholderAPI"));

        assertTrue(sorted.indexOf("BLib") < sorted.indexOf("BCases"));
        assertTrue(sorted.indexOf("BLib") < sorted.indexOf("BMenu"));
        assertTrue(sorted.contains("PlaceholderAPI"));
    }

    @Test
    void testLiveServerSetupDependents() throws Exception {
        Plugin lp = fakePlugin(desc("name: LuckPerms\nmain: t.LP\nversion: 1.0\nloadbefore: [Vault]\n"));
        Plugin vs = fakePlugin(desc("name: VaultSlim\nmain: t.VS\nversion: 1.0\nprovides: [Vault]\n"));
        Plugin cfp = fakePlugin(desc("name: ChatFilterPlus\nmain: t.CFP\nversion: 1.0\nsoftdepend: [LuckPerms, PlaceholderAPI]\n"));
        Plugin ess = fakePlugin(desc("name: Essentials\nmain: t.ESS\nversion: 1.0\nsoftdepend: [Vault, LuckPerms]\n"));
        Plugin fc = fakePlugin(desc("name: fcChat\nmain: t.FC\nversion: 1.0\nsoftdepend: [PlaceholderAPI, LuckPerms]\n"));
        Plugin wg = fakePlugin(desc("name: WorldGuard\nmain: t.WG\nversion: 1.0\n"));
        Plugin gsit = fakePlugin(desc("name: GSit\nmain: t.GSit\nversion: 1.0\nsoftdepend: [WorldGuard]\n"));
        Plugin scp = fakePlugin(desc("name: sCheckPlayer\nmain: t.SCP\nversion: 1.0\nsoftdepend: [WorldGuard]\n"));

        installServerWith(lp, vs, cfp, ess, fc, wg, gsit, scp);

        DependencyManager manager = new DependencyManager(null);
        Set<String> lpDeps = manager.getDependents("LuckPerms", true);
        Set<String> wgDeps = manager.getDependents("WorldGuard", true);

        System.out.println("LP dependents in test: " + lpDeps);
        System.out.println("WG dependents in test: " + wgDeps);

        assertTrue(lpDeps.contains("ChatFilterPlus"), "LuckPerms обязан иметь зависимого ChatFilterPlus");
        assertTrue(lpDeps.contains("Essentials"), "LuckPerms обязан иметь зависимого Essentials");
        assertTrue(lpDeps.contains("fcChat"), "LuckPerms обязан иметь зависимого fcChat");
        assertTrue(wgDeps.contains("GSit"), "WorldGuard обязан иметь зависимого GSit");
        assertTrue(wgDeps.contains("sCheckPlayer"), "WorldGuard обязан иметь зависимого sCheckPlayer");
    }

    @Test
    void testUnloadedPluginDependentsResolvedFromActiveDependents() throws Exception {
        Plugin cfp = fakePlugin(desc("name: ChatFilterPlus\nmain: t.CFP\nversion: 1.0\nsoftdepend: [LuckPerms, PlaceholderAPI]\n"));
        Plugin ess = fakePlugin(desc("name: Essentials\nmain: t.ESS\nversion: 1.0\nsoftdepend: [Vault, LuckPerms]\n"));
        Plugin fc = fakePlugin(desc("name: fcChat\nmain: t.FC\nversion: 1.0\nsoftdepend: [PlaceholderAPI, LuckPerms]\n"));

        installServerWith(cfp, ess, fc);

        DependencyManager manager = new DependencyManager(null);
        Set<String> unloadedLpDeps = manager.getDependents("LuckPerms", true);

        assertEquals(3, unloadedLpDeps.size());
        assertTrue(unloadedLpDeps.contains("ChatFilterPlus"));
        assertTrue(unloadedLpDeps.contains("Essentials"));
        assertTrue(unloadedLpDeps.contains("fcChat"));
    }
}
