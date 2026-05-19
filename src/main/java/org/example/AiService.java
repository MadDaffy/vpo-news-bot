package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Сервис для проверки доступности LLM (Ollama).
 * Поиск выполняется через стемминг в {@link Parser} и не зависит от LLM.
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
        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(10_000)
                        .setSocketTimeout(30_000)
                        .build())
                .setRetryHandler(new StandardHttpRequestRetryHandler(2, true))
                .build();
    }

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
                    if (model.equals(m.get("name").asText())) return true;
                }
            }
            log.warn("Model {} not found in Ollama", model);
            return false;
        } catch (IOException e) {
            log.error("Ollama health check failed", e);
            return false;
        }
    }
}