package ru.milkyway.plugmanreloaded.managers;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaperClassLoaderGroupSanitizerTest {

    static class DummyGroup {
        private final List<ClassLoader> classloaders = new ArrayList<>();

        public List<ClassLoader> getClassLoaders() {
            return classloaders;
        }
    }

    static class DummyLockingGroup {
        private final DummyGroup parent;

        DummyLockingGroup(DummyGroup parent) {
            this.parent = parent;
        }

        public DummyGroup getParent() {
            return parent;
        }
    }

    static class DummyPluginClassLoader extends URLClassLoader {
        private Object classLoaderGroup;

        DummyPluginClassLoader(URL[] urls, Object group) {
            super(urls, null);
            this.classLoaderGroup = group;
        }

        public Object getGroup() {
            return classLoaderGroup;
        }
    }

    @Test
    void testDetachFromLoaderGroupRemovesFromClassloadersList() throws Exception {
        DummyGroup innerGroup = new DummyGroup();
        DummyLockingGroup lockingGroup = new DummyLockingGroup(innerGroup);

        DummyPluginClassLoader loader = new DummyPluginClassLoader(new URL[0], lockingGroup);
        innerGroup.classloaders.add(loader);
        assertEquals(1, innerGroup.classloaders.size());

        SanitizerManager sanitizer = new SanitizerManager(null);
        Method detachMethod = SanitizerManager.class.getDeclaredMethod("detachFromLoaderGroup", ClassLoader.class);
        detachMethod.setAccessible(true);
        detachMethod.invoke(sanitizer, loader);

        assertTrue(innerGroup.classloaders.isEmpty());
        loader.close();
    }
}
