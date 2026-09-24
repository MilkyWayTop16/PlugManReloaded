package ru.milkyway.plugmanreloaded.update;

import ru.milkyway.plugmanreloaded.update.UpdateModels.*;
import ru.milkyway.plugmanreloaded.update.VersionResolver;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.update.source.SpigotSource;
import ru.milkyway.plugmanreloaded.update.source.UpdateSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("live")
class SpigotSourceLiveTest {

    @Test
    void listVersionsNeverClaimsPerVersionGameCompatibility() {
        UpdateCache cache = new UpdateCache(120000);
        SpigotSource source = new SpigotSource(cache);

        PluginIdentity identity = new PluginIdentity("Chunky", "org.popcraft.chunky.ChunkyBukkit", "1.4.40",
                List.of("pop4959"), null, null, null, new java.io.File("Chunky.jar"));
        UpdateSource.ProjectMatch match = source.identifyFromCatalog(identity, "81534", java.util.Map.of());

        List<RemoteVersion> versions = source.listVersions(match);
        Assumptions.assumeFalse(versions.isEmpty(), "Spiget недоступен из этой сети, живая проверка пропущена");

        List<String> overclaiming = versions.stream()
                .filter(RemoteVersion::compatibilityKnown)
                .map(v -> v.versionNumber() + " заявляет gameVersions=" + v.gameVersions())
                .toList();

        assertTrue(overclaiming.isEmpty(),
                "SpigotSource обязан присылать пустой gameVersions: Spiget отдаёт testedVersions только на "
                        + "уровне ресурса за всю историю плагина, а не для конкретной версии. Если это утверждение "
                        + "снова станет непустым, Resolver примет устаревшие тестированные версии за "
                        + "подтверждение совместимости актуального релиза (инцидент: Chunky 1.5.3 требует MC 26.1+ "
                        + "и Java 25, но унаследованный из старых версий тег «1.21» пропустил его как обновление "
                        + "для сервера 1.21.11):\n  " + String.join("\n  ", overclaiming));
    }
}
