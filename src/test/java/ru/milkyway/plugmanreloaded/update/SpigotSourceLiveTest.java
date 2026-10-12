package ru.milkyway.plugmanreloaded.update;

import ru.milkyway.plugmanreloaded.update.UpdateModels.*;

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
    void listVersionsLoadsTestedGameVersions() {
        UpdateCache cache = new UpdateCache(120000);
        SpigotSource source = new SpigotSource(cache);

        PluginIdentity identity = new PluginIdentity("EssentialsPay", "net.craftersland.essentialspay.EssentialsPay", "1.0-SNAPSHOT",
                List.of("CraftersLand"), null, null, null, new java.io.File("EssentialsPay.jar"));
        UpdateSource.ProjectMatch match = source.identifyFromCatalog(identity, "137860", java.util.Map.of());

        List<RemoteVersion> versions = source.listVersions(match);
        Assumptions.assumeFalse(versions.isEmpty(), "Spiget недоступен из этой сети, живая проверка пропущена");

        RemoteVersion latest = versions.get(0);
        assertTrue(latest.compatibilityKnown());
        assertTrue(latest.gameVersions().contains("1.21"));
    }
}
