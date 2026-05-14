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

@Slf4j
@Component
public class Parser {

    private final HttpClient httpClient;
    private final XmlMapper xmlMapper;
    private final List<String> rssUrls;

    // Внедряем список адресов
    public Parser(@Value("${rss.urls}") List<String> rssUrls) {
        this.httpClient = HttpClients.createDefault();
        this.xmlMapper = new XmlMapper();
        this.rssUrls = rssUrls;
    }

    public List<NewsPost> parse(String keywords) {
        List<NewsPost> allNews = new ArrayList<>();

        for (String url : rssUrls) {
            try {
                HttpGet request = new HttpGet(url);
                HttpResponse response = httpClient.execute(request);
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    log.warn("RSS-лента вернула код {}: {}", statusCode, url);
                    continue;
                }
                String xml = EntityUtils.toString(response.getEntity());

                RssWrapper wrapper = xmlMapper.readValue(xml, RssWrapper.class);
                if (wrapper == null || wrapper.getChannel() == null) {
                    log.warn("Не удалось найти <channel> в RSS-ленте: {}", url);
                    continue;
                }
                List<NewsPost> items = wrapper.getChannel().getItems();
                if (items != null) {
                    allNews.addAll(items);
                }
            } catch (IOException e) {
                log.error("Ошибка при получении или разборе RSS {}: {}", url, e.getMessage());
            }
        }

        if (allNews.isEmpty()) {
            return new ArrayList<>();
        }

        String[] words = keywords.toLowerCase().split("[,\\s]+");
        return allNews.stream()
                .filter(news -> {
                    String title = news.getTitle() != null ? news.getTitle().toLowerCase() : "";
                    String desc = news.getDescription() != null ? news.getDescription().toLowerCase() : "";
                    for (String word : words) {
                        if (!word.isEmpty() && (title.contains(word) || desc.contains(word))) {
                            return true;
                        }
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