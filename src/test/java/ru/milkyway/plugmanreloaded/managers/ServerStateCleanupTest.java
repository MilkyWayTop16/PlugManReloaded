package ru.milkyway.plugmanreloaded.managers;
import ru.milkyway.plugmanreloaded.utils.NettyGuard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerStateCleanupTest {

    @Test
    void onlyTheOwnNameAndDeclaredProvidesBecomePrefixes() {
        Set<String> prefixes = SanitizerManager.permissionPrefixesFor("SunLight", null);

        assertEquals(Set.of("sunlight."), prefixes,
                "имя плагина «SunLight» не обязано порождать никакого дополнительного префикса «light.» — "
                        + "это был необъяснённый спецкейс, который мог случайно снимать чужие права у другого "
                        + "плагина с префиксом light.*, никак не связанного с SunLight");
    }

    @Test
    void aPluginNamedLightIsNotAccidentallyAffectedBySunPrefixHeuristic() {

        Set<String> lightPrefixes = SanitizerManager.permissionPrefixesFor("Light", null);
        Set<String> sunPluginPrefixes = SanitizerManager.permissionPrefixesFor("SunPlugin", null);

        assertEquals(Set.of("light."), lightPrefixes);
        assertFalse(sunPluginPrefixes.contains("light."),
                "снятие плагина «SunPlugin» не обязано затрагивать права плагина «Light»");
    }

    @Test
    void providesAreAddedAsAdditionalPrefixes() {
        Set<String> prefixes = SanitizerManager.permissionPrefixesFor("MyPlugin", List.of("LegacyName", ""));

        assertTrue(prefixes.contains("myplugin."));
        assertTrue(prefixes.contains("legacyname."));
        assertEquals(2, prefixes.size(), "пустая строка в provides не обязана превращаться в префикс \".\"");
    }

    @Test
    void blankPluginNameYieldsNoPrefixes() {
        assertTrue(SanitizerManager.permissionPrefixesFor(null, null).isEmpty());
        assertTrue(SanitizerManager.permissionPrefixesFor("", null).isEmpty());
    }

    @Test
    void hyphenatedPluginNamesAlsoClaimTheirDottedPermissionNamespace() {
        Set<String> prefixes = SanitizerManager.permissionPrefixesFor("Multiverse-Inventories", null);

        assertTrue(prefixes.contains("multiverse-inventories."),
                "префикс по самому имени плагина обязан остаться");
        assertTrue(prefixes.contains("multiverse.inventories."),
                "Multiverse-Inventories регистрирует права multiverse.inventories.* ПРОГРАММНО, не объявляя "
                        + "их в plugin.yml. Без этого префикса они переживали выгрузку, и повторное включение "
                        + "падало с «The permission multiverse.inventories.info is already defined!», "
                        + "оставляя плагин выключенным");
    }

    @Test
    void underscoreNamesGetTheSameTreatmentAndPlainNamesGetNoExtras() {
        assertTrue(SanitizerManager.permissionPrefixesFor("My_Plugin", null).contains("my.plugin."));

        Set<String> plain = SanitizerManager.permissionPrefixesFor("Chunky", null);
        assertEquals(Set.of("chunky."), plain,
                "у имени без разделителей не должно появляться никаких дополнительных префиксов — "
                        + "лишний префикс рискует снести чужие права");
    }
}
