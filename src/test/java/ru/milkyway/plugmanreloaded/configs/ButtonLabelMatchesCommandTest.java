package ru.milkyway.plugmanreloaded.configs;

import ru.milkyway.plugmanreloaded.utils.ChatButtonFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ChatButtonFactory} не держит своих текстов на случай отсутствия ключа в конфиге —
 * подпись и hover кнопки берутся ТОЛЬКО из {@code messages/*.yml} (см. javadoc на самом классе).
 * Отсюда два инварианта, которые проверяет этот тест:
 * <ul>
 *   <li>с пустым конфигом кнопка не рендерится вовсе ({@link Component#empty()}) — ни утечки
 *       зашитого в код текста, ни падения;</li>
 *   <li>со штатным {@code ru-messages.yml} подпись кнопки семантически соответствует команде,
 *       которую она выполняет (кнопка «перезагрузить» не должна брать подпись от «перезапустить»
 *       и т.п.) — но только для токенов, у которых в конфиге реально есть ключ: {@code {restart-button}},
 *       {@code {cascade-restart-button}}, {@code {delete-button}}, {@code {confirm-button}},
 *       {@code {source-link}} в шипуемых шаблонах не используются вовсе (проверено: ни в одном
 *       {@code messages/*.yml}, ни в Java-коде за пределами самого {@code ChatButtonFactory}),
 *       поэтому у них законно нет ключа и с пустым, и со штатным конфигом;</li>
 *   <li>{@code {url}} — особый случай: в шаблонах ({@code download.buttons.url},
 *       {@code update.confirm-prerelease-buttons.url} и т.п.) у него задан только {@code hover},
 *       а {@code text} намеренно отсутствует — видимый текст кнопки автогенерируется из самого
 *       адреса (подчёркнутая ссылка). Поэтому отсутствие {@code text}-ключа для этого токена —
 *       не «недостающий контент», а штатный формат; проверяется отдельно в {@code urlTokenAutoGeneratesLabelFromRawUrl}.</li>
 * </ul>
 */
class ButtonLabelMatchesCommandTest {

    private static YamlConfiguration config;

    private record Expect(String token, String commandStarts, String labelMustContain, String meaning) {}

    // Штатный ru-messages.yml — эти 6 токенов реально используются в /plm info (actions.info.buttons.*).
    private static final List<Expect> EXPECTS_SHIPPED = List.of(
            new Expect("{reload-button}", "/plm reload", "Перезагрузить", "перезагрузка"),
            new Expect("{cascade-reload-button}", "/plm reload", "зависим", "каскадная перезагрузка"),
            new Expect("{enable-button}", "/plm enable", "Включить", "включение"),
            new Expect("{disable-button}", "/plm disable", "Выключить", "выключение"),
            new Expect("{load-button}", "/plm load", "Загрузить", "загрузка в память"),
            new Expect("{unload-button}", "/plm unload", "Выгрузить", "выгрузка из памяти")
    );

    // Ни в одном messages/*.yml, ни в Java-коде (кроме самого ChatButtonFactory) эти токены не
    // встречаются — законно нет ключа в конфиге, значит и с пустым, и со штатным конфигом кнопка
    // обязана быть Component.empty(), а не что-то показывать из зашитого в код текста.
    // {url} сюда НЕ входит — см. urlTokenAutoGeneratesLabelFromRawUrl.
    private static final List<String> EXPECTED_EMPTY_ALWAYS = List.of(
            "{confirm-button}", "{delete-button}", "{restart-button}", "{cascade-restart-button}", "{source-link}"
    );

    private static String label(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c).trim();
    }

    private static String command(Component c) {
        ClickEvent click = c.clickEvent();
        return click == null ? null : String.valueOf(click.value());
    }

    @BeforeAll
    static void setUp() throws Exception {
        try (InputStream in = ButtonLabelMatchesCommandTest.class.getClassLoader().getResourceAsStream("messages/ru-messages.yml")) {
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void emptyConfigRendersNoButtonAtAll() {
        Map<String, String> placeholders = Map.of("plugin", "TestPlugin");
        YamlConfiguration empty = new YamlConfiguration();
        List<String> leaked = new ArrayList<>();

        for (String token : ChatButtonFactory.BUTTON_TOKENS) {
            if (token.equals("{buttons}")) continue;
            Component button = ChatButtonFactory.createButton(empty, token, placeholders, null);
            if (!label(button).isEmpty() || button.clickEvent() != null) {
                leaked.add(token + ": подпись «" + label(button) + "», команда «" + command(button) + "»");
            }
        }

        assertTrue(leaked.isEmpty(),
                "С пустым конфигом (нет ни одного ключа actions.*.buttons.*) кнопка обязана быть "
                        + "Component.empty() — в ChatButtonFactory больше нет зашитого в код текста-заглушки "
                        + "на этот случай. Утечка текста/команды без ключа в конфиге:\n  "
                        + String.join("\n  ", leaked));
    }

    @Test
    void labelsMatchCommandsWithShippedConfig() {
        Map<String, String> placeholders = Map.of("plugin", "TestPlugin");
        List<String> problems = new ArrayList<>();

        for (Expect e : EXPECTS_SHIPPED) {
            Component button = ChatButtonFactory.createButton(config, e.token(), placeholders, null);
            String text = label(button);
            String cmd = command(button);

            if (cmd == null || !cmd.startsWith(e.commandStarts())) {
                problems.add(e.token() + ": ожидалась команда «" + e.commandStarts() + " ...», получена «" + cmd + "»");
                continue;
            }
            if (!text.toLowerCase(java.util.Locale.ROOT).contains(e.labelMustContain().toLowerCase(java.util.Locale.ROOT))) {
                problems.add(e.token() + ": команда «" + cmd + "» (" + e.meaning()
                        + "), а подпись кнопки «" + text + "» — в ней нет слова «" + e.labelMustContain() + "»");
            }
        }

        assertTrue(problems.isEmpty(),
                "Подпись кнопки не соответствует команде, которую она выполняет (штатный ru-messages.yml):\n  "
                        + String.join("\n  ", problems)
                        + "\nПричина такого расхождения — откат на ключ ЧУЖОГО действия: например у кнопки "
                        + "перезапуска брать подпись из actions.info.buttons.reload.");
    }

    @Test
    void urlTokenAutoGeneratesLabelFromRawUrl() {
        // Баг, найденный на живом сервере через реального игрока (не RCON): {url} с непустым
        // target.url() рендерился в пустую строку в download.confirm-single. Причина — URL_LINK
        // требовал наличия ключа actions.download.buttons.url.text, а в конфиге для url: задан
        // только hover (см. javadoc класса). Здесь фиксируем правильное поведение: отсутствие
        // text-ключа не мешает рендеру — подпись автогенерируется из самого адреса.
        Map<String, String> placeholders = Map.of("plugin", "TestPlugin", "url", "https://modrinth.com/plugin/essentialsx");

        Component button = ChatButtonFactory.createButton(config, "{url}", placeholders, null);

        assertTrue(!label(button).isEmpty(),
                "actions.download.buttons.url не содержит ключа text (только hover) — это штатный "
                        + "формат конфига, {url} обязан всё равно отрендериться (подпись = сам адрес), "
                        + "а не превратиться в Component.empty()");
        assertTrue(label(button).contains("https://modrinth.com/plugin/essentialsx"),
                "Подпись кнопки {url} должна содержать сам адрес, получено: «" + label(button) + "»");
        ClickEvent click = button.clickEvent();
        assertTrue(click != null && ClickEvent.Action.OPEN_URL.equals(click.action())
                        && "https://modrinth.com/plugin/essentialsx".equals(click.value()),
                "Клик по кнопке {url} должен открывать сам адрес, получено: " + click);
    }

    @Test
    void downloadConfirmDependenciesButtonsRenderCorrectly() {
        // Живой игрок подтвердил {confirm-download-button}/{cancel-button}/{url} в download.confirm-single
        // (см. urlTokenAutoGeneratesLabelFromRawUrl). Ветку download.confirm-dependencies (нужен плагин
        // с реально недостающей hard-зависимостью в Modrinth-метаданных) вживую воспроизвести не удалось —
        // перепробованы Multiverse-Portals, Multiverse-NetherPortals, PlotSquared: либо зависимости
        // оказывались уже удовлетворены, либо падала не связанная с этим ошибка скачивания. Но
        // {confirm-download-deps-button}/{cancel-button} в этом шаблоне рендерятся ТЕМ ЖЕ кодовым путём
        // (buildCommandButton + те же ключи actions.download.buttons.*), что уже подтверждено вживую для
        // confirm-single — поэтому здесь достаточно юнит-проверки того же инварианта.
        Map<String, String> placeholders = Map.of("plugin", "TestPlugin", "token", "abc123");

        Component deps = ChatButtonFactory.createButton(config, "{confirm-download-deps-button}", placeholders, "download.confirm-dependencies");
        assertTrue(label(deps).toLowerCase(java.util.Locale.ROOT).contains("dependenc")
                        || label(deps).toLowerCase(java.util.Locale.ROOT).contains("завис"),
                "{confirm-download-deps-button}: ожидалась подпись про зависимости, получено «" + label(deps) + "»");
        assertTrue(command(deps) != null && command(deps).equals("/plm download TestPlugin confirm abc123"),
                "{confirm-download-deps-button}: неверная команда «" + command(deps) + "»");

        Component cancel = ChatButtonFactory.createButton(config, "{cancel-button}", placeholders, "download.confirm-dependencies");
        assertTrue(!label(cancel).isEmpty(), "{cancel-button} в download.confirm-dependencies не должен быть пустым");
        assertTrue(command(cancel) != null && command(cancel).equals("/plm download cancel TestPlugin abc123"),
                "{cancel-button}: неверная команда «" + command(cancel) + "»");
    }

    @Test
    void remainingLiveVerifiedButtonsRenderCorrectly() {
        // Оставшиеся токены, проверенные вживую через реального игрока (mineflayer) на тестовом
        // сервере: /plm info Vault, /plm reload Multiverse-Core (с зависимым Multiverse-Inventories),
        // /plm update -all, /plm download essentials (search-card). Здесь — юнит-фиксация того же
        // поведения на штатном конфиге, чтобы регрессия ловилась без повторного живого прогона.
        Map<String, String> infoPh = Map.of("plugin", "Vault", "is-enabled", "true");
        assertTrue(command(ChatButtonFactory.createButton(config, "{toggle-button}", infoPh, "info")).equals("/plm disable Vault"),
                "{toggle-button} для включённого плагина должен предлагать disable");

        Map<String, String> reloadPh = Map.of("plugin", "Multiverse-Core", "token", "tok1");
        assertTrue(command(ChatButtonFactory.createButton(config, "{deps-button}", reloadPh, "reload.confirm")).equals("/plm reload Multiverse-Core -c tok1"));
        assertTrue(command(ChatButtonFactory.createButton(config, "{single-button}", reloadPh, "reload.confirm")).equals("/plm reload Multiverse-Core -f tok1"));

        Map<String, String> allPh = Map.of("plugin", "", "token", "tok2");
        assertTrue(command(ChatButtonFactory.createButton(config, "{all-button}", allPh, "update.confirm-all")).equals("/plm update -all -y tok2"));

        // Примечание: живьём (search-card в /plm download essentials) команда была
        // "/plm download EssentialsX/Essentials -s hangar -g" — карточки результатов поиска строит
        // сам DownloadCommand (со своим форматированием команды под конкретный источник), это его
        // собственный путь, а не диспетчеризация ChatButtonFactory.DOWNLOAD_BUTTON ниже. Тем не менее
        // сам токен в ChatButtonFactory обязан оставаться рабочим (на случай прямого использования
        // в другом шаблоне) — это и проверяем здесь.
        Map<String, String> cardPh = Map.of("plugin", "Essentials", "project", "Essentials");
        Component dl = ChatButtonFactory.createButton(config, "{download-button}", cardPh, "download.search-card");
        assertTrue(command(dl) != null && command(dl).startsWith("/plm download Essentials -y"),
                "{download-button}: получено «" + command(dl) + "»");
        assertTrue(!label(ChatButtonFactory.createButton(config, "{info-button}", cardPh, "download.search-card")).isEmpty());
        assertTrue(!label(ChatButtonFactory.createButton(config, "{web-button}", Map.of("plugin", "Essentials", "url", "https://example.com"), "download.search-card")).isEmpty());
    }

    @Test
    void deadTokensStayEmptyEvenWithShippedConfig() {
        Map<String, String> placeholders = Map.of("plugin", "TestPlugin");
        List<String> unexpectedlyRendered = new ArrayList<>();

        for (String token : EXPECTED_EMPTY_ALWAYS) {
            Component button = ChatButtonFactory.createButton(config, token, placeholders, null);
            if (!label(button).isEmpty() || button.clickEvent() != null) {
                unexpectedlyRendered.add(token + ": подпись «" + label(button) + "», команда «" + command(button) + "»");
            }
        }

        assertTrue(unexpectedlyRendered.isEmpty(),
                "Эти токены считались мертвыми (нет ключа ни в одном messages/*.yml) — если тест упал, "
                        + "значит для одного из них появился реальный ключ в ru-messages.yml, и его нужно "
                        + "перенести в EXPECTS_SHIPPED с проверкой семантики подписи:\n  "
                        + String.join("\n  ", unexpectedlyRendered));
    }
}
