package ru.milkyway.plugmanreloaded.managers;
import ru.milkyway.plugmanreloaded.utils.NettyGuard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SafetyManagerTest {

    @Test
    void testAssessNullPlugin() {
        SafetyManager advisor = new SafetyManager(null);
        SafetyManager.SafetyAssessment assessment = advisor.assess(null);

        assertNotNull(assessment);
        assertEquals(SafetyManager.PluginRiskLevel.SAFE, assessment.riskLevel());
        assertTrue(assessment.dependents().isEmpty());
    }
}
