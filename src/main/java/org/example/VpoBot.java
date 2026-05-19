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
    private final Map<String, String> pendingComment = new ConcurrentHashMap<>();
    private final Map<String, User> pendingCommentUser = new ConcurrentHashMap<>();
    private final Map<String, List<NewsPost>> lastNewsList = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastShownOffset = new ConcurrentHashMap<>();

    private static final String CALLBACK_EXPAND = "expand";
    private static final String CALLBACK_PREV = "prev";
    private static final String CALLBACK_NEXT = "next";
    private static final String CALLBACK_LIKE = "like";
    private static final String CALLBACK_DISLIKE = "dislike";
    private static final String CALLBACK_COMMENT_YES = "comment_yes";
    private static final String CALLBACK_COMMENT_NO = "comment_no";

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

            // Проверка доступности Ollama
            if (!aiService.isAvailable()) {
                log.warn("Ollama is not available or model is not loaded. AI features will be disabled.");
            } else {
                log.info("Ollama is healthy.");
            }

            // Маскировка токена в логах
            if (botToken.length() >= 10) {
                log.info("Bot token (masked): {}...", botToken.substring(0, 10));
            } else {
                log.warn("Bot token is shorter than 10 characters, security risk!");
            }

            Files.createDirectories(Path.of("logs/likes"));
            Files.createDirectories(Path.of("logs/dislikes"));
        } catch (TelegramApiException | IOException e) {
            log.error("Error registering bot or creating log directories", e);
        }
    }

    @Override public String getBotUsername() { return botName; }
    @Override public String getBotToken() { return botToken; }

    // ======================== ОСНОВНОЙ ОБРАБОТЧИК =========================
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
            log.warn("Unauthorized access attempt: user={} (ID={}), message={}",
                    update.getMessage().getFrom().getUserName(), userId, messageText);
            sendTextMessage(chatId, "⛔ Доступ запрещён.");
            return;
        }

        // Ограничение длины запроса
        if (messageText.length() > 500) {
            messageText = messageText.substring(0, 500);
            sendTextMessage(chatId, "⚠️ Сообщение было обрезано до 500 символов.");
            log.info("Truncated message from user={} to 500 chars", userId);
        }

        log.info("User request: userName={} (ID={}), query={}",
                update.getMessage().getFrom().getUserName(), userId, messageText);

        // Если ожидается комментарий
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

        performExactSearch(chatId, messageText);
    }

    // ======================== ОБРАБОТКА КНОПОК =========================
    private void handleCallbackQuery(Update update) {
        String data = update.getCallbackQuery().getData();
        String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();
        User user = update.getCallbackQuery().getFrom();
        Long userId = user.getId();

        // Проверка белого списка для callback
        if (!allowedUsers.contains(userId)) {
            log.warn("Unauthorized callback attempt: user={} (ID={}), data={}",
                    user.getUserName(), userId, data);
            // Попытка ответить через editMessage или sendText
            editMessageTextAndRemoveKeyboard(chatId, messageId, "⛔ Доступ запрещён.");
            return;
        }

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

    // ======================== ПОИСК =========================
// Основной поиск – теперь по основам слов (стемминг)
    private void performExactSearch(String chatId, String query) {
        sendTextMessage(chatId, "⏳ Ищу новости...");

        List<NewsPost> news = parser.searchByStemsRanked(query);

        if (news.isEmpty()) {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText("По запросу \"" + query + "\" ничего не найдено.");
            msg.setReplyMarkup(createSearchAgainKeyboard());
            executeMessage(msg);
            return;
        }

        lastNewsList.put(chatId, new ArrayList<>(news));
        lastShownOffset.remove(chatId);

        sendNewsPage(chatId, null, news, 0);   // <-- правильный вызов
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

        allNews = rankByWordMatch(allNews, combinedKeywords);

        List<NewsPost> exactResult = exactSearchResult.get(chatId);
        if (exactResult != null && newsListsAreEqual(exactResult, allNews)) {
            sendTextMessage(chatId, "😔 ИИ-помощник не нашел дополнительной информации");
            return;
        }

        processSearchResult(chatId, query, allNews, true);
    }

    private void processSearchResult(String chatId, String query, List<NewsPost> news, boolean isExpanded) {
        if (news.isEmpty()) {
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText("По запросу \"" + query + "\" ничего не найдено.");
            msg.setReplyMarkup(createSearchAgainKeyboard());
            executeMessage(msg);
            return;
        }

        lastNewsList.put(chatId, new ArrayList<>(news));
        lastShownOffset.remove(chatId);

        if (!isExpanded) {
            exactSearchResult.put(chatId, new ArrayList<>(news));
        }

        sendNewsPage(chatId, null, news, 0);
    }

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

    // ======================== РАНЖИРОВАНИЕ =========================
    private List<NewsPost> rankByWordMatch(List<NewsPost> news, String keywords) {
        String[] queryWords = keywords.toLowerCase().split("[,\\s]+");
        List<NewsPost> sorted = new ArrayList<>(news);
        sorted.sort((a, b) -> Integer.compare(countMatches(b, queryWords), countMatches(a, queryWords)));
        return sorted;
    }

    private int countMatches(NewsPost post, String[] queryWords) {
        String title = post.getTitle() != null ? post.getTitle().toLowerCase() : "";
        String desc = post.getDescription() != null ? post.getDescription().toLowerCase() : "";
        String combined = title + " " + desc;
        int matches = 0;
        for (String w : queryWords) if (!w.isEmpty() && combined.contains(w)) matches++;
        return matches;
    }

    private boolean newsListsAreEqual(List<NewsPost> l1, List<NewsPost> l2) {
        if (l1.size() != l2.size()) return false;
        Set<String> t1 = l1.stream().map(n -> n.getTitle() == null ? "" : n.getTitle()).collect(Collectors.toSet());
        Set<String> t2 = l2.stream().map(n -> n.getTitle() == null ? "" : n.getTitle()).collect(Collectors.toSet());
        return t1.equals(t2);
    }

    // ======================== ОБРАТНАЯ СВЯЗЬ =========================
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

    // ======================== КЛАВИАТУРЫ =========================
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

        List<InlineKeyboardButton> expandRow = new ArrayList<>();
        InlineKeyboardButton expandBtn = new InlineKeyboardButton();
        expandBtn.setText("🔍 Расширить результаты с ИИ");
        expandBtn.setCallbackData(CALLBACK_EXPAND);
        expandRow.add(expandBtn);
        keyboard.add(expandRow);

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