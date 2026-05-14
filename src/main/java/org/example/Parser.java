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

    @Value("${rss.url}")
    private String rssUrl;

    public Parser() {
        this.httpClient = HttpClients.createDefault();
        this.xmlMapper = new XmlMapper();
    }

    public List<NewsPost> parse(String keywords) {
        try {
            HttpGet request = new HttpGet(rssUrl);
            HttpResponse response = httpClient.execute(request);
            String xml = EntityUtils.toString(response.getEntity());

            RssWrapper wrapper = xmlMapper.readValue(xml, RssWrapper.class);
            List<NewsPost> allNews = wrapper.getChannel().getItems();

            if (allNews == null) {
                log.warn("No items found in RSS feed");
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

        } catch (IOException e) {
            log.error("Error parsing RSS feed", e);
            return new ArrayList<>();
        }
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