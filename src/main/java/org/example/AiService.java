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
     * Расширяет запрос пользователя: генерирует словоформы и синонимы.
     * Возвращает список поисковых фраз.
     */
    public List<String> expandQuery(String userQuery) {
        String prompt = String.format(
                "<|turn|>system\n" +
                        "Ты — лингвистический анализатор поисковых запросов. " +
                        "Отвечай СТРОГО словоформами через запятую, без нумерации, без лишних слов.\n" +
                        "<|turn|>user\n" +
                        "Из запроса \"%s\" выдели только ЗНАМЕНАТЕЛЬНЫЕ слова (игнорируй предлоги, частицы, вопросительные слова). " +
                        "Для каждого знаменательного слова выдай ВСЕ возможные словоформы (падежи, числа, роды, спряжения). " +
                        "Объедини словоформы всех слов в один список через запятую.\n" +
                        "Пример для запроса \"что происходит в Ормузском проливе\": " +
                        "Ормузский, Ормузского, Ормузскому, Ормузским, Ормузском, " +
                        "пролив, пролива, проливу, проливом, проливе, проливы, проливов, проливам, проливами, проливах, " +
                        "происходит, происходят, происходил, происходила, происходили\n" +
                        "<|turn|>assistant\n",
                userQuery
        );

        try {
            String aiResponse = chat(prompt);
            log.info("AI expandQuery response: {}", aiResponse);

            List<String> phrases = Arrays.stream(aiResponse.split("[,\n]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && s.length() > 1)
                    .collect(Collectors.toList());

            // Добавляем исходный запрос целиком как дополнительную фразу (для точных совпадений)
            if (!phrases.contains(userQuery)) {
                phrases.add(0, userQuery);
            }
            return phrases;
        } catch (Exception e) {
            log.error("Ошибка при расширении запроса", e);
            return List.of(userQuery);
        }
    }

    /**
     * Отправляет запрос к Ollama.
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