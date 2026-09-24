package ru.milkyway.plugmanreloaded.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyNodeSelfEdgeTest {

    @Test
    void aNodeNeverBecomesItsOwnDependent() {
        DependencyNode node = new DependencyNode("PlugManX");
        node.addDependent("PlugManX");

        assertTrue(node.getDependents().isEmpty(),
                "узел не имеет права числить самого себя в зависимых: иначе выгрузка плагина с "
                        + "provides+softdepend на одно имя показывает несуществующую зависимость");
    }

    @Test
    void selfEdgeIsRejectedRegardlessOfLetterCase() {
        DependencyNode node = new DependencyNode("PlugManX");
        node.addDependent("plugmanx");
        node.addDependent("PLUGMANX");

        assertTrue(node.getDependents().isEmpty(),
                "имена плагинов сравниваются без учёта регистра по всему проекту, самопетля тоже");
    }

    @Test
    void genuineDependentsAreStillRecorded() {
        DependencyNode node = new DependencyNode("PlaceholderAPI");
        node.addDependent("ajLeaderboards");
        node.addDependent("MaintenanceAddon");
        node.addDependent("ajLeaderboards");

        assertEquals(2, node.getDependents().size(), "настоящие зависимые обязаны сохраняться без дублей");
        assertTrue(node.getDependents().contains("ajLeaderboards"));
        assertTrue(node.getDependents().contains("MaintenanceAddon"));
        assertFalse(node.getDependents().contains("PlaceholderAPI"));
    }

    @Test
    void blankAndNullDependentsAreIgnored() {
        DependencyNode node = new DependencyNode("Vault");
        node.addDependent(null);
        node.addDependent("");
        node.addDependent("   ");

        assertTrue(node.getDependents().isEmpty());
    }
}
