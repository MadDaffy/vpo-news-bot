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
                .replace("|>", "");

        String prompt = String.format(
                "<|turn|>system\n" +
                        "Ты — лингвистический анализатор. Отвечай СТРОГО словоформами и основами через запятую, без нумерации, без лишних слов.\n" +
                        "НЕ ВЫВОДИ предлоги, союзы, частицы, междометия, вопросительные слова. Игнорируй слова короче 2 букв.\n" +
                        "ВАЖНО: Обрабатывай ВСЕ слова, включая имена, фамилии, названия, даже если они написаны с маленькой буквы или с опечаткой (пропущена/заменена 1-2 буквы).\n" +
                        "ВАЖНО: Если встречаешь АББРЕВИАТУРУ (например, \"СВО\", \"НАТО\", \"ООН\"), сначала расшифруй её полностью, а затем построй словоформы для каждого слова расшифровки.\n" +
                        "<|turn|>user\n" +
                        "Из запроса \"%s\" выдели АБСОЛЮТНО ВСЕ ЗНАМЕНАТЕЛЬНЫЕ СЛОВА. " +
                        "Если видишь слово, похожее на имя или фамилию (даже с опечаткой), восстанови правильную форму и построй для неё словоформы. " +
                        "Если видишь аббревиатуру (например, \"СВО\"), расшифруй её (\"специальная военная операция\") и построй словоформы для каждого слова расшифровки. " +
                        "Для каждого слова выдай ВСЕ возможные СЛОВОФОРМЫ (падежи, числа, роды, спряжения), " +
                        "а также его УСЕЧЁННУЮ ОСНОВУ (корень без окончания).\n" +
                        "Пример 1. Запрос: \"Ормузский пролив\" → ответ: Ормузский, Ормузского, Ормузскому, Ормузским, Ормузском, Ормузск, пролив, пролива, проливу, проливом, проливе, проливы\n" +
                        "Пример 2. Запрос: \"Визит Трампа в Китай\" → ответ: Визит, Визита, Визиту, Визитом, Визите, Визиты, Трамп, Трампа, Трампу, Трампом, Трампе, Китай, Китая, Китаю, Китаем, Китае\n" +
                        "Пример 3 (опечатка). Запрос: \"трамп\" → ответ: Трамп, Трампа, Трампу, Трампом, Трампе\n" +
                        "Пример 4 (аббревиатура). Запрос: \"Сводка СВО на сегодня\" → ответ: Сводка, Сводки, Сводке, Сводкой, Сводок, Сводкам, Сводками, Сводках, Сводк, специальная, специальной, специальную, специальное, специальные, специальных, специальным, специальными, военная, военной, военную, военное, военные, военных, военным, военными, операция, операции, операцию, операцией, операций, операциям, операциями, операциях, сегодня\n" +
                        "<|turn|>assistant\n",
                safeQuery
        );

        try {
            String aiResponse = chat(prompt);
            log.info("AI expandQuery response: {}", aiResponse);

            return Arrays.stream(aiResponse.split("[,\n]"))
                    .map(String::trim)
                    .filter(s -> s.length() > 2)
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