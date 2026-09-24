package ru.milkyway.plugmanreloaded.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteTextInjectionTest {

    private static final String QUOTE = String.valueOf((char) 34);
    private static final String NL = String.valueOf((char) 10);
    private static final String WS = String.valueOf((char) 92) + "s";
    private static final String LP = String.valueOf((char) 92) + "(";
    private static final String RP = String.valueOf((char) 92) + ")";

    private static final String EVIL =
            "Vault<click:run_command:'/op attacker'><green>[Скачать]</green></click>";

    private static ClickEvent findClick(Component component) {
        if (component.clickEvent() != null) return component.clickEvent();
        for (Component child : component.children()) {
            ClickEvent found = findClick(child);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void unescapedRemoteTitleWouldBecomeAClickableCommand() {
        Component rendered = HexColors.translateToComponent("Найдено: " + EVIL);
        assertNotNull(findClick(rendered),
                "если этот тест упал — значит движок разметки перестал разбирать click-теги, и вся "
                        + "модель угрозы ниже изменилась; пересмотри тест, а не удаляй его");
        assertEquals("/op attacker", findClick(rendered).value());
    }

    @Test
    void escapeTagsNeutralisesTheInjectionAndKeepsTextReadable() {
        Component rendered = HexColors.translateToComponent("Найдено: " + HexColors.escapeTags(EVIL));

        assertNull(findClick(rendered),
                "после экранирования у сообщения не должно остаться ни одного кликабельного действия");
        assertTrue(PlainTextComponentSerializer.plainText().serialize(rendered).contains("click:run_command"),
                "текст обязан остаться видимым как обычный текст, а не исчезнуть");
    }

    @Test
    void everyRemoteFieldReachesMessagesOnlyThroughEscapeTags() throws Exception {

        Pattern risky = Pattern.compile(
                "put" + LP + QUOTE + "(title|author|authors|description|latest|latest-version)" + QUOTE + WS + "*," + WS + "*(.+)$");

        List<String> offenders = new ArrayList<>();
        for (String file : new String[]{
                "src/main/java/ru/milkyway/plugmanreloaded/commands/sub/DownloadCommand.java",
                "src/main/java/ru/milkyway/plugmanreloaded/commands/sub/UpdateCommand.java"}) {
            List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = risky.matcher(lines.get(i).trim());
                if (!matcher.find()) continue;
                String value = matcher.group(2);
                boolean safe = value.contains("escapeTags")
                        || value.contains("formatSourceName")
                        || value.contains("formatSource")
                        || value.matches("^" + WS + "*(latestVer|currentVer|authors)" + WS + "*" + RP + "?" + WS + "*;?" + WS + "*$");
                if (!safe) {
                    offenders.add(Path.of(file).getFileName() + ":" + (i + 1) + " -> " + lines.get(i).trim());
                }
            }
        }

        assertEquals(List.of(), offenders,
                "Эти поля попадают в сообщение без HexColors.escapeTags. Их содержимое пишет посторонний "
                        + "(автор ресурса на площадке или автор чужого jar), поэтому оно может нести "
                        + "click-разметку и выполнить команду от имени администратора:" + NL + "  "
                        + String.join(NL + "  ", offenders));
    }
}
