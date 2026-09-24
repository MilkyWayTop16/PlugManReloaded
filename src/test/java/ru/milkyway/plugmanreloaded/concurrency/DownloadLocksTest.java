package ru.milkyway.plugmanreloaded.concurrency;

import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.download.DownloadService.DownloadLocks;

import static org.junit.jupiter.api.Assertions.*;

public class DownloadLocksTest {

    @Test
    void testDownloadLockManagerLifecycle() {
        DownloadLocks lockManager = new DownloadLocks();

        assertTrue(lockManager.tryLock("Essentials"));
        assertTrue(lockManager.isLocked("Essentials"));
        assertTrue(lockManager.isLocked("essentials"));

        assertFalse(lockManager.tryLock("Essentials"));
        assertFalse(lockManager.tryLock("essentials"));

        assertTrue(lockManager.tryLock("Vault"));
        assertTrue(lockManager.isLocked("Vault"));

        lockManager.unlock("Essentials");
        assertFalse(lockManager.isLocked("Essentials"));

        assertTrue(lockManager.tryLock("Essentials"));
        lockManager.unlock("Essentials");
        lockManager.unlock("Vault");
    }
}
