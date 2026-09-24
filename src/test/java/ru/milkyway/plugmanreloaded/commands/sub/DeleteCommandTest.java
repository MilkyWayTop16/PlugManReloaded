package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.managers.DependencyManager;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DeleteCommandTest {

    private static PluginDescriptionFile desc(String yaml) throws Exception {
        return new PluginDescriptionFile(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Plugin fakePlugin(PluginDescriptionFile desc) {
        return (Plugin) Proxy.newProxyInstance(
                DeleteCommandTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> desc.getName();
                    case "getDescription" -> desc;
                    case "isEnabled" -> true;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> desc.getName().hashCode();
                    case "toString" -> "Plugin[" + desc.getName() + "]";
                    default -> null;
                }
        );
    }

    private static void installServer(Plugin... plugins) throws Exception {
        ru.milkyway.plugmanreloaded.BukkitServerMock.ensureInitialized();
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);

        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                DeleteCommandTest.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlugins" -> plugins;
                    case "getPlugin" -> {
                        String name = (String) args[0];
                        Plugin match = null;
                        for (Plugin p : plugins) {
                            if (p.getName().equalsIgnoreCase(name)) {
                                match = p;
                                break;
                            }
                        }
                        yield match;
                    }
                    default -> null;
                }
        );

        ConsoleCommandSender console = (ConsoleCommandSender) Proxy.newProxyInstance(
                DeleteCommandTest.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class},
                (proxy, method, args) -> null
        );

        Server server = (Server) Proxy.newProxyInstance(
                DeleteCommandTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPluginManager" -> pluginManager;
                    case "getConsoleSender" -> console;
                    case "getLogger" -> Logger.getLogger("DeleteCommandTest");
                    case "isPrimaryThread" -> true;
                    default -> null;
                }
        );

        field.set(null, server);
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanupServer() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static Method resolveDependentsForWarningMethod() throws Exception {
        Method m = DependencyManager.class.getDeclaredMethod("resolveDependentsWithFallback",
                Set.class, DependencyManager.class, String.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    void testDeleteCommandFallsBackToGraphDependentsWhenPluginIsUnloaded() throws Exception {
        Plugin cfp = fakePlugin(desc("name: ChatFilterPlus\nmain: t.CFP\nversion: 1.0\nsoftdepend: [LuckPerms, PlaceholderAPI]\n"));
        Plugin ess = fakePlugin(desc("name: Essentials\nmain: t.ESS\nversion: 1.0\nsoftdepend: [Vault, LuckPerms]\n"));
        installServer(cfp, ess);

        DependencyManager graphManager = new DependencyManager(null);

        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) resolveDependentsForWarningMethod()
                .invoke(null, Set.of(), graphManager, "LuckPerms");

        assertEquals(2, result.size(),
                "DeleteCommand.resolveDependentsForWarning обязан подтянуть зависимых из графа, когда LuckPerms "
                        + "выгружен и SafetyAdvisor (работающий только с живыми Plugin) не нашёл ничего сам");
        assertTrue(result.contains("ChatFilterPlus"));
        assertTrue(result.contains("Essentials"));
    }

    @Test
    void testDeleteCommandDoesNotOverrideRealDependentsWithGraphFallback() throws Exception {
        DependencyManager graphManager = new DependencyManager(null);
        Set<String> directDependents = Set.of("AlreadyFoundDependent");

        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) resolveDependentsForWarningMethod()
                .invoke(null, directDependents, graphManager, "AnyPlugin");

        assertEquals(directDependents, result,
                "если SafetyAdvisor уже нашёл настоящих зависимых у ЗАГРУЖЕННОГО плагина, фолбэк на "
                        + "DependencyManager не должен их перезаписывать своим (возможно, отличающимся) списком");
    }

    @Test
    void testDeleteCommandFallbackReturnsEmptyWhenGraphManagerIsUnavailable() throws Exception {
        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) resolveDependentsForWarningMethod()
                .invoke(null, Set.of(), null, "AnyPlugin");

        assertTrue(result.isEmpty(), "без DependencyGraph фолбэк обязан вернуть пустой набор, а не упасть с NPE");
    }
}
