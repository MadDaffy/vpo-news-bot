package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Сервис для взаимодействия с локальной LLM (Gemma 4 E2B через Ollama).
 * Используется для расширения поискового запроса: генерации словоформ и основ слов.
 */
@Slf4j
@Service
public class AiService {

    private final String apiUrl;
    private final String model;
    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;

    public AiService(@Value("${ollama.api.url}") String apiUrl,
                     @Value("${ollama.model}") String model) {
        this.apiUrl = apiUrl;
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClients.createDefault();
    }

    /**
     * Расширяет запрос пользователя: выделяет знаменательные слова,
     * для каждого генерирует словоформы и усечённую основу (корень без окончания).
     *
     * @param userQuery исходный запрос (слово, фраза или предложение)
     * @return список поисковых фраз (словоформы + основы + исходный запрос)
     */
    public List<String> expandQuery(String userQuery) {
        // Промпт, который заставляет LLM:
        // 1. Выделить знаменательные слова (игнорируя стоп-слова).
        // 2. Для каждого знаменательного слова выдать все словоформы.
        // 3. Для каждого слова выдать усечённую основу (без окончания).
        String prompt = String.format(
                "<|turn|>system\n" +
                        "Ты — лингвистический анализатор. Отвечай СТРОГО словоформами и основами через запятую, без нумерации, без лишних слов.\n" +
                        "<|turn|>user\n" +
                        "Из запроса \"%s\" выдели только ЗНАМЕНАТЕЛЬНЫЕ слова (игнорируй предлоги, частицы, вопросительные слова). " +
                        "Для каждого знаменательного слова выдай ВСЕ возможные словоформы (падежи, числа, роды, спряжения), " +
                        "а также его УСЕЧЁННУЮ ОСНОВУ (корень без окончания). " +
                        "Объедини словоформы и основы всех слов в один список через запятую.\n" +
                        "Пример для запроса \"что происходит в Ормузском проливе\": " +
                        "Ормузский, Ормузского, Ормузскому, Ормузским, Ормузском, Ормузск, " +
                        "пролив, пролива, проливу, проливом, проливе, проливы, проливов, проливам, проливами, проливах, пролив, " +
                        "происходит, происходят, происходил, происходила, происходили, происход\n" +
                        "<|turn|>assistant\n",
                userQuery
        );

        try {
            String aiResponse = chat(prompt);
            log.info("AI expandQuery response: {}", aiResponse);

            // Разбиваем ответ по запятым и переводам строк, чистим
            List<String> phrases = Arrays.stream(aiResponse.split("[,\n]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && s.length() > 1)
                    .collect(Collectors.toList());

            // Добавляем исходный запрос целиком, если его ещё нет
            if (!phrases.contains(userQuery)) {
                phrases.add(0, userQuery);
            }
            return phrases;
        } catch (Exception e) {
            log.error("Ошибка при расширении запроса", e);
            // В случае ошибки возвращаем только исходный запрос
            return List.of(userQuery);
        }
    }

    /**
     * Отправляет запрос к Ollama и возвращает текстовый ответ.
     */
    private String chat(String userMessage) throws IOException {
        HttpPost post = new HttpPost(apiUrl + "/api/generate");
        post.setHeader("Content-Type", "application/json; charset=UTF-8");

        var requestMap = Map.of(
                "model", model,
                "prompt", userMessage,
                "stream", false
        );
        String requestBody = objectMapper.writeValueAsString(requestMap);
        post.setEntity(new StringEntity(requestBody,
                org.apache.http.entity.ContentType.APPLICATION_JSON));

        String response = httpClient.execute(post, httpResponse -> {
            String body = EntityUtils.toString(httpResponse.getEntity(), "UTF-8");
            if (httpResponse.getStatusLine().getStatusCode() != 200) {
                throw new IOException("API error: " + httpResponse.getStatusLine().getStatusCode());
            }
            return body;
        });

        JsonNode root = objectMapper.readTree(response);
        return root.get("response").asText().trim();
    }
}