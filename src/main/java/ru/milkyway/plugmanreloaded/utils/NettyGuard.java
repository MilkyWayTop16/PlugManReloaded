package ru.milkyway.plugmanreloaded.utils;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class NettyGuard {

    private static final long SYNC_TIMEOUT_MS = 1000L;

    private NettyGuard() {}

    record CacheKey(Class<?> clazz, ClassLoader loader) {}

    public static void cleanPlayerPipelines(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null || Bukkit.getServer() == null) return;
        ClassLoader targetCl = targetPlugin.getClass().getClassLoader();
        if (targetCl == null) return;

        int failed = 0;
        Map<CacheKey, Boolean> cache = new HashMap<>();
        Set<EventLoop> eventLoopsToSync = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Channel channel = getPlayerChannel(player);
                if (channel == null || !channel.isOpen()) continue;
                cleanPipeline(channel.pipeline(), targetCl, cache, eventLoopsToSync);
            } catch (Exception | LinkageError t) {
                failed++;
                Log.debug("nettyguard.player-cleanup-error", t, "player", player.getName());
            }
        }

        try {
            Object serverConnection = getServerConnection();
            if (serverConnection != null) {
                List<Channel> serverChannels = getServerChannels(serverConnection);
                for (Channel channel : serverChannels) {
                    try {
                        cleanPipeline(channel.pipeline(), targetCl, cache, eventLoopsToSync);
                    } catch (Exception | LinkageError t) {
                        failed++;
                    }
                }
            }
        } catch (Exception | LinkageError t) {
            Log.debug("nettyguard.server-cleanup-failed", t, "plugin", targetPlugin.getName());
        }

        syncEventLoops(eventLoopsToSync);

        if (failed > 0) {
            Log.debug("nettyguard.cleanup-failed-count", "count", String.valueOf(failed), "plugin", targetPlugin.getName());
        }
    }

    public static boolean cleanPipeline(ChannelPipeline pipeline, ClassLoader targetCl, Map<CacheKey, Boolean> cache) {
        return cleanPipeline(pipeline, targetCl, cache, null);
    }

    static boolean cleanPipeline(ChannelPipeline pipeline, ClassLoader targetCl, Map<CacheKey, Boolean> cache, @Nullable Set<EventLoop> eventLoopsToSync) {
        if (pipeline == null) return false;
        List<String> toRemove = new ArrayList<>();

        List<String> names;
        synchronized (pipeline) {
            names = new ArrayList<>(pipeline.names());
        }

        for (String handlerName : names) {
            ChannelHandler handler = pipeline.get(handlerName);
            if (handler != null && isHandlerBelongingTo(handler, targetCl, cache)) {
                toRemove.add(handlerName);
            }
        }

        if (toRemove.isEmpty()) {
            return false;
        }

        for (String name : toRemove) {
            try {
                if (pipeline.context(name) != null) {
                    pipeline.remove(name);
                }
            } catch (Exception | LinkageError t) {
                Log.debug("nettyguard.handler-remove-failed", t, "handler", name);
            }
        }

        try {
            Channel channel = pipeline.channel();
            if (channel != null) {
                EventLoop loop = channel.eventLoop();
                if (loop != null) {
                    if (eventLoopsToSync != null) {
                        eventLoopsToSync.add(loop);
                    } else {
                        syncEventLoops(Collections.singleton(loop));
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("nettyguard.pipeline-inspect-failed", t);
        }

        return true;
    }

    static void syncEventLoops(@Nullable Set<EventLoop> eventLoops) {
        if (eventLoops == null || eventLoops.isEmpty()) return;

        long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
        List<Future<?>> futures = new ArrayList<>(eventLoops.size());

        for (EventLoop loop : eventLoops) {
            if (loop == null) continue;
            try {
                if (loop.inEventLoop() || loop.isShuttingDown() || loop.isShutdown()) {
                    continue;
                }
                futures.add(loop.submit(() -> {}));
            } catch (RejectedExecutionException ignored) {
            } catch (Throwable t) {
                Log.debug("nettyguard.pipeline-inspect-failed", t);
            }
        }

        for (Future<?> future : futures) {
            long remainingMs = deadline - System.currentTimeMillis();
            if (remainingMs <= 0) {
                Log.debug("nettyguard.sync-timeout");
                break;
            }
            try {
                if (!future.await(remainingMs, TimeUnit.MILLISECONDS)) {
                    Log.debug("nettyguard.sync-timeout");
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                Log.debug("nettyguard.pipeline-inspect-failed", t);
            }
        }
    }

    public static boolean hasInjectedHandlers(@Nullable Plugin targetPlugin) {
        if (targetPlugin == null || Bukkit.getServer() == null) return false;
        ClassLoader targetCl = targetPlugin.getClass().getClassLoader();
        if (targetCl == null) return false;

        Map<CacheKey, Boolean> cache = new HashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Channel channel = getPlayerChannel(player);
                if (channel == null || !channel.isOpen()) continue;
                if (hasInjectedHandler(channel.pipeline(), targetCl, cache)) {
                    return true;
                }
            } catch (Exception | LinkageError t) {
                Log.debug("nettyguard.pipeline-inspect-failed", t);
            }
        }

        try {
            Object serverConnection = getServerConnection();
            if (serverConnection != null) {
                List<Channel> serverChannels = getServerChannels(serverConnection);
                for (Channel channel : serverChannels) {
                    try {
                        if (hasInjectedHandler(channel.pipeline(), targetCl, cache)) {
                            return true;
                        }
                    } catch (Exception | LinkageError t) {
                        Log.debug("nettyguard.pipeline-inspect-failed", t);
                    }
                }
            }
        } catch (Throwable t) {
            Log.debug("nettyguard.pipeline-inspect-failed", t);
        }

        return false;
    }

    private static boolean hasInjectedHandler(@Nullable ChannelPipeline pipeline, ClassLoader targetCl, Map<CacheKey, Boolean> cache) {
        if (pipeline == null) return false;
        List<String> names;
        synchronized (pipeline) {
            names = new ArrayList<>(pipeline.names());
        }
        for (String handlerName : names) {
            ChannelHandler handler = pipeline.get(handlerName);
            if (handler != null && isHandlerBelongingTo(handler, targetCl, cache)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHandlerBelongingTo(@Nullable ChannelHandler handler, ClassLoader targetCl, Map<CacheKey, Boolean> cache) {
        if (handler == null) return false;
        if (isClassLoadedBy(handler.getClass(), targetCl, cache)) {
            return true;
        }

        if (Proxy.isProxyClass(handler.getClass())) {
            try {
                InvocationHandler ih = Proxy.getInvocationHandler(handler);
                if (ih != null && isClassLoadedBy(ih.getClass(), targetCl, cache)) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }

        if (handler instanceof CombinedChannelDuplexHandler<?, ?> combined) {
            try {
                ChannelHandler inbound = ReflectionHelper.getFieldValue(combined, "inboundHandler");
                if (inbound != null && isHandlerBelongingTo(inbound, targetCl, cache)) {
                    return true;
                }
                ChannelHandler outbound = ReflectionHelper.getFieldValue(combined, "outboundHandler");
                if (outbound != null && isHandlerBelongingTo(outbound, targetCl, cache)) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }

        return false;
    }

    static boolean isClassLoadedBy(Class<?> clazz, ClassLoader targetCl, Map<CacheKey, Boolean> cache) {
        if (clazz == null || targetCl == null) return false;

        CacheKey key = new CacheKey(clazz, targetCl);
        Boolean cached = cache.get(key);
        if (cached != null) return cached;

        boolean result = checkClassLoadedBy(clazz, targetCl, cache);
        cache.put(key, result);
        return result;
    }

    private static boolean checkClassLoadedBy(Class<?> clazz, ClassLoader targetCl, Map<CacheKey, Boolean> cache) {
        ClassLoader classLoader = clazz.getClassLoader();
        if (classLoader == null) {
            return false;
        }

        if (isClassLoaderDescendant(classLoader, targetCl)) {
            return true;
        }

        for (Class<?> iface : clazz.getInterfaces()) {
            if (isClassLoadedBy(iface, targetCl, cache)) {
                return true;
            }
        }

        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            return isClassLoadedBy(superclass, targetCl, cache);
        }

        return false;
    }

    private static boolean isClassLoaderDescendant(@Nullable ClassLoader loader, ClassLoader targetCl) {
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current == targetCl) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Object getServerConnection() {
        try {
            Object server = ReflectionHelper.invokeMethod(Bukkit.getServer(), "getServer");
            if (server == null) return null;
            Object serverConnection = ReflectionHelper.invokeMethod(server, "getConnection");
            if (serverConnection == null) {
                serverConnection = ReflectionHelper.getFieldValue(server, "connection");
            }
            return serverConnection;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Channel> getServerChannels(Object serverConnection) {
        List<Channel> result = new ArrayList<>();
        try {
            List<?> channels = ReflectionHelper.getFieldValue(serverConnection, "channels");
            if (channels != null) {
                List<?> channelsSnapshot;
                synchronized (channels) {
                    channelsSnapshot = new ArrayList<>(channels);
                }
                for (Object item : channelsSnapshot) {
                    if (item instanceof ChannelFuture future) {
                        Channel ch = future.channel();
                        if (ch != null && ch.isOpen()) {
                            result.add(ch);
                        }
                    } else if (item instanceof Channel ch) {
                        if (ch.isOpen()) {
                            result.add(ch);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            List<?> connections = ReflectionHelper.getFieldValue(serverConnection, "connections");
            if (connections != null) {
                List<?> connectionsSnapshot;
                synchronized (connections) {
                    connectionsSnapshot = new ArrayList<>(connections);
                }
                for (Object conn : connections) {
                    try {
                        Channel channel = ReflectionHelper.getFieldValue(conn, "channel");
                        if (channel == null) {
                            channel = ReflectionHelper.getFieldValueOfType(conn, Channel.class);
                        }
                        if (channel != null && channel.isOpen()) {
                            result.add(channel);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return result;
    }

    private static @Nullable Channel getPlayerChannel(Player player) {
        try {
            Object handle = ReflectionHelper.invokeMethod(player, "getHandle");
            if (handle == null) return null;

            Object connection = ReflectionHelper.getFieldValue(handle, "playerConnection");
            if (connection == null) {
                connection = ReflectionHelper.getFieldValue(handle, "connection");
            }
            if (connection == null) return null;

            Object networkManager = ReflectionHelper.getFieldValue(connection, "networkManager");
            if (networkManager == null) {
                networkManager = ReflectionHelper.getFieldValue(connection, "connection");
            }
            if (networkManager == null) return null;

            Channel channel = ReflectionHelper.getFieldValue(networkManager, "channel");
            if (channel == null) {
                channel = ReflectionHelper.getFieldValueOfType(networkManager, Channel.class);
            }
            return channel;
        } catch (Throwable t) {
            return null;
        }
    }
}