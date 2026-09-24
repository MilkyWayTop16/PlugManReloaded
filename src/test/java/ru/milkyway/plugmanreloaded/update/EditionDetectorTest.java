package ru.milkyway.plugmanreloaded.update;

import ru.milkyway.plugmanreloaded.update.UpdateModels.PluginEdition;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditionDetectorTest {

    @Test
    void testPremiumDetectionFromJarName() {
        PluginEdition edition = PluginMatcher.detect(
                new File("ExecutableItems_Prem-5.9.69.jar"),
                "ExecutableItems",
                "5.9.69",
                "com.ssomar.score.executableitems.ExecutableItems",
                null
        );
        assertEquals(PluginEdition.PREMIUM, edition);
        assertTrue(edition.isPremium());
    }

    @Test
    void testPremiumDetectionWithDifferentSeparators() {
        PluginEdition edition1 = PluginMatcher.detect(
                new File("DeluxeCombat-PRO-1.2.jar"),
                "DeluxeCombat",
                "1.2",
                "nl.marido.deluxecombat.DeluxeCombat",
                null
        );
        assertEquals(PluginEdition.PREMIUM, edition1);

        PluginEdition edition2 = PluginMatcher.detect(
                new File("CustomCrafting_Premium.jar"),
                "CustomCrafting",
                "1.0",
                "redempt.customcrafting.CustomCrafting",
                null
        );
        assertEquals(PluginEdition.PREMIUM, edition2);
    }

    @Test
    void testPremiumDetectionFromVersionDescriptor() {
        PluginEdition edition = PluginMatcher.detect(
                new File("SCore.jar"),
                "SCore",
                "3.9.69-PREMIUM",
                "com.ssomar.score.SCore",
                null
        );
        assertEquals(PluginEdition.PREMIUM, edition);
    }

    @Test
    void testNoFalsePositivesOnLegitimateWords() {
        PluginEdition edition1 = PluginMatcher.detect(
                new File("PremadeKits-1.0.jar"),
                "PremadeKits",
                "1.0",
                "com.example.premadekits.Main",
                null
        );
        assertEquals(PluginEdition.FREE, edition1);
        assertFalse(edition1.isPremium());

        PluginEdition edition2 = PluginMatcher.detect(
                new File("PromptOptimizer-2.1.jar"),
                "PromptOptimizer",
                "2.1",
                "com.example.promptoptimizer.Main",
                null
        );
        assertEquals(PluginEdition.FREE, edition2);

        PluginEdition edition3 = PluginMatcher.detect(
                new File("ChatFilterPlus-1.0.jar"),
                "ChatFilterPlus",
                "1.0",
                "ru.milkyway.chatfilterplus.ChatFilterPlus",
                null
        );
        assertEquals(PluginEdition.FREE, edition3);

        PluginEdition edition4 = PluginMatcher.detect(
                new File("ProtocolLib.jar"),
                "ProtocolLib",
                "5.3.0",
                "com.comphenix.protocol.ProtocolLib",
                null
        );
        assertEquals(PluginEdition.FREE, edition4);
    }
}
