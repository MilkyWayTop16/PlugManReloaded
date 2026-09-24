package ru.milkyway.plugmanreloaded.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCompareTest {

    @Test
    @DisplayName("Numeric segments compare numerically, not lexically (1.9 < 1.10)")
    void numericSegmentsAvoidTheLexicalTrap() {
        assertTrue(VersionCompare.isNewer("1.10", "1.9"),
                "1.10 обязан быть новее 1.9 — сравнение должно быть числовым по сегментам, а не строковым "
                        + "(лексически \"1.10\" < \"1.9\", это классическая ловушка)");
        assertFalse(VersionCompare.isNewer("1.9", "1.10"));
        assertTrue(VersionCompare.isNewer("2.0", "1.99"));
    }

    @Test
    @DisplayName("A release version is newer than a pre-release of the same base version")
    void releaseBeatsPrereleaseOfSameBase() {
        assertTrue(VersionCompare.isNewer("1.0", "1.0-beta"),
                "релиз 1.0 обязан считаться новее чем 1.0-beta того же базового номера");
        assertTrue(VersionCompare.isNewer("1.0", "1.0-alpha"));
        assertTrue(VersionCompare.isNewer("1.0", "1.0-rc"));
        assertFalse(VersionCompare.isNewer("1.0-beta", "1.0"));
    }

    @Test
    @DisplayName("Pre-release qualifiers order alpha < beta < rc < pre < release")
    void prereleaseQualifiersOrderCorrectly() {
        assertTrue(VersionCompare.isNewer("1.0-beta", "1.0-alpha"));
        assertTrue(VersionCompare.isNewer("1.0-rc", "1.0-beta"));
        assertTrue(VersionCompare.isNewer("1.0-pre", "1.0-rc"));
        assertFalse(VersionCompare.isNewer("1.0-alpha", "1.0-beta"));
    }

    @Test
    @DisplayName("Platform tokens are stripped so 1.0-paper equals 1.0")
    void platformTokensAreIgnored() {
        assertFalse(VersionCompare.isNewer("1.0-paper", "1.0"));
        assertFalse(VersionCompare.isNewer("1.0", "1.0-spigot"));
        assertTrue(VersionCompare.isNewer("2.0-purpur", "1.0-folia"));
    }

    @Test
    @DisplayName("A leading v prefix is ignored")
    void leadingVPrefixIsIgnored() {
        assertFalse(VersionCompare.isNewer("v1.0", "1.0"));
        assertTrue(VersionCompare.isNewer("v2.0", "v1.0"));
    }

    @Test
    @DisplayName("Identical versions are never newer than each other")
    void identicalVersionsAreEqual() {
        assertFalse(VersionCompare.isNewer("1.2.3", "1.2.3"));
        assertFalse(VersionCompare.isNewer("1.2.3", "v1.2.3"));
        assertFalse(VersionCompare.isNewer("2.20.1-SNAPSHOT", "2.20.1-snapshot"));
    }

    @Test
    @DisplayName("Numeric build numbers compare strictly numerically")
    void buildNumbersCompareNumerically() {
        assertTrue(VersionCompare.isNewer("2005", "42"));
        assertFalse(VersionCompare.isNewer("42", "2005"));
    }

    @Test
    @DisplayName("parseable() detects at least one numeric segment")
    void parseableDetectsNumericContent() {
        assertTrue(VersionCompare.parseable("1.2.3"));
        assertTrue(VersionCompare.parseable("v2"));
        assertFalse(VersionCompare.parseable("unknown"));
        assertFalse(VersionCompare.parseable(""));
        assertFalse(VersionCompare.parseable(null));
    }

    @Test
    @DisplayName("Missing trailing segments are treated as zero, not as an automatic win")
    void missingTrailingSegmentsActLikeZero() {
        assertTrue(VersionCompare.isNewer("1.1", "1.0.9"),
                "1.1 (эквивалент 1.1.0) обязан быть новее 1.0.9");
        assertFalse(VersionCompare.isNewer("1.0", "1.0.0"),
                "1.0 и 1.0.0 обязаны считаться эквивалентными — отсутствующий хвост трактуется как 0, а не игнорируется");
    }
}
