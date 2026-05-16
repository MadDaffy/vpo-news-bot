package org.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import jakarta.annotation.PostConstruct;
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

    private final Map<String, String> lastQuery = new ConcurrentHashMap<>();
    private final Map<String, List<NewsPost>> exactSearchResult = new ConcurrentHashMap<>();

    private static final String CALLBACK_EXPAND = "expand";
    private static final String CALLBACK_MORE = "more";
    private static final String CALLBACK_LIKE = "like";
    private static final String CALLBACK_DISLIKE = "dislike";

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
        } catch (TelegramApiException e) {
            log.error("Error registering bot", e);
        }
    }

    @Override
    public String getBotUsername() { return botName; }
    @Override
    public String getBotToken() { return botToken; }

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

        log.info("User request: userName={} (ID={}), query={}",
                update.getMessage().getFrom().getUserName(), userId, messageText);

        lastQuery.put(chatId, messageText);

        if (messageText.equals("/start")) {
            sendStartMessage(chatId);
            return;
        }

        // ТОЧНЫЙ ПОИСК ПО УМОЛЧАНИЮ
        performExactSearch(chatId, messageText);
    }

    private void handleCallbackQuery(Update update) {
        String data = update.getCallbackQuery().getData();
        String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();

        switch (data) {
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
                editMessageText(chatId, messageId, "👍 Спасибо за обратную связь!");
                break;

            case CALLBACK_DISLIKE:
                editMessageText(chatId, messageId, "👎 Мы учтём вашу оценку.");
                break;
        }
    }

    /** Точный поиск: ищет полную фразу. */
    private void performExactSearch(String chatId, String query) {
        sendTextMessage(chatId, "⏳ Ищу новости...");

        List<NewsPost> news = parser.searchExactPhrase(query);

        if (news.isEmpty()) {
            // Сообщение с кнопкой "Расширить с ИИ"
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

    /** AI-расширенный поиск: генерирует словоформы и ищет по ним. */
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