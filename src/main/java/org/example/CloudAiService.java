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
 * Сервис для очистки запроса и расшифровки аббревиатур через облачное OpenAI‑совместимое API.
 * <p>
 * Поддерживает любые провайдеры (GPTunnel, OpenRouter, OpenAI и т.д.) —
 * достаточно указать ключ, URL и модель в конфигурации.
 */
@Slf4j
@Service
public class CloudAiService {

    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;

    public CloudAiService(@Value("${cloud.api.key}") String apiKey,
                          @Value("${cloud.api.url}") String apiUrl,
                          @Value("${cloud.api.model}") String model) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(10_000)
                        .setSocketTimeout(30_000)
                        .build())
                .build();
    }

    /**
     * Очищает запрос от мусорных слов и расшифровывает аббревиатуры через облачное API.
     * Возвращает очищенный запрос. Если API недоступен — возвращает исходный запрос.
     */
    public String cleanAndExpandQuery(String userQuery) {
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

            String cleaned = aiResponse.trim()
                    .replace("\"", "")
                    .replace("\n", " ")
                    .toLowerCase();
            log.info("Cleaned query: {}", cleaned);
            return cleaned;
        } catch (Exception e) {
            log.error("Ошибка при очистке запроса через облачное API", e);
            return userQuery;
        }
    }

    /**
     * Быстрая проверка доступности API (опционально).
     * @return true, если API отвечает
     */
    public boolean isAvailable() {
        try {
            chat("test");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String chat(String userMessage) throws IOException {
        HttpPost post = new HttpPost(apiUrl);
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
        return root.get("choices").get(0).get("message").get("content").asText();
    }
}