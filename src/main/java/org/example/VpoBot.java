package org.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Telegram-бот с гибридным AI-поиском новостей.
 * <p>
 * Логика:
 * <ol>
 *   <li>Пользователь отправляет запрос (слово, фразу или предложение).</li>
 *   <li>AI генерирует словоформы и основы знаменательных слов (через {@link AiService#expandQuery}).</li>
 *   <li>Парсер ищет новости, содержащие любую из этих фраз (по подстроке).</li>
 *   <li>Результаты сортируются: сначала точные совпадения с исходным запросом, затем остальные.</li>
 * </ol>
 */
@Slf4j
@Component
public class VpoBot extends TelegramLongPollingBot {

    private final String botName;
    private final String botToken;
    private final List<Long> allowedUsers;
    private final Parser parser;
    private final AiService aiService;

    public VpoBot(@Value("${bot.name}") String botName,
                  @Value("${bot.token}") String botToken,
                  @Value("${bot.allowed-users}") List<Long> allowedUsers,
                  Parser parser,
                  AiService aiService) {
        this.botName = botName;
        this.botToken = botToken;
        this.allowedUsers = allowedUsers;
        this.parser = parser;
        this.aiService = aiService;
    }

    /**
     * Инициализация: регистрация бота в Telegram API.
     */
    @PostConstruct
    public void init() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Bot registered successfully: {}", botName);
        } catch (TelegramApiException e) {
            log.error("Error registering bot", e);
        }
    }

    @Override
    public String getBotUsername() { return botName; }

    @Override
    public String getBotToken() { return botToken; }

    /**
     * Обработчик входящих сообщений.
     */
    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        Long userId = update.getMessage().getFrom().getId();
        String chatId = update.getMessage().getChatId().toString();
        String messageText = update.getMessage().getText();

        // Проверка белого списка
        if (!allowedUsers.contains(userId)) {
            sendTextMessage(chatId, "⛔ Доступ запрещён.");
            return;
        }

        log.info("User request: userName={} (ID={}), query={}",
                update.getMessage().getFrom().getUserName(), userId, messageText);

        if (messageText.equals("/start")) {
            sendTextMessage(chatId, "Привет! Я бот для поиска новостей.\n\n" +
                    "🔹 Просто отправь слово или фразу — я найду подходящие новости.\n" +
                    "🔹 Я понимаю склонения, синонимы и перефразы.");
            return;
        }

        // Шаг 1: расширяем запрос через LLM
        sendTextMessage(chatId, "⏳ Ищу новости...");
        List<String> searchPhrases = aiService.expandQuery(messageText);
        log.info("Search phrases: {}", searchPhrases);

        // Шаг 2: ищем новости по всем фразам
        String combinedKeywords = String.join(" ", searchPhrases);
        List<NewsPost> allNews = parser.parse(combinedKeywords);

        if (allNews.isEmpty()) {
            sendTextMessage(chatId, "По запросу \"" + messageText + "\" ничего не найдено.");
            return;
        }

        // Шаг 3: сортируем — точные совпадения с исходным запросом выше
        List<NewsPost> exactMatches = allNews.stream()
                .filter(n -> {
                    String t = n.getTitle() != null ? n.getTitle().toLowerCase() : "";
                    String d = n.getDescription() != null ? n.getDescription().toLowerCase() : "";
                    return t.contains(messageText.toLowerCase()) || d.contains(messageText.toLowerCase());
                })
                .collect(Collectors.toList());

        List<NewsPost> otherNews = allNews.stream()
                .filter(n -> !exactMatches.contains(n))
                .collect(Collectors.toList());

        List<NewsPost> sortedNews = new ArrayList<>(exactMatches);
        sortedNews.addAll(otherNews);

        // Шаг 4: формируем ответ с учётом лимита Telegram (4096 символов)
        StringBuilder response = new StringBuilder("🔹 Найдено " + sortedNews.size() + " новостей:\n\n");
        int shown = 0, maxLength = 4000;
        for (NewsPost post : sortedNews) {
            String entry = (shown + 1) + ". " + post.getTitle() + "\n" + post.getLink() + "\n\n";
            if (response.length() + entry.length() > maxLength) break;
            response.append(entry);
            shown++;
        }
        if (shown < sortedNews.size()) {
            response.append("Показано ").append(shown).append(" из ").append(sortedNews.size()).append(".\n");
        }

        sendTextMessage(chatId, response.toString());
    }

    /**
     * Отправляет текстовое сообщение в чат.
     */
    private void sendTextMessage(String chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Error sending message", e);
        }
    }
}