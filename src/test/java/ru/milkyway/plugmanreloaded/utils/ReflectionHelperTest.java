package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionHelperTest {

    public static class SampleTarget {
        private String name;
        private long count;

        public void setName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setCount(long count) {
            this.count = count;
        }

        public long getCount() {
            return count;
        }
    }

    @Test
    @DisplayName("Test method invocation with null arguments")
    void testInvokeWithNullArgument() {
        SampleTarget target = new SampleTarget();
        target.setName("Initial");

        ReflectionHelper.invokeMethod(target, "setName", (String) null);
        assertNull(target.getName());

        ReflectionHelper.invokeMethod(target, "setName", "Updated");
        assertEquals("Updated", target.getName());
    }

    @Test
    @DisplayName("Test method invocation with primitive widening")
    void testInvokeWithPrimitiveWidening() {
        SampleTarget target = new SampleTarget();

        ReflectionHelper.invokeMethod(target, "setCount", 42);
        assertEquals(42L, target.getCount());
    }

    @Test
    @DisplayName("Test purgeClassLoader removes cached fields and methods")
    void testPurgeClassLoader() {
        URLClassLoader customLoader = new URLClassLoader(new URL[0], getClass().getClassLoader());

        ReflectionHelper.invokeMethod(new SampleTarget(), "setName", "test");
        ReflectionHelper.getField(SampleTarget.class, "name");

        ReflectionHelper.purgeClassLoader(customLoader);
        assertNotNull(ReflectionHelper.getField(SampleTarget.class, "name"));

        ReflectionHelper.purgeClassLoader(SampleTarget.class.getClassLoader());
        ReflectionHelper.clearCache();
    }
}
