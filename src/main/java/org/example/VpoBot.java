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
 * Telegram-бот для поиска новостей с гибридным ранжированием
 * (стемминг + векторный поиск через Reciprocal Rank Fusion).
 * <p>
 * Использует морфологический анализ (Apache Lucene) для лексической
 * составляющей и облачное API для очистки запроса, расшифровки
 * аббревиатур и получения эмбеддингов.
 * <p>
 * Результаты выводятся с пагинацией (по 5 новостей на странице)
 * и кнопками обратной связи.
 */
@Slf4j
@Component
public class VpoBot extends TelegramLongPollingBot {

    // ======================== Конфигурация ========================

    private final String botName;
    private final String botToken;
    private final List<Long> allowedUsers;
    private final Parser parser;
    private final CloudAiService cloudAiService;
    private final NewsStorageService newsStorageService;

    // ======================== Состояния ========================

    private final Map<String, List<NewsPost>> lastNewsList = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastShownOffset = new ConcurrentHashMap<>();
    // Обратная связь: ожидание комментария (chatId → "like"/"dislike")
    private final Map<String, String> pendingComment = new ConcurrentHashMap<>();
    private final Map<String, User> pendingCommentUser = new ConcurrentHashMap<>();

    // ======================== Константы callback-данных ========================

    private static final String CALLBACK_PREV = "prev";
    private static final String CALLBACK_NEXT = "next";
    private static final String CALLBACK_LIKE = "like";
    private static final String CALLBACK_DISLIKE = "dislike";
    private static final String CALLBACK_COMMENT_YES = "comment_yes";
    private static final String CALLBACK_COMMENT_NO = "comment_no";

    // ======================== Временные константы ========================

    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ======================== Конструктор ========================

    public VpoBot(@Value("${bot.name}") String botName,
                  @Value("${bot.token}") String botToken,
                  @Value("${bot.allowed-users}") List<Long> allowedUsers,
                  Parser parser,
                  CloudAiService cloudAiService,
                  NewsStorageService newsStorageService) {
        this.botName = botName;
        this.botToken = botToken;
        this.allowedUsers = allowedUsers;
        this.parser = parser;
        this.cloudAiService = cloudAiService;
        this.newsStorageService = newsStorageService;
    }

    // ======================== Инициализация ========================

    @PostConstruct
    public void init() {
        try {
            // Таймауты HTTP-клиента Telegram
            System.setProperty("org.telegram.telegrambots.updatesreceivers.DefaultBotSession.TIMEOUT", "75");
            System.setProperty("org.telegram.telegrambots.updatesreceivers.DefaultBotSession.CONNECTION_TIMEOUT", "30");

            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Bot registered successfully: {}", botName);

            // Маскировка токена в логах
            if (botToken.length() >= 10) {
                log.info("Bot token (masked): {}...", botToken.substring(0, 10));
            }

            // Фоновая загрузка эмбеддингов при старте
            new Thread(() -> {
                log.info("Фоновая загрузка эмбеддингов начата.");
                List<NewsPost> freshNews = parser.getAllNews();
                newsStorageService.saveNews(freshNews);
                log.info("Фоновая загрузка эмбеддингов завершена.");
            }, "embedding-loader").start();

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

    // ======================== Обработчик сообщений ========================

    @Override
    public void onUpdateReceived(Update update) {
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

        if (messageText.length() > 500) {
            messageText = messageText.substring(0, 500);
            sendTextMessage(chatId, "⚠️ Сообщение обрезано до 500 символов.");
        }

        log.info("User request: userName={}, query={}",
                update.getMessage().getFrom().getUserName(), messageText);

        // Сохранение обратной связи
        if (pendingComment.containsKey(chatId)) {
            String feedbackType = pendingComment.remove(chatId);
            User user = pendingCommentUser.remove(chatId);
            saveFeedback(feedbackType, chatId, user, messageText);
            sendTextMessage(chatId, "Спасибо за ваш комментарий!");
            return;
        }

        if (messageText.equals("/start")) {
            sendStartMessage(chatId);
            return;
        }

        // Основной поиск — гибридный
        performHybridSearch(chatId, messageText);
    }

    // ======================== Гибридный поиск ========================

    private void performHybridSearch(String chatId, String query) {
        sendTextMessage(chatId, "⏳ Ищу новости...");

        // 1. Лексический поиск (стемминг): полная очистка + расшифровка
        String cleanedForStem = cloudAiService.cleanAndExpandQuery(query);
        List<NewsPost> stemResults = parser.searchByStemsRanked(cleanedForStem);

        // 2. Семантический поиск: только расшифровка аббревиатур с сохранением исходных
        String expandedForVector = cloudAiService.expandAbbreviationsKeepOriginal(query);
        int vectorLimit = Math.max(20, stemResults.size());
        List<NewsPost> semanticResults = newsStorageService.semanticSearch(expandedForVector, vectorLimit);

        // 3. Слияние через Reciprocal Rank Fusion
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, NewsPost> newsByLink = new LinkedHashMap<>();

        for (int i = 0; i < stemResults.size(); i++) {
            String link = stemResults.get(i).getLink();
            double rrf = 1.0 / (60 + i + 1);
            rrfScores.merge(link, rrf, Double::sum);
            newsByLink.putIfAbsent(link, stemResults.get(i));
        }

        for (int i = 0; i < semanticResults.size(); i++) {
            String link = semanticResults.get(i).getLink();
            double rrf = 1.0 / (60 + i + 1);
            rrfScores.merge(link, rrf, Double::sum);
            newsByLink.putIfAbsent(link, semanticResults.get(i));
        }

        // Сортируем по убыванию RRF-скора и возвращаем все результаты
        List<NewsPost> finalResults = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> newsByLink.get(e.getKey()))
                .collect(Collectors.toList());

        if (finalResults.isEmpty()) {
            sendTextMessage(chatId, "По запросу \"" + query + "\" ничего не найдено.");
            return;
        }

        // Кэширование и вывод первой страницы (без лимита)
        lastNewsList.put(chatId, finalResults);
        lastShownOffset.remove(chatId);
        sendNewsPage(chatId, null, finalResults, 0);
    }

    // ======================== ОБРАБОТКА НАЖАТИЙ КНОПОК ========================

    private void handleCallbackQuery(Update update) {
        String data = update.getCallbackQuery().getData();
        String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();
        User user = update.getCallbackQuery().getFrom();
        Long userId = user.getId();

        if (!allowedUsers.contains(userId)) {
            log.warn("Unauthorized callback attempt: user={} (ID={}), data={}",
                    user.getUserName(), userId, data);
            editMessageTextAndRemoveKeyboard(chatId, messageId, "⛔ Доступ запрещён.");
            return;
        }

        String[] parts = data.split(":", 2);
        String action = parts[0];
        String payload = parts.length > 1 ? parts[1] : "";

        switch (action) {
            case CALLBACK_PREV:
            case CALLBACK_NEXT: {
                int offset = 0;
                try { offset = Integer.parseInt(payload); } catch (NumberFormatException ignored) {}
                List<NewsPost> allNews = lastNewsList.get(chatId);
                if (allNews != null) {
                    sendNewsPage(chatId, messageId, allNews, offset);
                } else {
                    editMessageTextAndRemoveKeyboard(chatId, messageId, "Результаты устарели. Выполните новый поиск.");
                }
                break;
            }

            case CALLBACK_LIKE:
                askForComment(chatId, "like");
                break;

            case CALLBACK_DISLIKE:
                askForComment(chatId, "dislike");
                break;

            case CALLBACK_COMMENT_YES:
                pendingComment.put(chatId, payload);
                pendingCommentUser.put(chatId, user);
                editMessageTextAndRemoveKeyboard(chatId, messageId, "Ожидаю ваш комментарий...");
                break;

            case CALLBACK_COMMENT_NO:
                editMessageTextAndRemoveKeyboard(chatId, messageId, "Спасибо за обратную связь!");
                break;
        }
    }

    // ======================== Пагинация ========================

    private void sendNewsPage(String chatId, Integer messageId, List<NewsPost> allNews, int offset) {
        int pageSize = 5;
        int total = allNews.size();
        int start = offset;
        int end = Math.min(start + pageSize, total);
        List<NewsPost> page = allNews.subList(start, end);

        StringBuilder response = new StringBuilder("🔹 Найдено " + total + " новостей");
        if (start > 0 || end < total) {
            response.append(" (показаны ").append(start + 1).append("–").append(end).append(")");
        }
        response.append(":\n\n");

        for (int i = 0; i < page.size(); i++) {
            NewsPost post = page.get(i);
            String entry = (start + i + 1) + ". " + post.getTitle() + "\n" + post.getLink() + "\n\n";
            if (response.length() + entry.length() > 4000) {
                response.append("... (обрезано)");
                break;
            }
            response.append(entry);
        }

        boolean hasPrev = start > 0;
        boolean hasNext = end < total;
        InlineKeyboardMarkup keyboard = createResultKeyboard(hasPrev, hasNext, start);

        if (messageId == null) {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText(response.toString());
            msg.setReplyMarkup(keyboard);
            msg.enableHtml(true);
            executeMessage(msg);
        } else {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setText(response.toString());
            edit.setReplyMarkup(keyboard);
            try { execute(edit); } catch (TelegramApiException e) { log.error("Error editing message", e); }
        }
    }

    // ======================== Клавиатуры ========================

    private InlineKeyboardMarkup createResultKeyboard(boolean hasPrev, boolean hasNext, int currentOffset) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (hasPrev) {
            InlineKeyboardButton prevBtn = new InlineKeyboardButton();
            prevBtn.setText("◀ Назад");
            prevBtn.setCallbackData(CALLBACK_PREV + ":" + Math.max(0, currentOffset - 5));
            navRow.add(prevBtn);
        }
        if (hasNext) {
            InlineKeyboardButton nextBtn = new InlineKeyboardButton();
            nextBtn.setText("Вперёд ▶");
            nextBtn.setCallbackData(CALLBACK_NEXT + ":" + (currentOffset + 5));
            navRow.add(nextBtn);
        }
        if (!navRow.isEmpty()) keyboard.add(navRow);

        List<InlineKeyboardButton> feedbackRow = new ArrayList<>();
        InlineKeyboardButton likeBtn = new InlineKeyboardButton();
        likeBtn.setText("❤️ Понравилось");
        likeBtn.setCallbackData(CALLBACK_LIKE);
        feedbackRow.add(likeBtn);
        InlineKeyboardButton dislikeBtn = new InlineKeyboardButton();
        dislikeBtn.setText("👎 Не подходит");
        dislikeBtn.setCallbackData(CALLBACK_DISLIKE);
        feedbackRow.add(dislikeBtn);
        keyboard.add(feedbackRow);

        markup.setKeyboard(keyboard);
        return markup;
    }

    // ======================== Обратная связь ========================

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

    private void saveFeedback(String type, String chatId, User user, String comment) {
        String folder = type.equals("like") ? "logs/likes" : "logs/dislikes";
        String today = LocalDate.now(MOSCOW_ZONE).format(DATE_FORMAT);
        String fileName = folder + "/" + today + ".log";
        String timestamp = LocalDateTime.now(MOSCOW_ZONE).format(TIMESTAMP_FORMAT);

        String firstName = user.getFirstName() != null ? user.getFirstName() : "";
        String lastName = user.getLastName() != null ? user.getLastName() : "";
        String userName = user.getUserName() != null ? "@" + user.getUserName() : "";
        String userInfo = (firstName + " " + lastName).trim();
        if (!userName.isEmpty()) userInfo += " (" + userName + ")";

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

    // ======================== Вспомогательные методы ========================

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

    private void editMessageTextAndRemoveKeyboard(String chatId, Integer messageId, String text) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId);
        edit.setMessageId(messageId);
        edit.setText(text);
        edit.setReplyMarkup(null);
        try { execute(edit); } catch (TelegramApiException e) { log.error("Error editing message", e); }
    }

    private void executeMessage(SendMessage msg) {
        try { execute(msg); } catch (TelegramApiException e) { log.error("Error sending message", e); }
    }
}