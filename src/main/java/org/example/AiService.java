package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
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
     * Генерирует словоформы и основы для знаменательных слов.
     * Запрещено выводить предлоги, частицы, союзы.
     * @return список фраз или null при ошибке
     */
    public List<String> expandQuery(String userQuery) {
        String prompt = String.format(
                "<|turn|>system\n" +
                        "Ты — лингвистический анализатор. Отвечай СТРОГО словоформами и основами через запятую, без нумерации, без лишних слов.\n" +
                        "НЕ ВЫВОДИ предлоги, частицы, союзы, междометия. Игнорируй слова короче 3 букв.\n" +
                        "<|turn|>user\n" +
                        "Из запроса \"%s\" выдели только ЗНАМЕНАТЕЛЬНЫЕ слова. " +
                        "Для каждого такого слова выдай ВСЕ возможные СЛОВОФОРМЫ (падежи, числа, роды, спряжения), " +
                        "а также его УСЕЧЁННУЮ ОСНОВУ (корень без окончания).\n" +
                        "Пример для \"Ормузский пролив\": Ормузский, Ормузского, Ормузскому, Ормузским, Ормузском, Ормузск, пролив, пролива, проливу, проливом, проливе, проливы\n" +
                        "<|turn|>assistant\n",
                userQuery
        );

        try {
            String aiResponse = chat(prompt);
            log.info("AI expandQuery response: {}", aiResponse);

            return Arrays.stream(aiResponse.split("[,\n]"))
                    .map(String::trim)
                    .filter(s -> s.length() > 2)   // отсекаем "в", "во" и прочий мусор
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