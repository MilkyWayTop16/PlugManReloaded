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

class DisableCommandTest {

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
                DisableCommandTest.class.getClassLoader(),
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

    private static CommandSender createSender() {
        return (CommandSender) Proxy.newProxyInstance(
                DisableCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "getName" -> "CONSOLE";
                    default -> null;
                }
        );
    }

    private static final class CommandHarness {
        final PlugManReloaded plugin;
        final ConfirmationManager confirmationManager;
        final ConfigManager configManager;
        final MainConfig mainConfig;
        final List<String> sentActions = new ArrayList<>();
        final AtomicBoolean disableCalled = new AtomicBoolean(false);
        final Plugin targetPlugin;

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

    @Test
    void cancelIsHandledBeforeAnyPluginLookup() throws Exception {
        CommandHarness h = new CommandHarness(false);
        CommandSender sender = createSender();
        String token = h.confirmationManager.createSession(sender, "disable", "TargetPlugin");

        DisableCommand cmd = new DisableCommand(h.plugin) {
            @Override
            protected void sendAction(CommandSender s, String path) {
                h.sentActions.add(path);
            }

            @Override
            protected void sendAction(CommandSender s, String path, Map<String, String> placeholders) {
                h.sentActions.add(path);
            }
        };

        sun.misc.Unsafe unsafe = getUnsafe();
        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        setField(h.plugin, "pluginLifecycleManager", lm);

        boolean handled = cmd.execute(sender, new String[]{"disable", "cancel", "TargetPlugin", "-f", token});
        assertTrue(handled);

        assertFalse(h.confirmationManager.validateAndConsume(sender, "disable", "TargetPlugin", token));
        assertFalse(h.disableCalled.get());
        assertTrue(h.sentActions.contains("disable.cancelled"));
    }

    @Test
    void safeModeConfirmationGatesTheActualDisableCall() throws Exception {
        CommandHarness h = new CommandHarness(true);
        CommandSender sender = createSender();

        SafetyManager.SafetyAssessment risky = new SafetyManager.SafetyAssessment(
                SafetyManager.PluginRiskLevel.HAS_DEPENDENTS,
                Set.of("ChildPlugin")
        );

        sun.misc.Unsafe unsafe = getUnsafe();
        SafetyManager customAdvisor = new SafetyManager(h.plugin) {
            @Override
            public SafetyAssessment assess(Plugin p) {
                return risky;
            }
        };

        PlatformBridge bridge = (PlatformBridge) Proxy.newProxyInstance(
                DisableCommandTest.class.getClassLoader(),
                new Class<?>[]{PlatformBridge.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "disablePlugin" -> {
                        h.disableCalled.set(true);
                        yield PluginResult.ofSuccess("disable.success");
                    }
                    default -> null;
                }
        );

        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        setField(lm, "bridge", bridge);
        setField(lm, "safetyManager", customAdvisor);
        setField(lm, "pluginFileCache", new WeakHashMap<>());
        setField(h.plugin, "pluginLifecycleManager", lm);

        DisableCommand cmd = new DisableCommand(h.plugin) {
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

        boolean handled = cmd.execute(sender, new String[]{"disable", "TargetPlugin"});
        assertTrue(handled);

        assertFalse(h.disableCalled.get());
        assertTrue(h.sentActions.contains("disable.confirm"));
    }

    @Test
    void tokenBasedConfirmationForcesTheSkipJustLikeUnloadCommand() throws Exception {
        CommandHarness h = new CommandHarness(true);
        CommandSender sender = createSender();
        String token = h.confirmationManager.createSession(sender, "disable", "TargetPlugin");

        SafetyManager.SafetyAssessment risky = new SafetyManager.SafetyAssessment(
                SafetyManager.PluginRiskLevel.HAS_DEPENDENTS,
                Set.of("ChildPlugin")
        );

        sun.misc.Unsafe unsafe = getUnsafe();
        SafetyManager customAdvisor = new SafetyManager(h.plugin) {
            @Override
            public SafetyAssessment assess(Plugin p) {
                return risky;
            }
        };

        PlatformBridge bridge = (PlatformBridge) Proxy.newProxyInstance(
                DisableCommandTest.class.getClassLoader(),
                new Class<?>[]{PlatformBridge.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "disablePlugin" -> {
                        h.disableCalled.set(true);
                        yield PluginResult.ofSuccess("disable.success");
                    }
                    default -> null;
                }
        );

        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        setField(lm, "bridge", bridge);
        setField(lm, "safetyManager", customAdvisor);
        setField(lm, "pluginFileCache", new WeakHashMap<>());
        setField(h.plugin, "pluginLifecycleManager", lm);

        DisableCommand cmd = new DisableCommand(h.plugin) {
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

        boolean handled = cmd.execute(sender, new String[]{"disable", "TargetPlugin", "-f", token});
        assertTrue(handled);
        assertTrue(h.disableCalled.get());

        assertFalse(h.confirmationManager.validateAndConsume(sender, "disable", "TargetPlugin", token));

        h.disableCalled.set(false);
        h.sentActions.clear();
        boolean secondRun = cmd.execute(sender, new String[]{"disable", "TargetPlugin", "-f", token});
        assertTrue(secondRun);
        assertFalse(h.disableCalled.get());
        assertTrue(h.sentActions.contains("errors.confirm-expired"));
    }
}
