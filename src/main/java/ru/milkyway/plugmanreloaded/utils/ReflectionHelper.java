package ru.milkyway.plugmanreloaded.utils;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectionHelper {

    private static final Map<String, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    private ReflectionHelper() {}

    public static @Nullable Class<?> getClass(@Nullable String className) {
        if (className == null || className.isBlank()) return null;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            Log.debug("reflectionhelper.class-not-found", "class", className);
            return null;
        }
    }

    public static @Nullable Field getField(@Nullable Class<?> clazz, String fieldName) {
        if (clazz == null || fieldName == null) return null;
        String key = clazz.getName() + "#" + fieldName;

        return FIELD_CACHE.computeIfAbsent(key, k -> {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Field field = current.getDeclaredField(fieldName);
                    makeAccessible(field, current.getName() + "#" + fieldName);
                    return Optional.of(field);
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                } catch (Throwable t) {
                    Log.debug("reflectionhelper.field-access-error", t, "class", current.getName(), "field", fieldName);
                    break;
                }
            }
            Log.debug("reflectionhelper.field-not-found", "class", clazz.getName(), "field", fieldName);
            return Optional.empty();
        }).orElse(null);
    }

    @SuppressWarnings("unchecked")
    public static <T> @Nullable T getFieldValue(@Nullable Object instance, String fieldName) {
        if (instance == null) return null;
        if (instance instanceof Class<?> clazz) {
            return getStaticFieldValue(clazz, fieldName);
        }
        return getFieldValue(instance.getClass(), instance, fieldName);
    }

    @SuppressWarnings("unchecked")
    public static <T> @Nullable T getFieldValue(Class<?> clazz, Object instance, String fieldName) {
        Field field = getField(clazz, fieldName);
        if (field == null) return null;
        try {
            return (T) field.get(instance);
        } catch (Throwable t) {
            Log.debug("reflectionhelper.field-get-failed", t, "class", clazz.getName(), "field", fieldName);
            return null;
        }
    }

    public static <T> @Nullable T getFieldValueOfType(@Nullable Object instance, Class<T> type) {
        if (instance == null || type == null) return null;
        for (Class<?> clazz = instance.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(instance);
                    if (type.isInstance(value)) {
                        return type.cast(value);
                    }
                } catch (Throwable t) {
                    Log.debug("reflectionhelper.field-read-failed", t, "class", clazz.getName(), "field", field.getName());
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> @Nullable T getStaticFieldValue(Class<?> clazz, String fieldName) {
        Field field = getField(clazz, fieldName);
        if (field == null) return null;
        try {
            return (T) field.get(null);
        } catch (Throwable t) {
            Log.debug("reflectionhelper.static-field-get-failed", t, "class", clazz.getName(), "field", fieldName);
            return null;
        }
    }

    public static boolean setFieldValue(@Nullable Object instance, String fieldName, Object value) {
        if (instance == null) return false;
        if (instance instanceof Class<?> clazz) {
            return setStaticFieldValue(clazz, fieldName, value);
        }
        return setFieldValue(instance.getClass(), instance, fieldName, value);
    }

    public static boolean setFieldValue(Class<?> clazz, Object instance, String fieldName, Object value) {
        Field field = getField(clazz, fieldName);
        if (field == null) return false;
        try {
            field.set(instance, value);
            return true;
        } catch (Throwable t) {
            Log.debug("reflectionhelper.field-set-failed", "class", clazz.getName(), "field", fieldName);
            return false;
        }
    }

    public static boolean setStaticFieldValue(Class<?> clazz, String fieldName, Object value) {
        Field field = getField(clazz, fieldName);
        if (field == null) return false;
        try {
            field.set(null, value);
            return true;
        } catch (Throwable t) {
            Log.debug("reflectionhelper.static-field-set-failed", "class", clazz.getName(), "field", fieldName);
            return false;
        }
    }

    public static @Nullable Method getMethod(@Nullable Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        if (clazz == null || methodName == null) return null;
        String key = methodKey(clazz, methodName, parameterTypes);

        return METHOD_CACHE.computeIfAbsent(key, k -> {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Method method = current.getDeclaredMethod(methodName, parameterTypes);
                    makeAccessible(method, current.getName() + "#" + methodName);
                    return Optional.of(method);
                } catch (NoSuchMethodException e) {
                    current = current.getSuperclass();
                } catch (Throwable t) {
                    Log.debug("reflectionhelper.method-access-error", t, "class", current.getName(), "method", methodName);
                    break;
                }
            }
            Log.debug("reflectionhelper.method-not-found", "class", clazz.getName(), "method", methodName);
            return Optional.empty();
        }).orElse(null);
    }

    @SuppressWarnings("unchecked")
    public static <T> @Nullable T invokeMethod(@Nullable Object instance, String methodName, Object... args) {
        if (instance == null) return null;
        if (instance instanceof Class<?> clazz) {
            return invokeStaticMethod(clazz, methodName, args);
        }
        return invokeMethodOnClass(instance.getClass(), instance, methodName, args);
    }

    @SuppressWarnings("unchecked")
    public static <T> @Nullable T invokeMethodOrThrow(@Nullable Object instance, String methodName, Object... args) {
        if (instance == null) return null;
        Method method = resolveMethod(instance.getClass(), methodName, args);
        if (method == null) {
            throw new IllegalStateException("ReflectionHelper: method " + instance.getClass().getName() + "#" + methodName + " not found");
        }
        try {
            return (T) method.invoke(instance, args);
        } catch (Throwable t) {
            throw new IllegalStateException("ReflectionHelper: invocation failed for " + instance.getClass().getName() + "#" + methodName, t);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> @Nullable T invokeStaticMethod(@Nullable Class<?> clazz, String methodName, Object... args) {
        if (clazz == null || methodName == null) return null;
        return invokeMethodOnClass(clazz, null, methodName, args);
    }

    @SuppressWarnings("unchecked")
    private static <T> @Nullable T invokeMethodOnClass(Class<?> clazz, Object instance, String methodName, Object... args) {
        Method method = resolveMethod(clazz, methodName, args);
        if (method == null) {
            Log.debug("reflectionhelper.method-not-found", "class", clazz.getName(), "method", methodName);
            return null;
        }

        try {
            return (T) method.invoke(instance, args);
        } catch (Throwable t) {
            Log.debug("reflectionhelper.method-invoke-error", t, "class", clazz.getName(), "method", methodName);
            return null;
        }
    }

    private static String methodKey(Class<?> clazz, String methodName, Class<?>[] parameterTypes) {
        StringBuilder keyBuilder = new StringBuilder(clazz.getName()).append("#").append(methodName);
        if (parameterTypes != null) {
            for (Class<?> p : parameterTypes) {
                keyBuilder.append(":").append(p != null ? p.getName() : "null");
            }
        }
        return keyBuilder.toString();
    }

    private static @Nullable Method resolveMethod(Class<?> clazz, String methodName, Object... args) {
        Class<?>[] argTypes = new Class<?>[args != null ? args.length : 0];
        boolean hasNull = false;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null) {
                    argTypes[i] = args[i].getClass();
                } else {
                    argTypes[i] = null;
                    hasNull = true;
                }
            }
        }

        if (!hasNull) {
            Method method = getMethod(clazz, methodName, argTypes);
            if (method != null) return method;
        }

        String key = methodKey(clazz, methodName, argTypes);
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == argTypes.length) {
                    Class<?>[] paramTypes = m.getParameterTypes();
                    boolean compatible = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (!isTypeCompatible(paramTypes[i], argTypes[i])) {
                            compatible = false;
                            break;
                        }
                    }
                    if (compatible) {
                        makeAccessible(m, m.getDeclaringClass().getName() + "#" + m.getName());
                        METHOD_CACHE.put(key, Optional.of(m));
                        return m;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean isTypeCompatible(@Nullable Class<?> targetType, @Nullable Class<?> actualType) {
        if (targetType == null) return false;
        if (actualType == null) return !targetType.isPrimitive();
        if (targetType.isAssignableFrom(actualType)) return true;

        if (targetType.isPrimitive()) {
            if (targetType == boolean.class) return actualType == Boolean.class;
            if (targetType == byte.class) return actualType == Byte.class;
            if (targetType == char.class) return actualType == Character.class;
            if (targetType == short.class) return actualType == Short.class || actualType == Byte.class;
            if (targetType == int.class) return actualType == Integer.class || actualType == Short.class || actualType == Byte.class || actualType == Character.class;
            if (targetType == long.class) return actualType == Long.class || actualType == Integer.class || actualType == Short.class || actualType == Byte.class || actualType == Character.class;
            if (targetType == float.class) return actualType == Float.class || actualType == Long.class || actualType == Integer.class || actualType == Short.class || actualType == Byte.class;
            if (targetType == double.class) return actualType == Double.class || actualType == Float.class || actualType == Long.class || actualType == Integer.class || actualType == Short.class || actualType == Byte.class;
        }
        return false;
    }

    public static void purgeClassLoader(@Nullable ClassLoader cl) {
        if (cl == null) return;
        FIELD_CACHE.entrySet().removeIf(e -> e.getValue().isPresent() && e.getValue().get().getDeclaringClass().getClassLoader() == cl);
        METHOD_CACHE.entrySet().removeIf(e -> e.getValue().isPresent() && e.getValue().get().getDeclaringClass().getClassLoader() == cl);
    }

    public static void clearCache() {
        FIELD_CACHE.clear();
        METHOD_CACHE.clear();
    }
    private static void makeAccessible(AccessibleObject member, String description) {
        try {
            member.setAccessible(true);
        } catch (Throwable t) {
            Log.debug("reflectionhelper.access-not-opened", t, "description", description);
        }
    }

}

