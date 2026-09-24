package ru.milkyway.plugmanreloaded.download;

import ru.milkyway.plugmanreloaded.download.DownloadModels.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DownloadServiceLockKeyTest {

    @Test
    @DisplayName("Verify executeTransactionInternal never invokes tree.targetPluginName in bytecode")
    void executeTransactionInternalNeverCallsTargetPluginName() throws Exception {
        ClassReader reader = new ClassReader(DownloadService.class.getName());
        List<String> invokedMethods = new ArrayList<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!"executeTransactionInternal".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDescriptor, boolean isInterface) {
                        invokedMethods.add(owner + "." + mName);
                    }
                };
            }
        }, 0);

        assertFalse(invokedMethods.stream().anyMatch(m -> m.contains("targetPluginName")),
                "executeTransactionInternal must not recompute lock key from tree.targetPluginName()");
    }

    @Test
    @DisplayName("Verify unlockKey is always the lockKey passed to executeTransactionInternal")
    void unlockKeyIsAlwaysTheSameStringThatAcquiredTheLock() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);

        DownloadService service = (DownloadService) unsafe.allocateInstance(DownloadService.class);
        DownloadService.DownloadLocks lockManager = new DownloadService.DownloadLocks();

        Field lmField = DownloadService.class.getDeclaredField("lockManager");
        lmField.setAccessible(true);
        lmField.set(service, lockManager);

        PluginDownloader stubCoordinator = new PluginDownloader(null, null, "TestAgent") {
            @Override
            public void executeInstallTransaction(
                    SearchResultEntry targetEntry,
                    List<SearchResultEntry> dependencyEntries,
                    Consumer<DownloadResult> callback
            ) {
                callback.accept(DownloadResult.success("target", "1.0", "modrinth", List.of()));
            }
        };

        Field coordField = DownloadService.class.getDeclaredField("coordinator");
        coordField.setAccessible(true);
        coordField.set(service, stubCoordinator);

        SearchResultEntry entry = new SearchResultEntry(
                "modrinth", "target-id", "TargetTitle", "author", "1.0", "desc",
                "https://modrinth.com", "https://download", 0L, 0, 0.0,
                List.of(), List.of(), List.of(), null, null, "target.jar", false, true
        );
        DependencyTree tree = new DependencyTree(
                "DifferentPluginNameFromTree", entry, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), false, null
        );

        String acquiredLockKey = "CustomAcquiredLockKey";
        assertTrue(lockManager.tryLock(acquiredLockKey));
        assertTrue(lockManager.isLocked(acquiredLockKey));
        assertFalse(lockManager.isLocked(tree.targetPluginName()));

        Method execMethod = DownloadService.class.getDeclaredMethod(
                "executeTransactionInternal",
                String.class,
                DependencyTree.class,
                Consumer.class
        );
        execMethod.setAccessible(true);

        AtomicBoolean completed = new AtomicBoolean(false);
        execMethod.invoke(service, acquiredLockKey, tree, (Consumer<DownloadResult>) res -> completed.set(true));

        assertTrue(completed.get());
        assertFalse(lockManager.isLocked(acquiredLockKey), "The acquired lock key must be released");
        assertFalse(lockManager.isLocked(tree.targetPluginName()), "Tree target name was never locked");
    }
}
