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
 * Telegram-бот для поиска новостей.
 * <p>
 * Использует морфологический анализ (стемминг) в {@link Parser}
 * и облачное API {@link CloudAiService} для очистки запроса
 * и расшифровки аббревиатур.
 * <p>
 * Поддерживает пагинацию результатов (по 5 новостей на странице)
 * и сбор обратной связи (лайки/дизлайки с комментариями).
 */
@Slf4j
@Component
public class VpoBot extends TelegramLongPollingBot {

    // ======================== Конфигурация и зависимости ========================

    /** Имя бота, полученное из конфигурации */
    private final String botName;
    /** Токен, выданный @BotFather */
    private final String botToken;
    /** Список ID пользователей, которым разрешён доступ */
    private final List<Long> allowedUsers;
    /** Парсер RSS-лент с русским морфологическим анализатором */
    private final Parser parser;
    /** Облачный сервис для очистки запроса */
    private final CloudAiService cloudAiService;
    private final NewsStorageService newsStorageService;

    // ======================== Хранилище состояний ========================

    /** Последний запрос пользователя (chatId → query) */
    private final Map<String, String> lastQuery = new ConcurrentHashMap<>();
    /** Ожидание комментария: chatId → "like" или "dislike" */
    private final Map<String, String> pendingComment = new ConcurrentHashMap<>();
    /** Пользователь, ожидающий комментарий: chatId → User */
    private final Map<String, User> pendingCommentUser = new ConcurrentHashMap<>();
    /** Кэш последних результатов поиска для пагинации (chatId → список) */
    private final Map<String, List<NewsPost>> lastNewsList = new ConcurrentHashMap<>();
    /** Текущий отступ для пагинации (chatId → offset) */
    private final Map<String, Integer> lastShownOffset = new ConcurrentHashMap<>();

    // ======================== Константы callback-данных ========================

    private static final String CALLBACK_PREV = "prev";            // кнопка "◀ Назад"
    private static final String CALLBACK_NEXT = "next";            // кнопка "Вперёд ▶"
    private static final String CALLBACK_LIKE = "like";            // кнопка "❤️ Понравилось"
    private static final String CALLBACK_DISLIKE = "dislike";      // кнопка "👎 Не подходит"
    private static final String CALLBACK_COMMENT_YES = "comment_yes";  // "Да, конечно"
    private static final String CALLBACK_COMMENT_NO = "comment_no";    // "Нет"

    // ======================== Временные константы ========================

    /** Московский часовой пояс для логирования обратной связи */
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    /** Формат даты для имени файла лога */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** Формат времени для записи в лог */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ======================== Конструктор ========================

    /**
     * Конструктор, вызываемый Spring Boot.
     * Все зависимости внедряются автоматически.
     */
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

    /**
     * Инициализация бота при старте приложения.
     * Регистрирует бота в Telegram API, проверяет доступность облачного API,
     * маскирует токен в логах и создаёт директории для обратной связи.
     */
    @PostConstruct
    public void init() {
        try {
            // Увеличиваем таймауты HTTP-клиента Telegram для предотвращения
            // периодических SocketTimeoutException при получении обновлений
            System.setProperty(
                    "org.telegram.telegrambots.updatesreceivers.DefaultBotSession.TIMEOUT", "75");
            System.setProperty(
                    "org.telegram.telegrambots.updatesreceivers.DefaultBotSession.CONNECTION_TIMEOUT", "30");

            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Bot registered successfully: {}", botName);

            // Проверка доступности облачного API (не критично – бот работает и без него)
            if (!cloudAiService.isAvailable()) {
                log.warn("Cloud AI is not available. Abbreviation expansion will be disabled.");
            } else {
                log.info("Cloud AI is healthy.");
            }

            // Маскировка токена в логах (первые 10 символов)
            if (botToken.length() >= 10) {
                log.info("Bot token (masked): {}...", botToken.substring(0, 10));
            } else {
                log.warn("Bot token is shorter than 10 characters, security risk!");
            }

            // Запускаем фоновую загрузку эмбеддингов, чтобы не блокировать старт бота
            new Thread(() -> {
                log.info("Фоновая загрузка эмбеддингов начата.");
                List<NewsPost> freshNews = parser.getAllNews();
                newsStorageService.saveNews(freshNews);
                log.info("Фоновая загрузка эмбеддингов завершена.");
            }, "embedding-loader").start();


            // Создаём папки для логов обратной связи
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

    // ======================== ОСНОВНОЙ ОБРАБОТЧИК СООБЩЕНИЙ ========================

    /**
     * Главный обработчик входящих сообщений и callback-запросов.
     * <p>
     * <b>Логика:</b>
     * <ol>
     *   <li>Если пришёл callback (нажатие кнопки) → делегируется в {@link #handleCallbackQuery(Update)}.</li>
     *   <li>Проверяется белый список пользователей.</li>
     *   <li>Длинные сообщения обрезаются до 500 символов.</li>
     *   <li>Если ожидается комментарий — сохраняется обратная связь.</li>
     *   <li>Команда {@code /start} выводит приветствие.</li>
     *   <li>Все остальные сообщения считаются поисковым запросом и передаются в
     *       {@link #performSearch(String, String)}.</li>
     * </ol>
     */
    @Override
    public void onUpdateReceived(Update update) {
        // Нажатия inline-кнопок
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update);
            return;
        }

        // Только текстовые сообщения
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        Long userId = update.getMessage().getFrom().getId();
        String chatId = update.getMessage().getChatId().toString();
        String messageText = update.getMessage().getText();

        // Проверка белого списка
        if (!allowedUsers.contains(userId)) {
            log.warn("Unauthorized access attempt: user={} (ID={}), message={}",
                    update.getMessage().getFrom().getUserName(), userId, messageText);
            sendTextMessage(chatId, "⛔ Доступ запрещён.");
            return;
        }

        // Ограничение длины запроса (защита от чрезмерно длинных сообщений)
        if (messageText.length() > 500) {
            messageText = messageText.substring(0, 500);
            sendTextMessage(chatId, "⚠️ Сообщение было обрезано до 500 символов.");
            log.info("Truncated message from user={} to 500 chars", userId);
        }

        log.info("User request: userName={} (ID={}), query={}",
                update.getMessage().getFrom().getUserName(), userId, messageText);

        // Если ожидается комментарий от этого пользователя — сохраняем его
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

        if (messageText.startsWith("/semantic")) {
            String query = messageText.substring(10).trim();
            if (query.isEmpty()) {
                sendTextMessage(chatId, "Пожалуйста, напишите запрос после /semantic. Например: /semantic Что происходит в Ормузском проливе");
                return;
            }
            sendTextMessage(chatId, "⏳ Ищу по смыслу...");
            // Теперь запрос сначала расшифровывается, а потом передаётся в поиск
            String expandedQuery = cloudAiService.expandAbbreviationsKeepOriginal(query);
            log.info("Semantic query: {}", expandedQuery);
            List<NewsPost> results = newsStorageService.semanticSearch(expandedQuery, 5);
            if (results.isEmpty()) {
                sendTextMessage(chatId, "Ничего не найдено.");
            } else {
                StringBuilder sb = new StringBuilder("🔹 Результаты семантического поиска:\n\n");
                for (int i = 0; i < results.size(); i++) {
                    NewsPost post = results.get(i);
                    sb.append(i + 1).append(". ").append(post.getTitle()).append("\n").append(post.getLink()).append("\n\n");
                }
                sendTextMessage(chatId, sb.toString());
            }
            return;
        }

        if (messageText.startsWith("/hybrid")) {
            String query = messageText.substring(8).trim(); // убираем "/hybrid "
            if (query.isEmpty()) {
                sendTextMessage(chatId, "Пожалуйста, напишите запрос после /hybrid. Например: /hybrid Что происходит в Ормузском проливе");
                return;
            }
            sendTextMessage(chatId, "⏳ Ищу гибридно...");
            List<NewsPost> results = hybridSearch(query, 10);
            if (results.isEmpty()) {
                sendTextMessage(chatId, "Ничего не найдено.");
            } else {
                StringBuilder sb = new StringBuilder("🔹 Результаты гибридного поиска:\n\n");
                for (int i = 0; i < results.size(); i++) {
                    NewsPost post = results.get(i);
                    sb.append(i + 1).append(". ").append(post.getTitle()).append("\n").append(post.getLink()).append("\n\n");
                }
                sendTextMessage(chatId, sb.toString());
            }
            return;
        }
        // Основной поиск с облачной очисткой запроса
        performSearch(chatId, messageText);
    }

    // ======================== ОБРАБОТКА НАЖАТИЙ КНОПОК ========================

    /**
     * Обрабатывает нажатия на inline-кнопки под сообщениями бота.
     * <p>
     * <b>Поддерживаемые действия:</b>
     * <ul>
     *   <li>{@code CALLBACK_PREV} / {@code CALLBACK_NEXT} — пагинация результатов.</li>
     *   <li>{@code CALLBACK_LIKE} / {@code CALLBACK_DISLIKE} — запрос комментария.</li>
     *   <li>{@code CALLBACK_COMMENT_YES} / {@code CALLBACK_COMMENT_NO} — ответ на запрос комментария.</li>
     * </ul>
     */
    private void handleCallbackQuery(Update update) {
        String data = update.getCallbackQuery().getData();
        String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();
        User user = update.getCallbackQuery().getFrom();
        Long userId = user.getId();

        // Проверка белого списка для callback (защита от перебора кнопок)
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
                // Пагинация: загружаем следующую/предыдущую страницу
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
                // Пользователь хочет оставить комментарий — запоминаем контекст
                pendingComment.put(chatId, payload);
                pendingCommentUser.put(chatId, user);
                editMessageTextAndRemoveKeyboard(chatId, messageId, "Ожидаю ваш комментарий...");
                break;

            case CALLBACK_COMMENT_NO:
                editMessageTextAndRemoveKeyboard(chatId, messageId, "Спасибо за обратную связь!");
                break;
        }
    }

    // ======================== ПОИСК ========================

    /**
     * Выполняет поиск новостей с предварительной облачной очисткой запроса
     * и последующим морфологическим анализом (стеммингом).
     * <p>
     * <b>Алгоритм:</b>
     * <ol>
     *   <li>Запрос передаётся в {@link CloudAiService#cleanAndExpandQuery(String)}
     *       для удаления стоп-слов и расшифровки аббревиатур.</li>
     *   <li>Очищенный запрос передаётся в {@link Parser#searchByStemsRanked(String)}.</li>
     *   <li>Результаты кэшируются для пагинации и выводятся первой страницей.</li>
     * </ol>
     *
     * @param chatId ID чата Telegram
     * @param query  исходный запрос пользователя
     */
    private void performSearch(String chatId, String query) {
        sendTextMessage(chatId, "⏳ Ищу новости...");

        // Очищаем запрос и расшифровываем аббревиатуры через облачное API
        String cleanedQuery = cloudAiService.cleanAndExpandQuery(query);

        // Поиск по основам слов с ранжированием по количеству совпадений
        List<NewsPost> news = parser.searchByStemsRanked(cleanedQuery);

        if (news.isEmpty()) {
            sendTextMessage(chatId, "По запросу \"" + query + "\" ничего не найдено.");
            return;
        }

        // Кэширование и вывод первой страницы
        lastNewsList.put(chatId, new ArrayList<>(news));
        lastShownOffset.remove(chatId);

        sendNewsPage(chatId, null, news, 0);
    }

    /**
     * Гибридный поиск: стемминг + векторный поиск с ранжированием через RRF.
     * @param query исходный запрос пользователя
     * @param limit количество возвращаемых результатов
     * @return список новостей, отсортированный по релевантности
     */
    private List<NewsPost> hybridSearch(String query, int limit) {
        // 1. Лексический поиск (стемминг): полная очистка + расшифровка
        String cleanedForStem = cloudAiService.cleanAndExpandQuery(query);
        List<NewsPost> stemResults = parser.searchByStemsRanked(cleanedForStem);

        // 2. Векторный поиск: только расшифровка аббревиатур с сохранением исходных
        String expandedForVector = cloudAiService.expandAbbreviationsKeepOriginal(query);
        List<NewsPost> semanticResults = newsStorageService.semanticSearch(expandedForVector,
                Math.max(limit, stemResults.size()));

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

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> newsByLink.get(e.getKey()))
                .collect(Collectors.toList());
    }

    // ======================== ПАГИНАЦИЯ ========================

    /**
     * Отправляет или редактирует сообщение с очередной страницей новостей.
     * <p>
     * На одной странице отображается до 5 новостей. Если общее количество
     * новостей больше, под сообщением выводятся кнопки навигации «◀ Назад» и «Вперёд ▶».
     *
     * @param chatId    ID чата Telegram
     * @param messageId ID редактируемого сообщения (null для нового сообщения)
     * @param allNews   полный список новостей
     * @param offset    текущий отступ (сколько новостей уже показано)
     */
    private void sendNewsPage(String chatId, Integer messageId, List<NewsPost> allNews, int offset) {
        int pageSize = 5;
        int total = allNews.size();
        int start = offset;
        int end = Math.min(start + pageSize, total);
        List<NewsPost> page = allNews.subList(start, end);

        // Формируем текст сообщения
        StringBuilder response = new StringBuilder("🔹 Найдено " + total + " новостей");
        if (start > 0 || end < total) {
            response.append(" (показаны ").append(start + 1).append("–").append(end).append(")");
        }
        response.append(":\n\n");

        for (int i = 0; i < page.size(); i++) {
            NewsPost post = page.get(i);
            String entry = (start + i + 1) + ". " + post.getTitle() + "\n" + post.getLink() + "\n\n";
            // Контроль длины сообщения (Telegram API ограничивает 4096 символами)
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
            // Новое сообщение
            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setText(response.toString());
            msg.setReplyMarkup(keyboard);
            msg.enableHtml(true);
            executeMessage(msg);
        } else {
            // Редактирование существующего сообщения (для пагинации)
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setText(response.toString());
            edit.setReplyMarkup(keyboard);
            try { execute(edit); } catch (TelegramApiException e) { log.error("Error editing message", e); }
        }
    }

    // ======================== КЛАВИАТУРЫ ========================

    /**
     * Создаёт клавиатуру для сообщения с результатами поиска.
     * <p>
     * <b>Содержит два ряда кнопок:</b>
     * <ol>
     *   <li>«◀ Назад» и «Вперёд ▶» — навигация по страницам (отображаются при необходимости).</li>
     *   <li>«❤️ Понравилось» и «👎 Не подходит» — обратная связь.</li>
     * </ol>
     *
     * @param hasPrev       нужна ли кнопка «Назад»
     * @param hasNext       нужна ли кнопка «Вперёд»
     * @param currentOffset текущий отступ для расчёта следующей/предыдущей страницы
     * @return готовая клавиатура
     */
    private InlineKeyboardMarkup createResultKeyboard(boolean hasPrev, boolean hasNext, int currentOffset) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        // Ряд 1: Навигация
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

        // Ряд 2: Обратная связь
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

    // ======================== ОБРАТНАЯ СВЯЗЬ ========================

    /**
     * Отправляет сообщение с вопросом о желании оставить комментарий.
     * Содержит кнопки «Да, конечно» и «Нет».
     *
     * @param chatId       ID чата Telegram
     * @param feedbackType тип обратной связи ("like" или "dislike")
     */
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

    /**
     * Сохраняет комментарий пользователя в файл логов.
     * <p>
     * Файлы сохраняются в папки {@code logs/likes/} или {@code logs/dislikes/}
     * с именем, соответствующим текущей дате (например, {@code 2026-05-29.log}).
     * В каждой строке записывается временная метка, информация о пользователе и текст комментария.
     *
     * @param type    тип обратной связи ("like" или "dislike")
     * @param chatId  ID чата Telegram
     * @param user    объект пользователя Telegram
     * @param comment текст комментария
     */
    private void saveFeedback(String type, String chatId, User user, String comment) {
        String folder = type.equals("like") ? "logs/likes" : "logs/dislikes";
        String today = LocalDate.now(MOSCOW_ZONE).format(DATE_FORMAT);
        String fileName = folder + "/" + today + ".log";
        String timestamp = LocalDateTime.now(MOSCOW_ZONE).format(TIMESTAMP_FORMAT);

        // Собираем информацию о пользователе: имя, фамилия, никнейм
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

    // ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ========================

    /** Отправляет приветственное сообщение. */
    private void sendStartMessage(String chatId) {
        sendTextMessage(chatId,
                "Привет! Я бот для поиска новостей.\n\n" +
                        "🔹 Просто отправь слово или фразу — я найду подходящие новости.\n" +
                        "🔹 Я понимаю склонения, синонимы и перефразы.");
    }

    /** Отправляет текстовое сообщение в чат. */
    private void sendTextMessage(String chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        executeMessage(msg);
    }

    /**
     * Редактирует сообщение и убирает клавиатуру.
     * Используется для завершения диалога обратной связи.
     */
    private void editMessageTextAndRemoveKeyboard(String chatId, Integer messageId, String text) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId);
        edit.setMessageId(messageId);
        edit.setText(text);
        edit.setReplyMarkup(null);  // убираем клавиатуру
        try { execute(edit); } catch (TelegramApiException e) { log.error("Error editing message", e); }
    }

    /** Выполняет отправку сообщения с обработкой ошибок. */
    private void executeMessage(SendMessage msg) {
        try { execute(msg); } catch (TelegramApiException e) { log.error("Error sending message", e); }
    }
}