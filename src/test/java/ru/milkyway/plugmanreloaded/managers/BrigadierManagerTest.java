package ru.milkyway.plugmanreloaded.managers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class BrigadierManagerTest {

    public static class TestServerStub {
        public SimpleCommandMap commandMap;
    }

    public static class TestVanillaCommands {
        public Object dispatcher;
    }

    public static class TestConsole {
        public Object vanillaCommandDispatcher;
    }

    @BeforeAll
    static void installBukkitStub() {
        ru.milkyway.plugmanreloaded.BukkitServerMock.ensureInitialized();
    }

    @Test
    @DisplayName("Verify pendingSync debouncing with AtomicBoolean")
    void testSyncDebounce() throws Exception {
        BrigadierManager manager = new BrigadierManager(null);
        Field pendingField = BrigadierManager.class.getDeclaredField("pendingSync");
        pendingField.setAccessible(true);
        AtomicBoolean pendingSync = (AtomicBoolean) pendingField.get(manager);

        assertFalse(pendingSync.get());
        assertTrue(pendingSync.compareAndSet(false, true), "First CAS should transition pendingSync to true");
        assertFalse(pendingSync.compareAndSet(false, true), "Subsequent CAS should be rejected until reset");

        pendingSync.set(false);
        assertTrue(pendingSync.compareAndSet(false, true));
    }

    @Test
    @DisplayName("Verify cleanBrigadierDispatcher removes commands from root children and literals")
    void testCleanBrigadierDispatcher() throws Exception {
        Server server = Bukkit.getServer();

        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("testcommand"));
        dispatcher.register(LiteralArgumentBuilder.literal("myplugin:testcommand"));

        assertEquals(2, dispatcher.getRoot().getChildren().size());

        TestVanillaCommands vc = new TestVanillaCommands();
        vc.dispatcher = dispatcher;
        TestConsole console = new TestConsole();
        console.vanillaCommandDispatcher = vc;

        Field consoleField = server.getClass().getField("console");
        consoleField.set(server, console);

        BrigadierManager manager = new BrigadierManager(null);
        Method cleanMethod = BrigadierManager.class.getDeclaredMethod("cleanBrigadierDispatcher", Plugin.class, List.class);
        cleanMethod.setAccessible(true);

        Plugin dummyPlugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                BrigadierManagerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (p, m, a) -> m.getName().equals("getName") ? "MyPlugin" : null);

        cleanMethod.invoke(manager, dummyPlugin, List.of("myplugin:testcommand", "testcommand"));

        assertNull(dispatcher.getRoot().getChild("testcommand"));
        assertNull(dispatcher.getRoot().getChild("myplugin:testcommand"));
        assertEquals(0, dispatcher.getRoot().getChildren().size());
    }

    @Test
    @DisplayName("Verify removeMatchingCommands (the real production method) cleans knownCommands in SimpleCommandMap")
    void testUnregisterPluginCommandsInCommandMap() throws Exception {
        SimpleCommandMap commandMap = new SimpleCommandMap(Bukkit.getServer());
        Map<String, Command> knownCommands = ReflectionHelper.getFieldValue(SimpleCommandMap.class, commandMap, "knownCommands");

        final Plugin[] targetHolder = new Plugin[1];
        Plugin targetPlugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                BrigadierManagerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "DummyPlugin";
                    case "equals" -> proxy == args[0] || (targetHolder[0] != null && targetHolder[0] == args[0]);
                    default -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                });
        targetHolder[0] = targetPlugin;

        Constructor<PluginCommand> pcConstructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        pcConstructor.setAccessible(true);
        PluginCommand dummyCmd = pcConstructor.newInstance("dummy", targetPlugin);

        knownCommands.put("dummy", dummyCmd);
        knownCommands.put("dummyplugin:dummy", dummyCmd);

        Plugin otherPlugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                BrigadierManagerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "OtherPlugin";
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                });
        PluginCommand otherCmd = pcConstructor.newInstance("other", otherPlugin);
        knownCommands.put("other", otherCmd);

        Method removeMatching = BrigadierManager.class.getDeclaredMethod("removeMatchingCommands",
                Map.class, Plugin.class, String.class, SimpleCommandMap.class);
        removeMatching.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> removed = (List<String>) removeMatching.invoke(null, knownCommands, targetPlugin, "dummyplugin:", commandMap);

        assertTrue(removed.contains("dummy"), "removeMatchingCommands обязан вернуть удалённые метки");
        assertFalse(knownCommands.containsKey("dummy"),
                "removeMatchingCommands (реальный метод BrigadierManager) обязан удалить команду целевого плагина из knownCommands");
        assertFalse(knownCommands.containsKey("dummyplugin:dummy"));
        assertTrue(knownCommands.containsKey("other"), "команда чужого плагина не должна пострадать");
    }

    @Test
    @DisplayName("Verify cleanBrigadierDispatcher removes commands case-insensitively")
    void testCleanBrigadierDispatcherCaseInsensitive() throws Exception {
        Server server = Bukkit.getServer();

        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("CamelCaseCmd"));
        dispatcher.register(LiteralArgumentBuilder.literal("myplugin:SubModuleCmd"));

        assertEquals(2, dispatcher.getRoot().getChildren().size());

        TestVanillaCommands vc = new TestVanillaCommands();
        vc.dispatcher = dispatcher;
        TestConsole console = new TestConsole();
        console.vanillaCommandDispatcher = vc;

        Field consoleField = server.getClass().getField("console");
        consoleField.set(server, console);

        BrigadierManager manager = new BrigadierManager(null);
        Method cleanMethod = BrigadierManager.class.getDeclaredMethod("cleanBrigadierDispatcher", Plugin.class, List.class);
        cleanMethod.setAccessible(true);

        Plugin dummyPlugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                BrigadierManagerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (p, m, a) -> m.getName().equals("getName") ? "MyPlugin" : null);

        cleanMethod.invoke(manager, dummyPlugin, List.of("camelcasecmd", "myplugin:submodulecmd"));

        assertNull(dispatcher.getRoot().getChild("CamelCaseCmd"));
        assertNull(dispatcher.getRoot().getChild("myplugin:SubModuleCmd"));
        assertEquals(0, dispatcher.getRoot().getChildren().size());
    }

    @Test
    @DisplayName("Verify removeMatchingCommands matches PluginIdentifiableCommand without plugin prefix")
    void testUnregisterPluginIdentifiableCommandWithoutPrefix() throws Exception {
        SimpleCommandMap commandMap = new SimpleCommandMap(Bukkit.getServer());
        Map<String, Command> knownCommands = ReflectionHelper.getFieldValue(SimpleCommandMap.class, commandMap, "knownCommands");

        final Plugin[] targetHolder = new Plugin[1];
        Plugin targetPlugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                BrigadierManagerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TargetPlugin";
                    case "equals" -> proxy == args[0] || (targetHolder[0] != null && targetHolder[0] == args[0]);
                    default -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                });
        targetHolder[0] = targetPlugin;

        class CustomTestCommand extends Command implements org.bukkit.command.PluginIdentifiableCommand {
            private final Plugin plugin;

            CustomTestCommand(String name, Plugin plugin) {
                super(name);
                this.plugin = plugin;
            }

            @Override
            public Plugin getPlugin() {
                return plugin;
            }

            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                return false;
            }
        }

        Command customCommand = new CustomTestCommand("lp", targetPlugin);

        knownCommands.put("lp", customCommand);

        Method removeMatching = BrigadierManager.class.getDeclaredMethod("removeMatchingCommands",
                Map.class, Plugin.class, String.class, SimpleCommandMap.class);
        removeMatching.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> removed = (List<String>) removeMatching.invoke(null, knownCommands, targetPlugin, "targetplugin:", commandMap);

        assertTrue(removed.contains("lp"));
        assertFalse(knownCommands.containsKey("lp"));
    }

    @Test
    @DisplayName("Verify null safety on BrigadierManager methods")
    void testNullSafety() {
        BrigadierManager manager = new BrigadierManager(null);
        assertDoesNotThrow(() -> manager.unregisterPluginCommands(null));
        assertDoesNotThrow(manager::syncCommands);
    }
}
