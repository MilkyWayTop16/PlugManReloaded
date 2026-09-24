package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyGuardCacheTest {

    static class Probe {}

    @Test
    void sharingOneCacheAcrossDifferentClassLoadersStillGivesCorrectAnswers() {
        ClassLoader ownLoader = Probe.class.getClassLoader();
        ClassLoader unrelatedLoader = new URLClassLoader(new URL[0], null);
        Map<NettyGuard.CacheKey, Boolean> sharedCache = new HashMap<>();

        assertTrue(NettyGuard.isClassLoadedBy(Probe.class, ownLoader, sharedCache),
                "sanity: Probe действительно загружен ownLoader");

        assertFalse(NettyGuard.isClassLoadedBy(Probe.class, unrelatedLoader, sharedCache),
                "общая карта на два разных загрузчика обязана пересчитать ответ для второго, а не "
                        + "вернуть закэшированный ответ первого — иначе кэш небезопасен для переиспользования");
    }

    @Test
    void freshCachePerClassLoaderGivesCorrectAnswers() {
        ClassLoader ownLoader = Probe.class.getClassLoader();
        ClassLoader unrelatedLoader = new URLClassLoader(new URL[0], null);

        assertTrue(NettyGuard.isClassLoadedBy(Probe.class, ownLoader, new HashMap<>()));
        assertFalse(NettyGuard.isClassLoadedBy(Probe.class, unrelatedLoader, new HashMap<>()));
    }
}
