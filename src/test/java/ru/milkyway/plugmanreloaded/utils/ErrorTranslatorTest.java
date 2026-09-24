package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.api.FailureReason;

import static org.junit.jupiter.api.Assertions.*;

class ErrorTranslatorTest {

    @Test
    @DisplayName("Test translate NoSuchMethodError")
    void testTranslateNoSuchMethod() {
        NoSuchMethodError error = new NoSuchMethodError("org.bukkit.Server.getOnlinePlayers()");
        ErrorAnalyzer.ErrorDetails result = ErrorAnalyzer.analyze(error, null, null);

        assertEquals(FailureReason.INCOMPATIBLE_CORE, result.reason());
        assertEquals("org.bukkit.Server.getOnlinePlayers()", result.placeholders().get("member"));
    }

    @Test
    @DisplayName("Test translate IncompatibleClassChangeError")
    void testTranslateIncompatibleClassChange() {
        IncompatibleClassChangeError error = new IncompatibleClassChangeError("class change");
        ErrorAnalyzer.ErrorDetails result = ErrorAnalyzer.analyze(error, null, null);

        assertEquals(FailureReason.INCOMPATIBLE_CLASS_CHANGE, result.reason());
    }

    @Test
    @DisplayName("Test translate database locked error")
    void testTranslateDatabaseLocked() {
        Exception error = new Exception("[SQLITE_BUSY] The database file is locked (database is locked)");
        ErrorAnalyzer.ErrorDetails result = ErrorAnalyzer.analyze(error, null, null);

        assertEquals(FailureReason.DATABASE_LOCKED, result.reason());
    }

    @Test
    @DisplayName("Test translate bind port in use error")
    void testTranslatePortInUse() {
        Exception error = new Exception("java.net.BindException: Address already in use: bind");
        ErrorAnalyzer.ErrorDetails result = ErrorAnalyzer.analyze(error, null, null);

        assertEquals(FailureReason.PORT_IN_USE, result.reason());
    }
}
