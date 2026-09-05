package ru.milkyway.plugmanreloaded.managers;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.utils.Log;
import ru.milkyway.plugmanreloaded.utils.ReflectionHelper;

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
            Object server = ReflectionHelper.invokeMethod(Bukkit.getServer(), "getServer");
            if (server != null) {
                Object serverConnection = ReflectionHelper.invokeMethod(server, "getConnection");
                if (serverConnection == null) {
                    serverConnection = ReflectionHelper.getFieldValue(server, "connection");
                }
                if (serverConnection != null) {
                    List<?> connections = ReflectionHelper.getFieldValue(serverConnection, "connections");
                    if (connections != null) {
                        for (Object conn : connections) {
                            try {
                                Channel channel = (Channel) ReflectionHelper.getFieldValue(conn, "channel");
                                if (channel != null && channel.isOpen()) {
                                    cleanPipeline(channel.pipeline(), targetCl, cache, eventLoopsToSync);
                                }
                            } catch (Exception | LinkageError t) {
                                failed++;
                            }
                        }
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
            if (handler != null) {
                if (isClassLoadedBy(handler.getClass(), targetCl, cache)) {
                    toRemove.add(handlerName);
                }
            }
        }

        if (toRemove.isEmpty()) {
            return false;
        }

        for (String name : toRemove) {
            try {
                pipeline.remove(name);
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

                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline == null) continue;

                List<String> names;
                synchronized (pipeline) {
                    names = new ArrayList<>(pipeline.names());
                }

                for (String handlerName : names) {
                    ChannelHandler handler = pipeline.get(handlerName);
                    if (handler != null && isClassLoadedBy(handler.getClass(), targetCl, cache)) {
                        return true;
                    }
                }
            } catch (Exception | LinkageError t) {
                Log.debug("nettyguard.pipeline-inspect-failed", t);
            }
        }
        return false;
    }

    static boolean isClassLoadedBy(Class<?> clazz, ClassLoader targetCl, Map<CacheKey, Boolean> cache) {
        if (clazz == null || targetCl == null) return false;

        CacheKey key = new CacheKey(clazz, targetCl);
        Boolean cached = cache.get(key);
        if (cached != null) return cached;

        boolean result;
        if (clazz.getClassLoader() == targetCl) {
            result = true;
        } else {
            result = false;
            for (Class<?> iface : clazz.getInterfaces()) {
                if (isClassLoadedBy(iface, targetCl, cache)) {
                    result = true;
                    break;
                }
            }
            if (!result) {
                Class<?> superclass = clazz.getSuperclass();
                if (superclass != null && superclass != Object.class) {
                    result = isClassLoadedBy(superclass, targetCl, cache);
                }
            }
        }

        cache.put(key, result);
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

            return ReflectionHelper.getFieldValue(networkManager, "channel");
        } catch (Throwable t) {
            return null;
        }
    }
}

