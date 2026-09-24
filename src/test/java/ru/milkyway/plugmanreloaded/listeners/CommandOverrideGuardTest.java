package ru.milkyway.plugmanreloaded.listeners;

import ru.milkyway.plugmanreloaded.commands.CommandOverrideListener;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.BukkitServerMock;
import ru.milkyway.plugmanreloaded.PlugManReloaded;
import ru.milkyway.plugmanreloaded.managers.ConfigManager;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandOverrideGuardTest {

    @BeforeAll
    static void initServer() {
        BukkitServerMock.ensureInitialized();
    }

    @Test
    void neitherHandlerCancelsTheEventBeforeCheckingThatWeCanServeIt() throws Exception {
        PlugManReloaded plugin = createPluginWithOverrideEnabled();
        CommandOverrideListener listener = new CommandOverrideListener(plugin);

        Player playerWithoutPerm = createSender(Player.class, Set.of("bukkit.command.plugins"));
        PlayerCommandPreprocessEvent playerEvent = new PlayerCommandPreprocessEvent(playerWithoutPerm, "/plugins");
        listener.onPlayerCommand(playerEvent);
        assertFalse(playerEvent.isCancelled());

        ConsoleCommandSender consoleWithoutPerm = createSender(ConsoleCommandSender.class, Set.of("bukkit.command.plugins"));
        ServerCommandEvent serverEvent = new ServerCommandEvent(consoleWithoutPerm, "plugins");
        listener.onServerCommand(serverEvent);
        assertFalse(serverEvent.isCancelled());
    }

    @Test
    void canServeListRequiresOurOwnPermissionNotTheVanillaOne() {
        CommandOverrideListener listener = new CommandOverrideListener(null);

        CommandSender vanillaOnly = createSender(CommandSender.class, Set.of("bukkit.command.plugins"));
        assertFalse(listener.canServeList(vanillaOnly));

        CommandSender listPerm = createSender(CommandSender.class, Set.of("plugmanreloaded.list"));
        assertTrue(listener.canServeList(listPerm));

        CommandSender adminPerm = createSender(CommandSender.class, Set.of("plugmanreloaded.admin"));
        assertTrue(listener.canServeList(adminPerm));
    }

    private static PlugManReloaded createPluginWithOverrideEnabled() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Unsafe unsafe = (Unsafe) f.get(null);

        PlugManReloaded plugin = (PlugManReloaded) unsafe.allocateInstance(PlugManReloaded.class);
        ConfigManager configManager = (ConfigManager) unsafe.allocateInstance(ConfigManager.class);
        ru.milkyway.plugmanreloaded.configs.MainConfig mainConfig = (ru.milkyway.plugmanreloaded.configs.MainConfig) unsafe.allocateInstance(ru.milkyway.plugmanreloaded.configs.MainConfig.class);

        Field mainConfigField = ConfigManager.class.getDeclaredField("mainConfig");
        mainConfigField.setAccessible(true);
        mainConfigField.set(configManager, mainConfig);

        Field overrideField = ru.milkyway.plugmanreloaded.configs.MainConfig.class.getDeclaredField("overridePluginsCommand");
        overrideField.setAccessible(true);
        overrideField.setBoolean(mainConfig, true);

        Field cmField = PlugManReloaded.class.getDeclaredField("configManager");
        cmField.setAccessible(true);
        cmField.set(plugin, configManager);

        return plugin;
    }

    @SuppressWarnings("unchecked")
    private static <T extends CommandSender> T createSender(Class<T> type, Set<String> permissions) {
        return (T) Proxy.newProxyInstance(
                CommandOverrideGuardTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals("getServer")) {
                        return org.bukkit.Bukkit.getServer();
                    }
                    if (method.getName().equals("hasPermission") && args != null && args.length == 1) {
                        return permissions.contains(args[0]);
                    }
                    if (method.getName().equals("getName")) {
                        return "TestSender";
                    }
                    return method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                });
    }
}
