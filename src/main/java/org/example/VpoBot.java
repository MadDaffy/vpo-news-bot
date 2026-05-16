package org.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import jakarta.annotation.PostConstruct;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Telegram‑бот с точным поиском, AI‑расширением и сбором обратной связи.
 * <p>
 * Логика:
 * <ul>
 *   <li>Обычный запрос — точный поиск фразы.</li>
 *   <li>Кнопка «Расширить с ИИ» — LLM генерирует словоформы, парсер ищет по ним.</li>
 *   <li>Кнопки ❤️/👎 — запрос комментария, сохранение в файлы логов с данными пользователя.</li>
 * </ul>
 */
@Slf4j
@Component
public class VpoBot extends TelegramLongPollingBot {

    private final String botName;
    private final String botToken;
    private final List<Long> allowedUsers;
    private final Parser parser;
    private final AiService aiService;

    // Хранилище состояний
    private final Map<String, String> lastQuery = new ConcurrentHashMap<>();
    private final Map<String, List<NewsPost>> exactSearchResult = new ConcurrentHashMap<>();
    // Ожидание комментария: chatId -> "like" или "dislike", и данные пользователя
    private final Map<String, String> pendingComment = new ConcurrentHashMap<>();
    private final Map<String, User> pendingCommentUser = new ConcurrentHashMap<>();

    // Константы callback-данных
    private static final String CALLBACK_EXPAND = "expand";
    private static final String CALLBACK_MORE = "more";
    private static final String CALLBACK_LIKE = "like";
    private static final String CALLBACK_DISLIKE = "dislike";
    private static final String CALLBACK_COMMENT_YES = "comment_yes";
    private static final String CALLBACK_COMMENT_NO = "comment_no";

    // Московский часовой пояс
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    @PostConstruct
    public void init() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Bot registered successfully: {}", botName);
            // Создаём папки для логов, если их нет
            Files.createDirectories(Path.of("logs/likes"));
            Files.createDirectories(Path.of("logs/dislikes"));
        } catch (TelegramApiException | IOException e) {
            log.error("Error registering bot or creating log directories", e);
        }
    }

    @Override
    public String getBotUsername() { return botName; }
    @Override
    public String getBotToken() { return botToken; }

    @Override
    public void onUpdateReceived(Update update) {
        // Обработка нажатий inline-кнопок
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update);
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        Long userId = update.getMessage().getFrom().getId();
        String chatId = update.getMessage().getChatId().toString();
        String messageText = update.getMessage().getText();

        if (!allowedUsers.contains(userId)) {
            sendTextMessage(chatId, "⛔ Доступ запрещён.");
            return;
        }

        log.info("User request: userName={} (ID={}), query={}",
                update.getMessage().getFrom().getUserName(), userId, messageText);

        // Если ожидается комментарий — сохраняем его
        if (pendingComment.containsKey(chatId)) {
            String feedbackType = pendingComment.remove(chatId);
            User user = pendingCommentUser.remove(chatId);
            saveFeedback(feedbackType, chatId, user, messageText);
            sendTextMessage(chatId, "Спасибо за ваш комментарий!");
            return;
        }

        lastQuery.put(chatId, messageText);

        if (messageText.equals("/start")) {
            sendStartMessage(chatId);
            return;
        }

        // Точный поиск по умолчанию
        performExactSearch(chatId, messageText);
    }

    // ======================== ОБРАБОТКА КНОПОК =========================

    private void handleCallbackQuery(Update update) {
        String data = update.getCallbackQuery().getData();
        String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();
        User user = update.getCallbackQuery().getFrom();   // Тот, кто нажал кнопку

        // Составные callback'ы: "comment_yes:like"
        String[] parts = data.split(":", 2);
        String action = parts[0];
        String payload = parts.length > 1 ? parts[1] : "";

        switch (action) {
            case CALLBACK_EXPAND:
                String originalQuery = lastQuery.get(chatId);
                if (originalQuery != null) {
                    sendTextMessage(chatId, "⏳ Это займет немного времени...");
                    performExpandedSearch(chatId, originalQuery);
                } else {
                    sendTextMessage(chatId, "Не удалось определить запрос. Повторите поиск.");
                }
                break;

            case CALLBACK_MORE:
                sendTextMessage(chatId, "⏳ Загрузка следующих результатов...");
                break;

            case CALLBACK_LIKE:
                askForComment(chatId, "like");
                break;

            case CALLBACK_DISLIKE:
                askForComment(chatId, "dislike");
                break;

            case CALLBACK_COMMENT_YES:
                // Запоминаем, что ждём комментарий, и убираем кнопки
                pendingComment.put(chatId, payload);
                pendingCommentUser.put(chatId, user);
                editMessageTextAndRemoveKeyboard(chatId, messageId, "Ожидаю ваш комментарий...");
                break;

            case CALLBACK_COMMENT_NO:
                // Убираем кнопки и благодарим
                editMessageTextAndRemoveKeyboard(chatId, messageId, "Спасибо за обратную связь!");
                break;
        }
    }

    // ======================== ПОИСК =========================

    private void performExactSearch(String chatId, String query) {
        sendTextMessage(chatId, "⏳ Ищу новости...");

        List<NewsPost> news = parser.searchExactPhrase(query);

        if (news.isEmpty()) {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText("По запросу \"" + query + "\" ничего не найдено.");
            msg.setReplyMarkup(createSearchAgainKeyboard());
            executeMessage(msg);
            return;
        }

        exactSearchResult.put(chatId, new ArrayList<>(news));
        sendNewsList(chatId, news);
    }

    private void performExpandedSearch(String chatId, String query) {
        List<String> phrases = aiService.expandQuery(query);
        if (phrases == null) {
            sendTextMessage(chatId, "🤖 ИИ-помощник сейчас недоступен. Попробуйте позже.");
            return;
        }

        String combinedKeywords = String.join(" ", phrases);
        List<NewsPost> allNews = parser.searchByKeywords(combinedKeywords);

        if (allNews.isEmpty()) {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText("По запросу \"" + query + "\" ничего не найдено.");
            msg.setReplyMarkup(createSearchAgainKeyboard());
            executeMessage(msg);
            return;
        }

        List<NewsPost> exactResult = exactSearchResult.get(chatId);
        if (exactResult != null && newsListsAreEqual(exactResult, allNews)) {
            sendTextMessage(chatId, "😔 ИИ-помощник не нашел дополнительной информации");
            return;
        }

        sendNewsList(chatId, allNews);
    }

    private void sendNewsList(String chatId, List<NewsPost> newsList) {
        StringBuilder response = new StringBuilder("🔹 Найдено " + newsList.size() + " новостей:\n\n");
        int shown = 0, maxLength = 3500;
        for (NewsPost post : newsList) {
            String entry = (shown + 1) + ". " + post.getTitle() + "\n" + post.getLink() + "\n\n";
            if (response.length() + entry.length() > maxLength) break;
            response.append(entry);
            shown++;
        }
        if (shown < newsList.size()) {
            response.append("Показано ").append(shown).append(" из ").append(newsList.size()).append(".\n");
        }

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(response.toString());
        msg.setReplyMarkup(createResultKeyboard(newsList.size() > shown));
        msg.enableHtml(true);
        executeMessage(msg);
    }

    private boolean newsListsAreEqual(List<NewsPost> list1, List<NewsPost> list2) {
        if (list1.size() != list2.size()) return false;
        Set<String> titles1 = list1.stream().map(n -> n.getTitle() == null ? "" : n.getTitle()).collect(Collectors.toSet());
        Set<String> titles2 = list2.stream().map(n -> n.getTitle() == null ? "" : n.getTitle()).collect(Collectors.toSet());
        return titles1.equals(titles2);
    }

    // ======================== ОБРАТНАЯ СВЯЗЬ =========================

    /** Отправляет сообщение с вопросом о комментарии. */
    private void askForComment(String chatId, String feedbackType) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        InlineKeyboardButton yesBtn = new InlineKeyboardButton();
        yesBtn.setText("Да, конечно");
        yesBtn.setCallbackData(CALLBACK_COMMENT_YES + ":" + feedbackType);
        row.add(yesBtn);

        InlineKeyboardButton noBtn = new InlineKeyboardButton();
        noBtn.setText("Нет");
        noBtn.setCallbackData(CALLBACK_COMMENT_NO);
        row.add(noBtn);
        keyboard.add(row);

        markup.setKeyboard(keyboard);

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText("Хотите оставить комментарий?");
        msg.setReplyMarkup(markup);
        executeMessage(msg);
    }

    /** Сохраняет комментарий в файл логов с данными пользователя. */
    private void saveFeedback(String type, String chatId, User user, String comment) {
        String folder = type.equals("like") ? "logs/likes" : "logs/dislikes";
        String today = LocalDate.now(MOSCOW_ZONE).format(DATE_FORMAT);
        String fileName = folder + "/" + today + ".log";
        String timestamp = LocalDateTime.now(MOSCOW_ZONE).format(TIMESTAMP_FORMAT);

        // Никнейм, имя и фамилия (что есть)
        String firstName = user.getFirstName() != null ? user.getFirstName() : "";
        String lastName = user.getLastName() != null ? user.getLastName() : "";
        String userName = user.getUserName() != null ? "@" + user.getUserName() : "";
        String userInfo = (firstName + " " + lastName).trim();
        if (!userName.isEmpty()) {
            userInfo += " (" + userName + ")";
        }

        try {
            Files.createDirectories(Path.of(folder));
            try (PrintWriter pw = new PrintWriter(new FileWriter(fileName, true))) {
                pw.println(timestamp + " | " + userInfo + " | " + comment);
            }
            log.info("Feedback saved: type={}, file={}", type, fileName);
        } catch (IOException e) {
            log.error("Ошибка сохранения фидбека", e);
        }
    }

    // ======================== КЛАВИАТУРЫ =========================

    private InlineKeyboardMarkup createResultKeyboard(boolean hasMore) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton expandBtn = new InlineKeyboardButton();
        expandBtn.setText("🔍 Расширить результаты с ИИ");
        expandBtn.setCallbackData(CALLBACK_EXPAND);
        row1.add(expandBtn);

        if (hasMore) {
            InlineKeyboardButton moreBtn = new InlineKeyboardButton();
            moreBtn.setText("📄 Показать ещё");
            moreBtn.setCallbackData(CALLBACK_MORE);
            row1.add(moreBtn);
        }
        keyboard.add(row1);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton likeBtn = new InlineKeyboardButton();
        likeBtn.setText("❤️ Понравилось");
        likeBtn.setCallbackData(CALLBACK_LIKE);
        row2.add(likeBtn);

        InlineKeyboardButton dislikeBtn = new InlineKeyboardButton();
        dislikeBtn.setText("👎 Не подходит");
        dislikeBtn.setCallbackData(CALLBACK_DISLIKE);
        row2.add(dislikeBtn);
        keyboard.add(row2);

        markup.setKeyboard(keyboard);
        return markup;
    }

    private InlineKeyboardMarkup createSearchAgainKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton expandBtn = new InlineKeyboardButton();
        expandBtn.setText("🔍 Расширить результаты с ИИ");
        expandBtn.setCallbackData(CALLBACK_EXPAND);
        row.add(expandBtn);
        keyboard.add(row);
        markup.setKeyboard(keyboard);
        return markup;
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ =========================

    private void sendStartMessage(String chatId) {
        sendTextMessage(chatId,
                "Привет! Я бот для поиска новостей.\n\n" +
                        "🔹 Просто отправь слово или фразу — я найду подходящие новости.\n" +
                        "🔹 Я понимаю склонения, синонимы и перефразы.");
    }

    private void sendTextMessage(String chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        executeMessage(msg);
    }

    /** Редактирует сообщение и убирает клавиатуру. */
    private void editMessageTextAndRemoveKeyboard(String chatId, Integer messageId, String text) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId);
        edit.setMessageId(messageId);
        edit.setText(text);
        // Убираем клавиатуру
        edit.setReplyMarkup(null);
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            log.error("Error editing message", e);
        }
    }

    private void editMessageText(String chatId, Integer messageId, String text) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId);
        edit.setMessageId(messageId);
        edit.setText(text);
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            log.error("Error editing message", e);
        }
    }

    private void executeMessage(SendMessage msg) {
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Error sending message", e);
        }
    }
}