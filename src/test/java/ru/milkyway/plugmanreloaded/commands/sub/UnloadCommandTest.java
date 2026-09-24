package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.BukkitServerMock;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.api.PluginResult;
import ru.milkyway.plugmanreloaded.bridge.PlatformBridge;
import ru.milkyway.plugmanreloaded.configs.MainConfig;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;
import ru.milkyway.plugmanreloaded.managers.SafetyManager;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class UnloadCommandTest {

    @BeforeAll
    static void initBukkit() {
        BukkitServerMock.ensureInitialized();
    }

    @AfterEach
    void cleanup() {
        BukkitServerMock.clearRegisteredPlugins();
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static void setField(Object target, Class<?> clz, String name, Object value) throws Exception {
        Field f = clz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        setField(target, target.getClass(), name, value);
    }

    private static Plugin createMockPlugin(String name) throws Exception {
        String yaml = "name: " + name + "\nversion: '1.0'\nmain: org.example.Test\n";
        PluginDescriptionFile desc = new PluginDescriptionFile(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return (Plugin) Proxy.newProxyInstance(
                UnloadCommandTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getDescription" -> desc;
                    case "isEnabled" -> true;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> args.length > 0 && args[0] == proxy;
                    case "toString" -> name;
                    default -> null;
                }
        );
    }

    private static CommandSender createSender() {
        return (CommandSender) Proxy.newProxyInstance(
                UnloadCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "CONSOLE";
                    case "hasPermission" -> true;
                    default -> null;
                }
        );
    }

    private static class CommandHarness {
        final PlugManReloaded plugin;
        final ConfirmationManager confirmationManager;
        final ConfigManager configManager;
        final MainConfig mainConfig;
        final Plugin targetPlugin;
        final AtomicBoolean unloadCalled = new AtomicBoolean(false);
        final List<String> sentActions = new ArrayList<>();

        CommandHarness(boolean safeMode) throws Exception {
            sun.misc.Unsafe unsafe = getUnsafe();
            plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
            confirmationManager = new ConfirmationManager();
            configManager = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);
            mainConfig = (MainConfig) unsafe.allocateInstance(MainConfig.class);
            setField(mainConfig, "safeModeEnabled", safeMode);
            setField(configManager, "mainConfig", mainConfig);
            setField(configManager, "messagesConfig", new YamlConfiguration());

            targetPlugin = createMockPlugin("TargetPlugin");
            BukkitServerMock.registerPlugin(targetPlugin);

            setField(plugin, "confirmationManager", confirmationManager);
            setField(plugin, "configManager", configManager);
        }
    }

    private UnloadCommand createUnloadCommand(CommandHarness h, SafetyManager.SafetyAssessment assessment) throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        SafetyManager customAdvisor = new SafetyManager(h.plugin) {
            @Override
            public SafetyAssessment assess(Plugin p) {
                return assessment;
            }
        };

        PlatformBridge bridge = (PlatformBridge) Proxy.newProxyInstance(
                UnloadCommandTest.class.getClassLoader(),
                new Class<?>[]{PlatformBridge.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unloadPlugin" -> {
                        h.unloadCalled.set(true);
                        yield PluginResult.ofSuccess("unload.success");
                    }
                    default -> null;
                }
        );

        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        setField(lm, "bridge", bridge);
        setField(lm, "safetyManager", customAdvisor);
        setField(lm, "pluginFileCache", new WeakHashMap<>());
        setField(h.plugin, "pluginLifecycleManager", lm);

        return new UnloadCommand(h.plugin) {
            @Override
            protected void sendAction(CommandSender s, String path) {
                h.sentActions.add(path);
            }

            @Override
            protected void sendAction(CommandSender s, String path, Map<String, String> placeholders) {
                h.sentActions.add(path);
            }

            @Override
            protected boolean checkProtected(CommandSender s, Plugin p) {
                return false;
            }

            @Override
            protected boolean isPluginLocked(CommandSender s, String pluginName, String actionType) {
                return false;
            }
        };
    }

    @Test
    void safeModePromptsConfirmationWhenNoFlagProvided() throws Exception {
        CommandHarness h = new CommandHarness(true);
        SafetyManager.SafetyAssessment risky = new SafetyManager.SafetyAssessment(
                SafetyManager.PluginRiskLevel.LOW_LEVEL_NETWORK,
                Collections.emptySet()
        );

        UnloadCommand cmd = createUnloadCommand(h, risky);
        CommandSender sender = createSender();

        boolean handled = cmd.execute(sender, new String[]{"unload", "TargetPlugin"});
        assertTrue(handled);
        assertFalse(h.unloadCalled.get());
        assertTrue(h.sentActions.contains("unload.confirm"));
    }

    @Test
    void safeModeBypassedWithShortYesFlag() throws Exception {
        CommandHarness h = new CommandHarness(true);
        SafetyManager.SafetyAssessment risky = new SafetyManager.SafetyAssessment(
                SafetyManager.PluginRiskLevel.LOW_LEVEL_NETWORK,
                Collections.emptySet()
        );

        UnloadCommand cmd = createUnloadCommand(h, risky);
        CommandSender sender = createSender();

        boolean handled = cmd.execute(sender, new String[]{"unload", "TargetPlugin", "-y"});
        assertTrue(handled);
        assertTrue(h.unloadCalled.get());
        assertFalse(h.sentActions.contains("unload.confirm"));
    }

    @Test
    void safeModeBypassedWithSingleDashYesFlag() throws Exception {
        CommandHarness h = new CommandHarness(true);
        SafetyManager.SafetyAssessment risky = new SafetyManager.SafetyAssessment(
                SafetyManager.PluginRiskLevel.LOW_LEVEL_NETWORK,
                Collections.emptySet()
        );

        UnloadCommand cmd = createUnloadCommand(h, risky);
        CommandSender sender = createSender();

        boolean handled = cmd.execute(sender, new String[]{"unload", "TargetPlugin", "-yes"});
        assertTrue(handled);
        assertTrue(h.unloadCalled.get());
        assertFalse(h.sentActions.contains("unload.confirm"));
    }

    @Test
    void safeModeBypassedWithDoubleDashYesFlag() throws Exception {
        CommandHarness h = new CommandHarness(true);
        SafetyManager.SafetyAssessment risky = new SafetyManager.SafetyAssessment(
                SafetyManager.PluginRiskLevel.LOW_LEVEL_NETWORK,
                Collections.emptySet()
        );

        UnloadCommand cmd = createUnloadCommand(h, risky);
        CommandSender sender = createSender();

        boolean handled = cmd.execute(sender, new String[]{"unload", "TargetPlugin", "--yes"});
        assertTrue(handled);
        assertTrue(h.unloadCalled.get());
        assertFalse(h.sentActions.contains("unload.confirm"));
    }

    @Test
    void safeModeBypassedWithLegacyForceFlag() throws Exception {
        CommandHarness h = new CommandHarness(true);
        SafetyManager.SafetyAssessment risky = new SafetyManager.SafetyAssessment(
                SafetyManager.PluginRiskLevel.LOW_LEVEL_NETWORK,
                Collections.emptySet()
        );

        UnloadCommand cmd = createUnloadCommand(h, risky);
        CommandSender sender = createSender();

        boolean handled = cmd.execute(sender, new String[]{"unload", "TargetPlugin", "-f"});
        assertTrue(handled);
        assertTrue(h.unloadCalled.get());
        assertFalse(h.sentActions.contains("unload.confirm"));
    }
}
