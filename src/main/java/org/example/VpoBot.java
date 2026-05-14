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
import java.util.List;

@Slf4j
@Component
public class VpoBot extends TelegramLongPollingBot {

    private final String botName;
    private final String botToken;
    private final Parser parser;
    private final List<Long> allowedUsers;

    public VpoBot(@Value("${bot.name}") String botName,
                  @Value("${bot.token}") String botToken,
                  @Value("${bot.allowed-users}") List<Long> allowedUsers,
                  Parser parser) {
        this.botName = botName;
        this.botToken = botToken;
        this.allowedUsers = allowedUsers;
        this.parser = parser;
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
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Long userId = update.getMessage().getFrom().getId();
        String chatId = update.getMessage().getChatId().toString();
        String messageText = update.getMessage().getText();


        // Проверка белого списка
        if (!allowedUsers.contains(userId)) {
            log.warn("Unauthorized access attempt: user={} (ID={}), message={}",
                    update.getMessage().getFrom().getUserName(),
                    userId,
                    messageText);
            sendTextMessage(chatId, "Извините, у вас нет доступа к этому боту.");
            return;
        }

        // Сообщения от белого списка
        log.info("User request: userName={} (ID={}), query={}",
                update.getMessage().getFrom().getUserName(),
                userId,
                messageText);

        // Обработка команд
        if (messageText.equals("/start")) {
            sendTextMessage(chatId, "Привет! Я бот для поиска новостей по ключевым словам. Введи запрос.");
            return;
        }

        // Поиск новостей
        List<NewsPost> news = parser.parse(messageText);
        if (news.isEmpty()) {
            sendTextMessage(chatId, "По вашему запросу \"" + messageText + "\" ничего не найдено.");
        } else {
            StringBuilder response = new StringBuilder("🔹 Найдено " + news.size() + " новостей:\n\n");
            for (int i = 0; i < Math.min(5, news.size()); i++) {
                NewsPost post = news.get(i);
                response.append(i + 1).append(". ").append(post.getTitle())
                        .append("\n").append(post.getLink()).append("\n\n");
            }
            sendTextMessage(chatId, response.toString());
        }
    }

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