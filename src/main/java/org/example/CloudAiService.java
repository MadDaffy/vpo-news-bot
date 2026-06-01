package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Сервис для очистки поискового запроса и расшифровки аббревиатур
 * с помощью облачного OpenAI-совместимого API (GPTunnel, OpenRouter и т.д.).
 * <p>
 * Для переключения между провайдерами достаточно изменить три настройки
 * в {@code application.properties}: {@code cloud.api.key}, {@code cloud.api.url}
 * и {@code cloud.api.model}.
 */
@Slf4j
@Service
public class CloudAiService {

    /** API-ключ облачного провайдера */
    private final String apiKey;
    /** URL эндпоинта для Chat Completions */
    private final String apiUrl;
    /** Идентификатор используемой модели (например, {@code deepseek-chat}) */
    private final String model;
    /** Маппер для работы с JSON */
    private final ObjectMapper objectMapper;
    /** HTTP-клиент с настройками таймаутов */
    private final CloseableHttpClient httpClient;

    /**
     * Конструктор, автоматически внедряющий значения из конфигурации.
     *
     * @param apiKey  API-ключ облачного провайдера
     * @param apiUrl  URL эндпоинта Chat Completions
     * @param model   идентификатор модели
     */
    public CloudAiService(@Value("${cloud.api.key}") String apiKey,
                          @Value("${cloud.api.url}") String apiUrl,
                          @Value("${cloud.api.model}") String model) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(10_000)   // таймаут на установку соединения
                        .setSocketTimeout(30_000)    // таймаут на ожидание ответа
                        .build())
                .build();
    }

    /**
     * Очищает пользовательский запрос от мусорных слов и одновременно
     * расшифровывает все известные аббревиатуры через облачную LLM.
     * <p>
     * Промпт содержит полный перечень аббревиатур, требование удалить
     * все стоп-слова и временные метки, а также пример ожидаемого ответа.
     * <p>
     * Если API недоступно или возвращает пустой ответ, метод возвращает
     * исходный запрос без изменений — это обеспечивает отказоустойчивость.
     *
     * @param userQuery исходный запрос пользователя
     * @return очищенный и расширенный запрос (знаменательные слова + расшифровки)
     */
    public String cleanAndExpandQuery(String userQuery) {
        // Промпт, жёстко регламентирующий формат ответа
        String prompt = String.format(
                "Ты — инструмент очистки поискового запроса. Отвечай ТОЛЬКО на русском языке. " +
                        "Удали ВСЕ предлоги, союзы, частицы, междометия, местоимения, вводные, вопросительные, модальные слова и слова-паразиты. " +
                        "Также удали временные метки: сегодня, вчера, завтра, сейчас, теперь, потом, раньше.\n" +
                        "Расшифруй ВСЕ аббревиатуры строго по образцу:\n" +
                        "  СВО → специальная военная операция\n" +
                        "  БпЛА → беспилотный летательный аппарат\n" +
                        "  БПЛА → беспилотный летательный аппарат\n" +
                        "  ООН → Организация Объединённых Наций\n" +
                        "  США → Соединённые Штаты Америки\n" +
                        "  НАТО → Североатлантический альянс\n" +
                        "  КНР → Китайская Народная Республика\n" +
                        "  МСК → Москва\n" +
                        "  ВСУ → вооружённые силы Украины\n" +
                        "  РФ → Российская Федерация\n" +
                        "  ЕС → Европейский союз\n" +
                        "Верни ТОЛЬКО очищенный текст в нижнем регистре, без пояснений и лишних слов.\n" +
                        "Пример для запроса \"Сводка СВО на сегодня\": сводка специальная военная операция\n" +
                        "Запрос: \"%s\"\nОчищенный запрос:",
                userQuery
        );

        try {
            String aiResponse = chat(prompt);
            if (aiResponse == null || aiResponse.isBlank()) {
                return userQuery;
            }

            // Убираем возможные кавычки и переносы строк, приводим к нижнему регистру
            String cleaned = aiResponse.trim()
                    .replace("\"", "")
                    .replace("\n", " ")
                    .toLowerCase();
            log.info("Cleaned query: {}", cleaned);
            return cleaned;
        } catch (Exception e) {
            log.error("Ошибка при очистке запроса через облачное API", e);
            return userQuery;   // fallback – исходный запрос
        }
    }

    /**
     * Получает эмбеддинг для переданного текста через облачное API.
     * @param text текст для векторизации
     * @return массив double – эмбеддинг, или null при ошибке
     */
    public double[] embed(String text) {
        try {
            HttpPost post = new HttpPost(apiUrl.replace("/chat/completions", "/embeddings"));
            post.setHeader("Authorization", apiKey);
            post.setHeader("Content-Type", "application/json");

            var requestMap = Map.of(
                    "model", "text-embedding-3-small",
                    "input", text
            );
            String requestBody = objectMapper.writeValueAsString(requestMap);
            post.setEntity(new StringEntity(requestBody, "UTF-8"));

            String response = httpClient.execute(post, httpResponse -> {
                String body = EntityUtils.toString(httpResponse.getEntity(), "UTF-8");
                if (httpResponse.getStatusLine().getStatusCode() != 200) {
                    throw new IOException("Embed API error " + httpResponse.getStatusLine().getStatusCode() + ": " + body);
                }
                return body;
            });

//                        log.info("Embed API response: {}", response);

            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddingArray = root.get("data").get(0).get("embedding");
            double[] embedding = new double[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding[i] = embeddingArray.get(i).asDouble();
            }
            return embedding;
        } catch (IOException e) {
            log.error("Ошибка при получении эмбеддинга через облачное API", e);
            return null;
        }
    }

    /**
     * Быстрая проверка доступности облачного API.
     * Вызывается при старте бота для информативного логирования.
     *
     * @return {@code true}, если API отвечает на тестовый запрос
     */
    public boolean isAvailable() {
        try {
            chat("test");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Отправляет сообщение в облачное API и возвращает текстовый ответ ассистента.
     * <p>
     * Использует стандартный формат OpenAI Chat Completions.
     *
     * @param userMessage сообщение для модели
     * @return ответ модели
     * @throws IOException при сетевых ошибках или ошибках API
     */
    private String chat(String userMessage) throws IOException {
        HttpPost post = new HttpPost(apiUrl);
        // GPTunnel (и аналоги) ожидают API-ключ без префикса "Bearer"
        post.setHeader("Authorization", apiKey);
        post.setHeader("Content-Type", "application/json");

        var requestMap = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", userMessage))
        );
        String requestBody = objectMapper.writeValueAsString(requestMap);
        post.setEntity(new StringEntity(requestBody, "UTF-8"));

        String response = httpClient.execute(post, httpResponse -> {
            String body = EntityUtils.toString(httpResponse.getEntity(), "UTF-8");
            if (httpResponse.getStatusLine().getStatusCode() != 200) {
                throw new IOException("API error " + httpResponse.getStatusLine().getStatusCode() + ": " + body);
            }
            return body;
        });

        log.info("API response: {}", response);

        JsonNode root = objectMapper.readTree(response);
        // Извлекаем содержимое первого ответа ассистента
        return root.get("choices").get(0).get("message").get("content").asText();
    }
}