package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Парсер RSS-лент. Загружает новости и умеет искать:
 * - по полной фразе (точный поиск)
 * - по набору ключевых слов (расширенный поиск)
 */
@Slf4j
@Component
public class Parser {

    private final HttpClient httpClient;
    private final XmlMapper xmlMapper;

    @Value("${rss.urls}")
    private List<String> rssUrls;

    public Parser() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10_000)
                .setSocketTimeout(15_000)
                .build();
        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(config)
                .setRetryHandler(new StandardHttpRequestRetryHandler(3, true))
                .build();
        this.xmlMapper = new XmlMapper();
    }

    /**
     * Загружает ВСЕ новости из всех RSS-лент без фильтрации.
     */
    public List<NewsPost> getAllNews() {
        List<NewsPost> allNews = new ArrayList<>();
        for (String url : rssUrls) {
            try {
                HttpGet request = new HttpGet(url);
                HttpResponse response = httpClient.execute(request);
                if (response.getStatusLine().getStatusCode() != 200) continue;

                // Определяем кодировку из Content-Type
                String charset = "UTF-8";
                if (response.getEntity().getContentType() != null) {
                    String contentType = response.getEntity().getContentType().getValue();
                    String[] parts = contentType.split("charset=");
                    if (parts.length > 1) {
                        charset = parts[1].trim();
                    }
                }

                String xml = EntityUtils.toString(response.getEntity(), charset);
                RssWrapper wrapper = xmlMapper.readValue(xml, RssWrapper.class);
                if (wrapper != null && wrapper.getChannel() != null && wrapper.getChannel().getItems() != null) {
                    allNews.addAll(wrapper.getChannel().getItems());
                }
            } catch (IOException e) {
                log.error("Ошибка парсинга {}: {}", url, e.getMessage());
            }
        }
        return allNews;
    }

    /**
     * Ищет новости, содержащие ПОЛНУЮ строку (точное совпадение фразы).
     */
    public List<NewsPost> searchExactPhrase(String phrase) {
        List<NewsPost> allNews = getAllNews();
        if (allNews.isEmpty()) return new ArrayList<>();

        String lowerPhrase = phrase.toLowerCase();
        return allNews.stream()
                .filter(n -> {
                    String t = n.getTitle() != null ? n.getTitle().toLowerCase() : "";
                    String d = n.getDescription() != null ? n.getDescription().toLowerCase() : "";
                    return t.contains(lowerPhrase) || d.contains(lowerPhrase);
                })
                .collect(Collectors.toList());
    }

    /**
     * Ищет новости, содержащие ХОТЯ БЫ ОДНО из ключевых слов (по подстроке).
     */
    public List<NewsPost> searchByKeywords(String keywords) {
        List<NewsPost> allNews = getAllNews();
        if (allNews.isEmpty()) return new ArrayList<>();

        String[] words = keywords.toLowerCase().split("[,\\s]+");
        return allNews.stream()
                .filter(n -> {
                    String t = n.getTitle() != null ? n.getTitle().toLowerCase() : "";
                    String d = n.getDescription() != null ? n.getDescription().toLowerCase() : "";
                    for (String w : words) {
                        if (!w.isEmpty() && (t.contains(w) || d.contains(w))) return true;
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RssWrapper {
        @JacksonXmlProperty(localName = "channel")
        private Channel channel;
    }

    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Channel {
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "item")
        private List<NewsPost> items;
    }
}