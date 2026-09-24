package ru.milkyway.plugmanreloaded.utils;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class NettyGuardTest {

    interface CustomPluginInterface {}

    static class CustomPluginBaseHandler extends ChannelDuplexHandler implements CustomPluginInterface {}

    static class CustomSubHandler extends CustomPluginBaseHandler {}

    @BeforeAll
    static void installBukkitStub() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        if (field.get(null) != null) {
            return;
        }

        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                NettyGuardTest.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null);

        Server server = (Server) Proxy.newProxyInstance(
                NettyGuardTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPluginManager" -> pluginManager;
                    case "getOnlinePlayers" -> java.util.Collections.emptyList();
                    case "isPrimaryThread" -> true;
                    default -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null;
                });

        field.set(null, server);
    }

    @Test
    @DisplayName("Verify isClassLoadedBy detects classloader matching class, interfaces, and superclasses")
    void testIsClassLoadedByHierarchy() {
        ClassLoader testCl = NettyGuardTest.class.getClassLoader();
        ClassLoader otherCl = new URLClassLoader(new URL[0], null);

        assertTrue(NettyGuard.isClassLoadedBy(CustomSubHandler.class, testCl, new java.util.IdentityHashMap<>()),
                "Should identify class loaded directly by classloader");
        assertFalse(NettyGuard.isClassLoadedBy(CustomSubHandler.class, otherCl, new java.util.IdentityHashMap<>()),
                "Should return false for unrelated classloader");
        assertFalse(NettyGuard.isClassLoadedBy(String.class, testCl, new java.util.IdentityHashMap<>()),
                "Should return false for bootstrap JDK classes");
        assertFalse(NettyGuard.isClassLoadedBy(null, testCl, new java.util.IdentityHashMap<>()));
        assertFalse(NettyGuard.isClassLoadedBy(CustomSubHandler.class, null, new java.util.IdentityHashMap<>()));
    }

    @Test
    @DisplayName("Verify isClassLoadedBy identifies classes implementing plugin interfaces via dynamic classloader")
    void testIsClassLoadedByInterface() {
        ClassLoader pluginCl = new URLClassLoader(new URL[0], ChannelDuplexHandler.class.getClassLoader());
        ChannelHandler proxyHandler = (ChannelHandler) Proxy.newProxyInstance(
                pluginCl,
                new Class<?>[]{ChannelHandler.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null);

        assertTrue(NettyGuard.isClassLoadedBy(proxyHandler.getClass(), pluginCl, new java.util.IdentityHashMap<>()),
                "Proxy implementing interface on pluginCl should be identified as loaded by pluginCl");
    }

    @Test
    @DisplayName("Verify cleanPipeline removes only matching handlers from Netty pipeline")
    void testCleanPipeline() {
        ClassLoader systemCl = ChannelDuplexHandler.class.getClassLoader();
        ClassLoader pluginCl = new URLClassLoader(new URL[0], systemCl);

        ChannelHandler pluginHandler = (ChannelHandler) Proxy.newProxyInstance(
                pluginCl,
                new Class<?>[]{ChannelHandler.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null);

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("system_handler", new ChannelDuplexHandler());
        channel.pipeline().addLast("plugin_handler", pluginHandler);

        assertNotNull(channel.pipeline().get("system_handler"));
        assertNotNull(channel.pipeline().get("plugin_handler"));

        ClassLoader foreignCl = new URLClassLoader(new URL[0], systemCl);
        NettyGuard.cleanPipeline(channel.pipeline(), foreignCl, new java.util.IdentityHashMap<>());
        assertNotNull(channel.pipeline().get("system_handler"), "Foreign classloader should keep system handler");
        assertNotNull(channel.pipeline().get("plugin_handler"), "Foreign classloader should keep plugin handler");

        NettyGuard.cleanPipeline(channel.pipeline(), pluginCl, new java.util.IdentityHashMap<>());
        assertNull(channel.pipeline().get("plugin_handler"), "Plugin handler should be removed by cleanPipeline");
        assertNotNull(channel.pipeline().get("system_handler"), "System handler should remain intact");
        channel.close();
    }

    @Test
    @DisplayName("Verify null safety on NettyGuard public methods")
    void testNullSafety() {
        assertDoesNotThrow(() -> NettyGuard.cleanPlayerPipelines(null));
        assertFalse(NettyGuard.hasInjectedHandlers(null));
    }

    @Test
    @DisplayName("Verify cleanPipeline synchronizes EventLoop before returning")
    void testCleanPipelineSynchronizesEventLoopBeforeReturn() throws Exception {
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            LocalChannel channel = new LocalChannel();
            group.register(channel).sync();

            ClassLoader systemCl = ChannelHandler.class.getClassLoader();
            ClassLoader pluginCl = new URLClassLoader(new URL[0], systemCl);

            AtomicBoolean handlerRemovedFinished = new AtomicBoolean(false);

            ChannelHandler pluginHandler = (ChannelHandler) Proxy.newProxyInstance(
                    pluginCl,
                    new Class<?>[]{ChannelHandler.class},
                    (proxy, method, args) -> {
                        if ("handlerRemoved".equals(method.getName())) {
                            Thread.sleep(50);
                            handlerRemovedFinished.set(true);
                            return null;
                        }
                        if (method.getReturnType().isPrimitive()) {
                            return method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0;
                        }
                        return null;
                    });

            channel.pipeline().addLast("test_plugin_handler", pluginHandler);

            NettyGuard.cleanPipeline(channel.pipeline(), pluginCl, new java.util.IdentityHashMap<>());

            assertTrue(handlerRemovedFinished.get(), "cleanPipeline must wait for EventLoop to finish handler removal");

            channel.close().sync();
        } finally {
            group.shutdownGracefully().sync();
        }
    }

    @Test
    @DisplayName("Verify cleanPipeline removes CombinedChannelDuplexHandler when inner handler is from plugin")
    void testCombinedChannelDuplexHandlerRemoval() {
        ClassLoader systemCl = ChannelDuplexHandler.class.getClassLoader();
        ClassLoader pluginCl = new URLClassLoader(new URL[0], systemCl);

        io.netty.channel.ChannelInboundHandler inboundPlugin = (io.netty.channel.ChannelInboundHandler) Proxy.newProxyInstance(
                pluginCl,
                new Class<?>[]{io.netty.channel.ChannelInboundHandler.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null);

        io.netty.channel.CombinedChannelDuplexHandler<?, ?> combined = new io.netty.channel.CombinedChannelDuplexHandler<>(
                inboundPlugin, new io.netty.channel.ChannelOutboundHandlerAdapter());

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("combined_handler", combined);

        assertNotNull(channel.pipeline().get("combined_handler"));

        ClassLoader unrelatedCl = new URLClassLoader(new URL[0], systemCl);
        NettyGuard.cleanPipeline(channel.pipeline(), unrelatedCl, new java.util.HashMap<>());
        assertNotNull(channel.pipeline().get("combined_handler"));

        NettyGuard.cleanPipeline(channel.pipeline(), pluginCl, new java.util.HashMap<>());
        assertNull(channel.pipeline().get("combined_handler"));
        channel.close();
    }

    @Test
    @DisplayName("Verify isClassLoadedBy detects class loaded by child classloader of plugin")
    void testChildClassLoaderDetection() {
        ClassLoader pluginCl = new URLClassLoader(new URL[0], getClass().getClassLoader());
        ClassLoader childCl = new URLClassLoader(new URL[0], pluginCl);

        ChannelHandler childHandler = (ChannelHandler) Proxy.newProxyInstance(
                childCl,
                new Class<?>[]{ChannelHandler.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null);

        assertTrue(NettyGuard.isClassLoadedBy(childHandler.getClass(), pluginCl, new java.util.HashMap<>()),
                "Classes loaded by child classloaders of plugin must be recognized as loaded by plugin");
    }
}
