package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(10_000)
                        .setSocketTimeout(120_000)
                        .build())
                .setRetryHandler(new StandardHttpRequestRetryHandler(2, true))
                .build();
    }

    /**
     * Проверяет, доступна ли Ollama и загружена ли модель.
     * @return true, если сервис здоров
     */
    public boolean isAvailable() {
        try {
            HttpGet get = new HttpGet(apiUrl + "/api/tags");
            String response = httpClient.execute(get, httpResponse -> {
                if (httpResponse.getStatusLine().getStatusCode() != 200) return null;
                return EntityUtils.toString(httpResponse.getEntity());
            });
            if (response == null) return false;
            JsonNode root = objectMapper.readTree(response);
            JsonNode models = root.get("models");
            if (models != null && models.isArray()) {
                for (JsonNode m : models) {
                    if (model.equals(m.get("name").asText())) {
                        return true;
                    }
                }
            }
            log.warn("Model {} not found in Ollama", model);
            return false;
        } catch (IOException e) {
            log.error("Ollama health check failed", e);
            return false;
        }
    }

    // ... (остальные методы expandQuery, chat) полностью из предыдущей версии
    public List<String> expandQuery(String userQuery) {
        String safeQuery = userQuery
                .replace("\"", "\\\"")
                .replace("<|", "")
                .replace("|>", "")
                .replaceAll("\\b[А-Яа-я]\\.[А-Яа-я]?\\.[А-Яа-я]?\\.?", "")  // удаляем "Д.", "А.С." "А.С.П." и т.п.
                .trim();

        String prompt = String.format(
                "Сначала выпиши ВСЕ знаменательные слова из запроса, которые не являются предлогами, союзами, частицами, " +
                        "междометиями, местоимениями, вводными, вопросительными, модальными, словами‑паразитами и одиночными буквами с точками. " +
                        "Затем для КАЖДОГО выписанного слова (включая имена, названия, аббревиатуры) выдай его словоформы. " +
                        "Аббревиатуры (например, КНР, США, СВО) расшифруй полностью и для каждого слова расшифровки построй словоформы. " +
                        "Исходную аббревиатуру не включай в ответ. " +
                        "Для одного слова давай не более 10 словоформ (падежи, число, род) и усечённую основу. " +
                        "Пиши ТОЛЬКО словоформы через запятую, без пояснений. Не останавливайся, пока не обработаешь все слова.\n" +
                        "Пример для запроса \"СВО\": специальная, специальной, специальную, специальное, специальные, специальных, специальным, военная, военной, военную, военное, военные, военных, военным, операция, операции, операцию, операцией, операций, операциям, операциями, операциях\n" +
                        "Запрос: %s\n" +
                        "Знаменательные слова:",
                safeQuery
        );

        try {
            String aiResponse = chat(prompt);
            log.info("AI expandQuery response: {}", aiResponse);

            return Arrays.stream(aiResponse.split("[,\n]"))
                    .map(String::trim)
                    .filter(s -> s.length() > 2)   // отсекаем "А", "в" и т.п.
                    .filter(s -> !s.matches(".*[А-Яа-я]\\.[А-Яа-я]?.*"))  // удаляем любые строки с инициалами
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Ошибка при расширении запроса", e);
            return null;
        }
    }

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