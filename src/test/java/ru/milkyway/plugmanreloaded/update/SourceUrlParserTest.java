package ru.milkyway.plugmanreloaded.update;

import org.junit.jupiter.api.Test;
import ru.milkyway.plugmanreloaded.update.input.SourceUrlParser;

import static org.junit.jupiter.api.Assertions.*;

public class SourceUrlParserTest {

    @Test
    public void testSpigotUrls() {
        var r1 = SourceUrlParser.parse("https://www.spigotmc.org/resources/%E2%9C%8Bwg-piston-fixer%E2%9C%85.102591/");
        assertTrue(r1.success(), "Failed to parse unicode encoded spigot URL: " + r1.errorReason());
        assertEquals("102591", r1.source().ref());

        var r2 = SourceUrlParser.parse("https://spigotmc.org/resources/wg-piston-fixer.102591/updates");
        assertTrue(r2.success());
        assertEquals("102591", r2.source().ref());

        var r3 = SourceUrlParser.parse("https://www.spigotmc.org/resources/102591/");
        assertTrue(r3.success());
        assertEquals("102591", r3.source().ref());

        var r4 = SourceUrlParser.parse("https://www.spigotmc.org/resources/worldedit.60/history");
        assertTrue(r4.success());
        assertEquals("60", r4.source().ref());

        var r5 = SourceUrlParser.parse("spigotmc.org/resources/luckperms.28140?tab=updates");
        assertTrue(r5.success());
        assertEquals("28140", r5.source().ref());
    }

    @Test
    public void testModrinthUrls() {
        var r1 = SourceUrlParser.parse("https://modrinth.com/plugin/zmblocklimiter");
        assertTrue(r1.success());
        assertEquals("zmblocklimiter", r1.source().ref());

        var r2 = SourceUrlParser.parse("https://modrinth.com/plugin/zmblocklimiter/versions");
        assertTrue(r2.success());
        assertEquals("zmblocklimiter", r2.source().ref());

        var r3 = SourceUrlParser.parse("https://modrinth.com/mod/sodium/version/0.5.8");
        assertTrue(r3.success());
        assertEquals("sodium", r3.source().ref());

        var r4 = SourceUrlParser.parse("https://modrinth.com/project/chunky/gallery");
        assertTrue(r4.success());
        assertEquals("chunky", r4.source().ref());

        var r5 = SourceUrlParser.parse("https://modrinth.com/datapack/terralith");
        assertTrue(r5.success());
        assertEquals("terralith", r5.source().ref());
    }

    @Test
    public void testGithubUrls() {
        var r1 = SourceUrlParser.parse("https://github.com/sk89q/worldguard");
        assertTrue(r1.success());
        assertEquals("sk89q/worldguard", r1.source().ref());

        var r2 = SourceUrlParser.parse("https://github.com/sk89q/worldguard/releases");
        assertTrue(r2.success());
        assertEquals("sk89q/worldguard", r2.source().ref());

        var r3 = SourceUrlParser.parse("https://github.com/sk89q/worldguard/releases/tag/7.0.18");
        assertTrue(r3.success());
        assertEquals("sk89q/worldguard", r3.source().ref());

        var r4 = SourceUrlParser.parse("https://github.com/sk89q/worldguard.git");
        assertTrue(r4.success());
        assertEquals("sk89q/worldguard", r4.source().ref());

        var r5 = SourceUrlParser.parse("https://github.com/sk89q");
        assertTrue(r5.success());
        assertEquals("sk89q", r5.source().ref());
        assertEquals("true", r5.source().options().get("ownerOnly"));
    }

    @Test
    public void testHangarUrls() {
        var r1 = SourceUrlParser.parse("https://hangar.papermc.io/MiniPlaceholders/MiniPlaceholders");
        assertTrue(r1.success());
        assertEquals("MiniPlaceholders/MiniPlaceholders", r1.source().ref());

        var r2 = SourceUrlParser.parse("https://hangar.papermc.io/MiniPlaceholders/MiniPlaceholders/versions/2.2.3");
        assertTrue(r2.success());
        assertEquals("MiniPlaceholders/MiniPlaceholders", r2.source().ref());
    }

    @Test
    public void testRuSpigotUrls() {
        var r1 = SourceUrlParser.parse("https://spigotmc.ru/resources/deluxemenus.43/");
        assertTrue(r1.success());
        assertTrue(r1.source().url().contains("deluxemenus.43"));

        var r2 = SourceUrlParser.parse("https://spigotmc.ru/dev/v1/resource/2206/version/");
        assertTrue(r2.success());
        assertEquals("https://spigotmc.ru/resources/2206/", r2.source().url());
    }

    @Test
    public void testJenkinsUrls() {
        var r1 = SourceUrlParser.parse("https://ci.athion.net/job/FastAsyncWorldEdit/lastSuccessfulBuild/");
        assertTrue(r1.success());
        assertEquals("https://ci.athion.net/job/FastAsyncWorldEdit", r1.source().ref());

        var r2 = SourceUrlParser.parse("https://ci.opencollab.dev/job/GeyserMC/job/Geyser/job/master/1234/");
        assertTrue(r2.success());
        assertEquals("https://ci.opencollab.dev/job/GeyserMC/job/Geyser/job/master", r2.source().ref());
    }

    @Test
    public void testWrappedUrls() {
        var r1 = SourceUrlParser.parse("<https://modrinth.com/plugin/luckperms>");
        assertTrue(r1.success());
        assertEquals("luckperms", r1.source().ref());

        var r2 = SourceUrlParser.parse("\"https://github.com/sk89q/worldguard\"");
        assertTrue(r2.success());
        assertEquals("sk89q/worldguard", r2.source().ref());
    }

    @Test
    public void testInvalidUrls() {
        var r1 = SourceUrlParser.parse("https://modrinth.com/plugin/test.jar");
        assertFalse(r1.success());

        var r2 = SourceUrlParser.parse("https://unsupported-site.com/plugin/123");
        assertFalse(r2.success());

        var r3 = SourceUrlParser.parse("");
        assertFalse(r3.success());
    }
}
