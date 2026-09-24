package ru.milkyway.plugmanreloaded.managers;
import ru.milkyway.plugmanreloaded.utils.NettyGuard;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ClassLoaderSanitizerTest {

    @BeforeAll
    static void installBukkitStub() {
        ru.milkyway.plugmanreloaded.BukkitServerMock.ensureInitialized();
    }

    public static class SamplePluginWithStaticState {
        public static Object activeCache = "CACHED_DATA";
        public static final String CONSTANT = "IMMUTABLE";
        public static int primitiveCount = 42;
    }

    @Test
    @DisplayName("Verify cleanStaticFields nulls non-final static fields while preserving constants and primitives")
    void testStaticFieldsClearing() throws Exception {
        SamplePluginWithStaticState.activeCache = "DYNAMIC_OBJECT";
        assertEquals("DYNAMIC_OBJECT", SamplePluginWithStaticState.activeCache);

        SanitizerManager sanitizer = new SanitizerManager(null);
        Method clearClassStaticFields = SanitizerManager.class.getDeclaredMethod("clearClassStaticFields", Class.class);
        clearClassStaticFields.setAccessible(true);

        clearClassStaticFields.invoke(sanitizer, SamplePluginWithStaticState.class);

        assertNull(SamplePluginWithStaticState.activeCache, "Нефинальное статическое поле обязано быть обнулено");
        assertEquals("IMMUTABLE", SamplePluginWithStaticState.CONSTANT, "Финальное поле не должно изменяться");
        assertEquals(42, SamplePluginWithStaticState.primitiveCount, "Примитивные поля не должны изменяться");
    }

    @Test
    @DisplayName("Verify cleanThreads clears contextClassLoader and interrupts matching thread")
    void testThreadContextClassLoaderSanitization() throws Exception {
        URLClassLoader dummyLoader = new URLClassLoader(new URL[0], getClass().getClassLoader());
        java.util.concurrent.atomic.AtomicBoolean wasInterrupted = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.CountDownLatch startedLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch endLatch = new java.util.concurrent.CountDownLatch(1);

        Thread worker = new Thread(() -> {
            startedLatch.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
            } finally {
                endLatch.countDown();
            }
        }, "test-worker-sanitizer-thread");
        worker.setContextClassLoader(dummyLoader);
        worker.start();

        assertTrue(startedLatch.await(2, java.util.concurrent.TimeUnit.SECONDS));

        SanitizerManager sanitizer = new SanitizerManager(null);
        Method cleanThreads = SanitizerManager.class.getDeclaredMethod("cleanThreads", Plugin.class, ClassLoader.class);
        cleanThreads.setAccessible(true);

        cleanThreads.invoke(sanitizer, null, dummyLoader);

        assertTrue(endLatch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(wasInterrupted.get(), "Поток с контекстным загрузчиком плагина обязан быть прерван");
        assertNull(worker.getContextClassLoader(), "ContextClassLoader обязан быть обнулен во избежание удержания Metaspace");
    }

    public static class DummyPluginClassLoader extends ClassLoader {
        public Plugin plugin;
        public Plugin pluginInit;
    }

    @Test
    @DisplayName("Verify closeClassLoader nulls both plugin and pluginInit fields")
    void testPluginInitAndPluginFieldNulling() throws Exception {
        SanitizerManager cleanup = new SanitizerManager(null);
        DummyPluginClassLoader loader = new DummyPluginClassLoader();
        Plugin dummyPlugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                ClassLoaderSanitizerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> "TestPlugin"
        );
        loader.plugin = dummyPlugin;
        loader.pluginInit = dummyPlugin;

        Method clearBackReferences = SanitizerManager.class.getDeclaredMethod("clearBackReferences", Plugin.class, ClassLoader.class);
        clearBackReferences.setAccessible(true);
        clearBackReferences.invoke(cleanup, dummyPlugin, loader);

        assertNull(loader.plugin, "plugin field must be nulled");
        assertNull(loader.pluginInit, "pluginInit field must be nulled");
    }

    public static class DummyPaperGroup {
        public List<ClassLoader> loaders = new ArrayList<>();
        public Map<String, ClassLoader> map = new HashMap<>();
    }

    public static class DummyPaperClassLoader extends ClassLoader {
        public DummyPaperGroup group = new DummyPaperGroup();
    }

    @Test
    @DisplayName("Verify Paper group loaders and map decoupling in closeClassLoader")
    void testPaperGroupSanitization() throws Exception {
        SanitizerManager cleanup = new SanitizerManager(null);
        DummyPaperClassLoader loader = new DummyPaperClassLoader();
        loader.group.loaders.add(loader);
        loader.group.map.put("test", loader);

        Method detach = SanitizerManager.class.getDeclaredMethod("detachFromLoaderGroup", ClassLoader.class);
        detach.setAccessible(true);
        detach.invoke(cleanup, loader);

        assertFalse(loader.group.loaders.contains(loader), "Loader must be removed from Paper group loaders");
        assertFalse(loader.group.map.containsValue(loader), "Loader must be removed from Paper group map");
    }

    public static class RegisteredListenerStub {
        public final Plugin plugin;

        public RegisteredListenerStub(Plugin plugin) {
            this.plugin = plugin;
        }
    }

    @Test
    @DisplayName("Verify cleanInternalListeners handles both Collection and Map values")
    void testInternalListenersNestedMapSupport() throws Exception {
        Plugin targetPlugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                ClassLoaderSanitizerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TargetPlugin";
                    case "equals" -> proxy == args[0];
                    default -> null;
                }
        );

        Plugin otherPlugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                ClassLoaderSanitizerTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "OtherPlugin";
                    case "equals" -> proxy == args[0];
                    default -> null;
                }
        );

        Map<Object, Object> listeners = new HashMap<>();
        Map<Object, List<Object>> nestedMap = new HashMap<>();
        List<Object> nestedList = new ArrayList<>();
        nestedList.add(new RegisteredListenerStub(targetPlugin));
        nestedList.add(new RegisteredListenerStub(otherPlugin));
        nestedMap.put("subEvent", nestedList);
        listeners.put("rootEventWithMap", nestedMap);

        List<Object> flatList = new ArrayList<>();
        flatList.add(new RegisteredListenerStub(targetPlugin));
        flatList.add(new RegisteredListenerStub(otherPlugin));
        listeners.put("rootEventWithCollection", flatList);

        Method purgeMethod = SanitizerManager.class.getDeclaredMethod("purgeListenersMap", Map.class, Plugin.class);
        purgeMethod.setAccessible(true);
        purgeMethod.invoke(null, listeners, targetPlugin);

        assertEquals(1, nestedList.size(), "Target plugin listener must be removed from nested map");
        assertSame(otherPlugin, ((RegisteredListenerStub) nestedList.get(0)).plugin);

        assertEquals(1, flatList.size(), "Target plugin listener must be removed from collection");
        assertSame(otherPlugin, ((RegisteredListenerStub) flatList.get(0)).plugin);
    }

    public static class SamplePluginWithDiverseFields extends JavaPlugin {
        public URLClassLoader internalLoader;
        public ByteArrayInputStream inputStream;
        public ByteArrayOutputStream outputStream;
        public Closeable customResource;
        public ClassLoader selfLoader;
        public ClassLoader systemLoader;
    }

    @Test
    @DisplayName("Verify closeInternalLoaders closes only internal ClassLoaders and preserves other Closeable resources")
    void testCloseInternalLoadersSelectivity() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        SamplePluginWithDiverseFields plugin = (SamplePluginWithDiverseFields) unsafe.allocateInstance(SamplePluginWithDiverseFields.class);

        AtomicBoolean internalLoaderClosed = new AtomicBoolean(false);
        plugin.internalLoader = new URLClassLoader(new URL[0], getClass().getClassLoader()) {
            @Override
            public void close() throws IOException {
                internalLoaderClosed.set(true);
                super.close();
            }
        };

        AtomicBoolean inputStreamClosed = new AtomicBoolean(false);
        plugin.inputStream = new ByteArrayInputStream(new byte[10]) {
            @Override
            public void close() throws IOException {
                inputStreamClosed.set(true);
                super.close();
            }
        };

        AtomicBoolean outputStreamClosed = new AtomicBoolean(false);
        plugin.outputStream = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                outputStreamClosed.set(true);
                super.close();
            }
        };

        AtomicBoolean customResourceClosed = new AtomicBoolean(false);
        plugin.customResource = () -> customResourceClosed.set(true);

        plugin.selfLoader = plugin.getClass().getClassLoader();
        plugin.systemLoader = ClassLoader.getSystemClassLoader();

        SanitizerManager cleanup = new SanitizerManager(null);
        cleanup.closeInternalLoaders(plugin);

        assertTrue(internalLoaderClosed.get(), "Internal URLClassLoader must be closed");
        assertFalse(inputStreamClosed.get(), "InputStream must NOT be closed");
        assertFalse(outputStreamClosed.get(), "OutputStream must NOT be closed");
        assertFalse(customResourceClosed.get(), "Arbitrary Closeable resource must NOT be closed");
    }

    @Test
    @DisplayName("Verify closeInternalLoaders does not close plugin self loader or system loader")
    void testCloseInternalLoadersDoesNotCloseSelfOrSystemLoader() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        SamplePluginWithDiverseFields plugin = (SamplePluginWithDiverseFields) unsafe.allocateInstance(SamplePluginWithDiverseFields.class);

        AtomicBoolean selfLoaderClosed = new AtomicBoolean(false);
        URLClassLoader selfCl = new URLClassLoader(new URL[0], getClass().getClassLoader()) {
            @Override
            public void close() throws IOException {
                selfLoaderClosed.set(true);
                super.close();
            }
        };

        Field classLoaderField = JavaPlugin.class.getDeclaredField("classLoader");
        classLoaderField.setAccessible(true);
        classLoaderField.set(plugin, selfCl);

        plugin.selfLoader = selfCl;
        plugin.systemLoader = ClassLoader.getSystemClassLoader();

        SanitizerManager cleanup = new SanitizerManager(null);
        cleanup.closeInternalLoaders(plugin);

        assertFalse(selfLoaderClosed.get(), "Self or system loader must not be closed in closeInternalLoaders");
        selfCl.close();
    }

    @Test
    @DisplayName("Verify closeInternalLoaders handles null plugin gracefully")
    void testCloseInternalLoadersNullSafe() {
        SanitizerManager cleanup = new SanitizerManager(null);
        assertDoesNotThrow(() -> cleanup.closeInternalLoaders(null));
    }
}
