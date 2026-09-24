package ru.milkyway.plugmanreloaded;

import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.stream.Stream;

public final class BukkitServerMock {

    private static boolean initialized = false;

    private static Server cachedStubServer;

    private BukkitServerMock() {
    }

    public static synchronized void ensureInitialized() {
        try {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            Object currentServer = serverField.get(null);
            if (currentServer != null) {
                return;
            }
            if (cachedStubServer != null) {
                serverField.set(null, cachedStubServer);
                return;
            }

            ByteArrayLoader loader = new ByteArrayLoader(BukkitServerMock.class.getClassLoader());

            byte[] serverBytes = buildStubServerBytes();
            Class<?> stubServerClass = loader.define("ru.milkyway.plugmanreloaded.StubServer", serverBytes);
            Server stubServerInstance = (Server) stubServerClass.getDeclaredConstructor().newInstance();
            cachedStubServer = stubServerInstance;

            serverField.set(null, stubServerInstance);

            Class.forName("org.bukkit.generator.structure.StructureType", true, BukkitServerMock.class.getClassLoader());
            Class.forName("org.bukkit.Registry", true, BukkitServerMock.class.getClassLoader());
            Class.forName("org.bukkit.potion.PotionEffectType", true, BukkitServerMock.class.getClassLoader());

            initialized = true;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static synchronized void resetServer() {
        try {
            ensureInitialized();
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(null, cachedStubServer);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static final java.util.Map<String, org.bukkit.plugin.Plugin> REGISTERED_PLUGINS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void registerPlugin(org.bukkit.plugin.Plugin plugin) {
        if (plugin != null && plugin.getName() != null) {
            REGISTERED_PLUGINS.put(plugin.getName().toLowerCase(java.util.Locale.ROOT), plugin);
        }
    }

    public static void clearRegisteredPlugins() {
        REGISTERED_PLUGINS.clear();
    }

    private static final org.bukkit.plugin.PluginManager PLUGIN_MANAGER = (org.bukkit.plugin.PluginManager) java.lang.reflect.Proxy.newProxyInstance(
            BukkitServerMock.class.getClassLoader(),
            new Class<?>[]{org.bukkit.plugin.PluginManager.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlugins" -> REGISTERED_PLUGINS.values().toArray(new org.bukkit.plugin.Plugin[0]);
                case "getPlugin" -> args[0] != null ? REGISTERED_PLUGINS.get(((String) args[0]).toLowerCase(java.util.Locale.ROOT)) : null;
                case "isPluginEnabled" -> {
                    org.bukkit.plugin.Plugin p = args[0] != null ? REGISTERED_PLUGINS.get(((String) args[0]).toLowerCase(java.util.Locale.ROOT)) : null;
                    yield p != null && p.isEnabled();
                }
                default -> null;
            }
    );

    public static org.bukkit.plugin.PluginManager providePluginManager() {
        return PLUGIN_MANAGER;
    }

    private static final org.bukkit.command.ConsoleCommandSender CONSOLE_SENDER = (org.bukkit.command.ConsoleCommandSender) java.lang.reflect.Proxy.newProxyInstance(
            BukkitServerMock.class.getClassLoader(),
            new Class<?>[]{org.bukkit.command.ConsoleCommandSender.class},
            (proxy, method, args) -> method.getReturnType().isPrimitive() ? (method.getReturnType().equals(boolean.class) ? Boolean.FALSE : 0) : null
    );

    public static org.bukkit.command.ConsoleCommandSender provideConsoleSender() {
        return CONSOLE_SENDER;
    }

    private static final org.bukkit.plugin.ServicesManager SERVICES_MANAGER = (org.bukkit.plugin.ServicesManager) java.lang.reflect.Proxy.newProxyInstance(
            BukkitServerMock.class.getClassLoader(),
            new Class<?>[]{org.bukkit.plugin.ServicesManager.class},
            (proxy, method, args) -> null
    );

    public static org.bukkit.plugin.ServicesManager provideServicesManager() {
        return SERVICES_MANAGER;
    }

    public static Registry<?> provideRegistry(Class<?> type) {
        return new Registry<org.bukkit.Keyed>() {
            @Override
            public org.bukkit.Keyed get(org.bukkit.NamespacedKey key) {
                if (type != null && org.bukkit.potion.PotionEffectType.class.isAssignableFrom(type)) {
                    return new StubPotionEffectType(key);
                }
                return null;
            }

            @Override
            public Stream<org.bukkit.Keyed> stream() {
                return Stream.empty();
            }

            @Override
            public Iterator<org.bukkit.Keyed> iterator() {
                return java.util.Collections.emptyIterator();
            }
        };
    }

    private static final class StubPotionEffectType extends org.bukkit.potion.PotionEffectType {
        private final org.bukkit.NamespacedKey key;

        private StubPotionEffectType(org.bukkit.NamespacedKey key) {
            this.key = key;
        }

        @Override
        public org.bukkit.NamespacedKey getKey() {
            return key != null ? key : org.bukkit.NamespacedKey.minecraft("stub");
        }

        @Override
        public org.bukkit.potion.PotionEffect createEffect(int duration, int amplifier) {
            return null;
        }

        @Override
        public boolean isInstant() {
            return false;
        }

        @Override
        public org.bukkit.Color getColor() {
            return org.bukkit.Color.WHITE;
        }

        @Override
        public double getDurationModifier() {
            return 1.0;
        }

        @Override
        public int getId() {
            return 1;
        }

        @Override
        public String getName() {
            return key != null ? key.getKey() : "stub";
        }

        @Override
        public java.util.Map<org.bukkit.attribute.Attribute, org.bukkit.attribute.AttributeModifier> getEffectAttributes() {
            return java.util.Collections.emptyMap();
        }

        @Override
        public double getAttributeModifierAmount(org.bukkit.attribute.Attribute attribute, int amplifier) {
            return 0.0;
        }

        @Override
        public org.bukkit.potion.PotionEffectType.Category getEffectCategory() {
            return org.bukkit.potion.PotionEffectType.Category.BENEFICIAL;
        }

        @Override
        public String getTranslationKey() {
            return "effect.stub";
        }

        @Override
        public String translationKey() {
            return "effect.stub";
        }
    }

    private static byte[] buildStubServerBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "ru/milkyway/plugmanreloaded/StubServer", null, "java/lang/Object", new String[]{"org/bukkit/Server"});
        cw.visitField(Opcodes.ACC_PUBLIC, "console", "Ljava/lang/Object;", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        for (Method m : Server.class.getMethods()) {
            if (m.getName().equals("getRegistry") && m.getParameterCount() == 1) {
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "ru/milkyway/plugmanreloaded/BukkitServerMock", "provideRegistry", "(Ljava/lang/Class;)Lorg/bukkit/Registry;", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                continue;
            }

            if (m.getName().equals("isPrimaryThread")) {
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                continue;
            }

            if (m.getName().equals("getOnlinePlayers")) {
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                continue;
            }

            if (m.getName().equals("getPluginManager")) {
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "ru/milkyway/plugmanreloaded/BukkitServerMock", "providePluginManager", "()Lorg/bukkit/plugin/PluginManager;", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                continue;
            }

            if (m.getName().equals("getConsoleSender")) {
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "ru/milkyway/plugmanreloaded/BukkitServerMock", "provideConsoleSender", "()Lorg/bukkit/command/ConsoleCommandSender;", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                continue;
            }

            if (m.getName().equals("getServicesManager")) {
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "ru/milkyway/plugmanreloaded/BukkitServerMock", "provideServicesManager", "()Lorg/bukkit/plugin/ServicesManager;", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                continue;
            }

            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
            Class<?> ret = m.getReturnType();
            if (ret == void.class) {
                mv.visitInsn(Opcodes.RETURN);
            } else if (ret == boolean.class || ret == byte.class || ret == char.class || ret == short.class || ret == int.class) {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            } else if (ret == long.class) {
                mv.visitInsn(Opcodes.LCONST_0);
                mv.visitInsn(Opcodes.LRETURN);
            } else if (ret == float.class) {
                mv.visitInsn(Opcodes.FCONST_0);
                mv.visitInsn(Opcodes.FRETURN);
            } else if (ret == double.class) {
                mv.visitInsn(Opcodes.DCONST_0);
                mv.visitInsn(Opcodes.DRETURN);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class ByteArrayLoader extends ClassLoader {
        ByteArrayLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
