package ru.milkyway.plugmanreloaded.commands.sub;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.BukkitServerMock;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.commands.CommandContext;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;
import ru.milkyway.plugmanreloaded.managers.ConfirmationManager;
import ru.milkyway.plugmanreloaded.managers.DependencyManager;
import ru.milkyway.plugmanreloaded.managers.LifecycleManager;
import ru.milkyway.plugmanreloaded.utils.PluginJarIndex;
import ru.milkyway.plugmanreloaded.update.UpdateModels.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCommandTest {

    @BeforeAll
    static void initBukkit() {
        BukkitServerMock.ensureInitialized();
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

    private static CommandSender createSender() {
        return (CommandSender) Proxy.newProxyInstance(
                UpdateCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "getName" -> "CONSOLE";
                    default -> null;
                }
        );
    }

    @Test
    @DisplayName("Verify cleanVersion stripping and fallback behavior")
    void testCleanVersion() throws Exception {
        UpdateCommand cmd = new UpdateCommand(null);
        Method clean = UpdateCommand.class.getDeclaredMethod("cleanVersion", String.class);
        clean.setAccessible(true);

        assertEquals("—", clean.invoke(cmd, (String) null));
        assertEquals("—", clean.invoke(cmd, "   "));
        assertEquals("1.2.3", clean.invoke(cmd, "1.2.3"));
        assertEquals("1.2.3", clean.invoke(cmd, "v1.2.3"));
        assertEquals("1.2.3", clean.invoke(cmd, "V1.2.3"));
        assertEquals("2.0-SNAPSHOT", clean.invoke(cmd, "v2.0-SNAPSHOT"));
    }

    @Test
    @DisplayName("Verify formatSource formatting for paid and free sources")
    void testFormatSource() throws Exception {
        UpdateCommand cmd = new UpdateCommand(null);
        Method formatSource = UpdateCommand.class.getDeclaredMethod("formatSource", String.class);
        formatSource.setAccessible(true);

        assertEquals("unknown", formatSource.invoke(cmd, (String) null));
        assertEquals("unknown", formatSource.invoke(cmd, ""));
        assertEquals("Modrinth", formatSource.invoke(cmd, "modrinth"));
        assertEquals("SpigotMC (paid)", formatSource.invoke(cmd, "spigot-premium"));
        assertEquals("RuSpigot (paid)", formatSource.invoke(cmd, "ruspigot-premium"));
    }

    @Test
    @DisplayName("Verify formatChannel formatting for release, beta, alpha")
    void testFormatChannel() throws Exception {
        UpdateCommand cmd = new UpdateCommand(null);
        Method formatChannel = UpdateCommand.class.getDeclaredMethod("formatChannel", String.class);
        formatChannel.setAccessible(true);

        assertEquals("RELEASE", formatChannel.invoke(cmd, (String) null));
        assertEquals("RELEASE", formatChannel.invoke(cmd, "release"));
        assertEquals("BETA", formatChannel.invoke(cmd, "beta"));
        assertEquals("ALPHA", formatChannel.invoke(cmd, "alpha"));
    }

    @Test
    @DisplayName("Verify handleCancel consumes token for all/batch update confirmation sessions")
    void testHandleCancelBatchSessionTokenConsumption() throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        ConfirmationManager confirmationManager = new ConfirmationManager();
        setField(plugin, "confirmationManager", confirmationManager);

        ConfigManager configManager = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);
        setField(configManager, "messagesConfig", new YamlConfiguration());
        setField(plugin, "configManager", configManager);

        List<String> sentActions = new ArrayList<>();
        UpdateCommand cmd = new UpdateCommand(plugin) {
            @Override
            protected void sendAction(CommandSender s, String path) {
                sentActions.add(path);
            }

            @Override
            protected void sendAction(CommandSender s, String path, Map<String, String> placeholders) {
                sentActions.add(path);
            }
        };

        CommandSender sender = createSender();
        String token = confirmationManager.createSession(sender, "update", "all");

        CommandContext ctx = CommandContext.parse(sender, new String[]{"update", "cancel", "all", "-f", token}, cmd);

        Method handleCancel = UpdateCommand.class.getDeclaredMethod("handleCancel", CommandSender.class, CommandContext.class);
        handleCancel.setAccessible(true);
        boolean result = (boolean) handleCancel.invoke(cmd, sender, ctx);

        assertTrue(result);
        assertFalse(confirmationManager.validateAndConsume(sender, "update", "all", token));
        assertTrue(sentActions.contains("update.cancelled-all"));
    }

    @Test
    @DisplayName("Verify dependentsOf falls back to DependencyGraph for unloaded target plugins")
    void testDependentsOfFallsBackToGraphManager() throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        LifecycleManager lm = (LifecycleManager) unsafe.allocateInstance(LifecycleManager.class);
        PluginJarIndex jarIndex = new PluginJarIndex(null);
        setField(lm, "jarIndex", jarIndex);

        DependencyManager graph = new DependencyManager(null) {
            @Override
            public Set<String> getDependents(String name, boolean includeSoftDepends) {
                return Set.of("DependentChild");
            }
        };

        setField(lm, "dependencyManager", graph);
        setField(plugin, "pluginLifecycleManager", lm);

        UpdateCommand cmd = new UpdateCommand(plugin);

        PluginIdentity identity = new PluginIdentity("UnloadedPlugin", "org.test.Main", "1.0", List.of(), null, null, null, null);
        UpdateCandidate candidate = UpdateCandidate.noSource(identity);

        Method dependentsOf = UpdateCommand.class.getDeclaredMethod("dependentsOf", UpdateCandidate.class);
        dependentsOf.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> dependents = (Set<String>) dependentsOf.invoke(cmd, candidate);

        assertEquals(Set.of("DependentChild"), dependents);
    }

    @Test
    @DisplayName("Verify installSequentially only reloads dependents of plugins that were actually hot-swapped")
    void testInstallSequentiallyOnlyReloadsDependentsOfSuccessfulHotSwaps() throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        ConfigManager configManager = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);
        setField(configManager, "messagesConfig", new YamlConfiguration());
        setField(plugin, "configManager", configManager);

        PluginIdentity idA = new PluginIdentity("PluginA", "org.test.A", "1.0", List.of(), null, null, null, null);
        UpdateCandidate candA = UpdateCandidate.noSource(idA);

        PluginIdentity idB = new PluginIdentity("PluginB", "org.test.B", "1.0", List.of(), null, null, null, null);
        UpdateCandidate candB = UpdateCandidate.noSource(idB);

        List<String> sentActions = new ArrayList<>();
        UpdateCommand cmd = new UpdateCommand(plugin) {
            @Override
            protected void sendAction(CommandSender s, String path) {
                sentActions.add(path);
            }

            @Override
            protected void sendAction(CommandSender s, String path, Map<String, String> placeholders) {
                sentActions.add(path);
            }
        };

        List<UpdateCandidate> queue = List.of(candA, candB);
        List<UpdateCandidate> hotSwappedNow = new ArrayList<>();
        List<String> pendingRestartList = new ArrayList<>();

        CommandSender sender = createSender();
        cmd.recordInstallOutcome(sender, queue, 0, candA,
                new InstallResult(InstallStatus.INSTALLED, "PluginA", "1.0", "2.0", ""),
                pendingRestartList, hotSwappedNow);

        cmd.recordInstallOutcome(sender, queue, 1, candB,
                new InstallResult(InstallStatus.DOWNLOAD_FAILED, "PluginB", "1.0", "2.0", "network error"),
                pendingRestartList, hotSwappedNow);

        assertEquals(1, hotSwappedNow.size());
        assertTrue(hotSwappedNow.contains(candA));
        assertFalse(hotSwappedNow.contains(candB));
        assertTrue(pendingRestartList.isEmpty());
        assertTrue(sentActions.contains("update.all-install-item-success"));
        assertTrue(sentActions.contains("update.all-install-item-failed"));
    }

    @Test
    @DisplayName("Verify installSequentially handles PENDING_RESTART with dedicated notification and tracking")
    void testInstallSequentiallyPendingRestartNotification() throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        ConfigManager configManager = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);
        setField(configManager, "messagesConfig", new YamlConfiguration());
        setField(plugin, "configManager", configManager);

        PluginIdentity idPending = new PluginIdentity("PaperBootstrapper", "org.test.Boot", "1.0", List.of(), null, null, null, null);
        UpdateCandidate candPending = UpdateCandidate.noSource(idPending);

        List<String> sentActions = new ArrayList<>();
        UpdateCommand cmd = new UpdateCommand(plugin) {
            @Override
            protected void sendAction(CommandSender s, String path) {
                sentActions.add(path);
            }

            @Override
            protected void sendAction(CommandSender s, String path, Map<String, String> placeholders) {
                sentActions.add(path);
            }
        };

        List<UpdateCandidate> queue = List.of(candPending);
        List<UpdateCandidate> hotSwappedNow = new ArrayList<>();
        List<String> pendingRestartList = new ArrayList<>();

        CommandSender sender = createSender();
        cmd.recordInstallOutcome(sender, queue, 0, candPending,
                new InstallResult(InstallStatus.PENDING_RESTART, "PaperBootstrapper", "1.0", "2.0", "restart required"),
                pendingRestartList, hotSwappedNow);

        assertEquals(List.of("PaperBootstrapper"), pendingRestartList);
        assertTrue(sentActions.contains("update.all-install-item-pending"));
        assertTrue(hotSwappedNow.isEmpty());
    }
}
