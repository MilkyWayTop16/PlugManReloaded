package ru.milkyway.plugmanreloaded.managers;
import ru.milkyway.plugmanreloaded.utils.NettyGuard;

import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.utils.NettyGuard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class ResourceSanitizerTest {

    @Test
    void testNullSafetyAcrossSanitizers() {
        assertDoesNotThrow(() -> SanitizerManager.cleanupServerState(null));
        assertDoesNotThrow(() -> NettyGuard.cleanPlayerPipelines(null));
        SanitizerManager sanitizer = new SanitizerManager(null);
        assertDoesNotThrow(() -> sanitizer.cleanup(null));
    }
}
