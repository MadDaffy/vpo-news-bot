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
 * Сервис для взаимодействия с облачным OpenAI‑совместимым API (GPTunnel).
 * <p>
 * Предоставляет методы для:
 * <ul>
 *   <li>очистки запроса (удаление стоп‑слов, расшифровка аббревиатур) — для стемминга;</li>
 *   <li>расшифровки аббревиатур с сохранением исходного текста — для векторного поиска;</li>
 *   <li>получения эмбеддингов (векторных представлений) текста.</li>
 * </ul>
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
     * Очищает запрос от стоп‑слов, временных меток и расшифровывает аббревиатуры.
     * Используется для подготовки текста перед лексическим поиском (стеммингом).
     *
     * @param userQuery исходный запрос пользователя
     * @return очищенный запрос (нижний регистр, только знаменательные слова)
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
            if (aiResponse == null || aiResponse.isBlank()) return userQuery;
            String cleaned = aiResponse.trim().replace("\"", "").replace("\n", " ").toLowerCase();
            log.info("Cleaned query for stemming: {}", cleaned);
            return cleaned;
        } catch (Exception e) {
            log.error("Ошибка при очистке запроса", e);
            return userQuery;
        }
    }

    /**
     * Расшифровывает аббревиатуры, сохраняя исходные аббревиатуры в тексте.
     * <p>
     * Пример: "Сводка СВО на сегодня" → "Сводка СВО (специальная военная операция) на сегодня"
     * <p>
     * Используется для подготовки текста перед семантическим (векторным) поиском.
     *
     * @param userQuery исходный запрос пользователя
     * @return запрос с расшифровками аббревиатур в скобках
     */
    public String expandAbbreviationsKeepOriginal(String userQuery) {
        String prompt = String.format(
                "Перепиши запрос, добавив после каждой аббревиатуры её полную расшифровку в скобках. " +
                        "Если аббревиатур нет, верни исходный запрос без изменений. Отвечай только переписанным запросом.\n" +
                        "Запрос: \"%s\"",
                userQuery
        );

        try {
            String aiResponse = chat(prompt);
            if (aiResponse == null || aiResponse.isBlank()) return userQuery;
            String rewritten = aiResponse.trim();
            log.info("Expanded with original abbreviations: {}", rewritten);
            return rewritten;
        } catch (Exception e) {
            log.error("Ошибка при расшифровке аббревиатур", e);
            return userQuery;
        }
    }

    /**
     * Получает эмбеддинг (векторное представление) для переданного текста.
     *
     * @param text текст для векторизации
     * @return массив double — эмбеддинг, или null при ошибке
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