package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Парсер RSS-лент. Загружает новости и умеет фильтровать по набору ключевых слов.
 */
@Slf4j
@Component
public class Parser {

    private final HttpClient httpClient;
    private final XmlMapper xmlMapper;

    @Value("${rss.urls}")
    private List<String> rssUrls;

    public Parser() {
        this.httpClient = HttpClients.createDefault();
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
                String xml = EntityUtils.toString(response.getEntity(), "UTF-8");
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
     * Фильтрует новости по набору ключевых слов (поиск по подстроке без учёта регистра).
     *
     * @param keywords строка с ключевыми словами, разделёнными пробелами или запятыми
     * @return список новостей, содержащих хотя бы одно из ключевых слов
     */
    public List<NewsPost> parse(String keywords) {
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

    // Вспомогательные классы для десериализации RSS 2.0

    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RssWrapper {
        @JacksonXmlProperty(localName = "channel")
        private Channel channel;
    }

    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Channel {
        @JacksonXmlElementWrapper(useWrapping = false) // элементы <item> идут сразу внутри <channel>
        @JacksonXmlProperty(localName = "item")
        private List<NewsPost> items;
    }
}