package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaspaceCleanupTest {

    @BeforeEach
    void reset() {
        MetaspaceCleanup.resetForTests();
    }

    @Test
    void rawSystemGcLivesOnlyInsideTheReclaimer() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.getFileName().toString().equals("MetaspaceCleanup.java")) continue;

                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains("System.gc()")) {
                        offenders.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "прямой System.gc() в обход MetaspaceCleanup — каждый такой вызов это отдельная "
                        + "STW-пауза, а в цикле выгрузки их станет столько же, сколько плагинов:\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    void deferredRequestWithoutPluginCollectsImmediately() {
        MetaspaceCleanup.requestDeferred(null);
        assertFalse(MetaspaceCleanup.hasPendingRequest(),
                "без живого плагина отложить нечем — сбор обязан произойти сразу, а не потеряться");
    }

    @Test
    void immediateReclaimCancelsAPendingRequest() {
        MetaspaceCleanup.resetForTests();
        MetaspaceCleanup.runNow();
        assertFalse(MetaspaceCleanup.hasPendingRequest(),
                "немедленный сбор обязан снимать отложенный запрос, иначе следующим тиком случится второй Full GC");
    }
}
