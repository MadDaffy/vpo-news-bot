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
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Component
public class Parser {

    private final HttpClient httpClient;
    private final XmlMapper xmlMapper;
    private final RussianAnalyzer russianAnalyzer;   // морфологический анализатор

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
        this.russianAnalyzer = new RussianAnalyzer();   // Lucene Russian Analyzer
    }

    public List<NewsPost> getAllNews() {
        List<NewsPost> allNews = new ArrayList<>();
        for (String url : rssUrls) {
            try {
                HttpGet request = new HttpGet(url);
                HttpResponse response = httpClient.execute(request);
                if (response.getStatusLine().getStatusCode() != 200) continue;
                String charset = "UTF-8";
                if (response.getEntity().getContentType() != null) {
                    String contentType = response.getEntity().getContentType().getValue();
                    String[] parts = contentType.split("charset=");
                    if (parts.length > 1) charset = parts[1].trim();
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

    public List<NewsPost> searchByStemsRanked(String query) {
        List<NewsPost> allNews = getAllNews();
        if (allNews.isEmpty()) return new ArrayList<>();

        Set<String> queryStems = stemText(query);
        if (queryStems.isEmpty()) return new ArrayList<>();

        // Считаем для каждой новости количество совпавших основ
        Map<NewsPost, Integer> newsScores = new HashMap<>();
        for (NewsPost news : allNews) {
            String text = (news.getTitle() != null ? news.getTitle() : "")
                    + " " + (news.getDescription() != null ? news.getDescription() : "");
            Set<String> newsStems = stemText(text);
            // Пересечение основ запроса и новости
            Set<String> intersection = new HashSet<>(queryStems);
            intersection.retainAll(newsStems);
            if (!intersection.isEmpty()) {
                newsScores.put(news, intersection.size());
            }
        }

        // Сортируем по убыванию количества совпадений
        return newsScores.entrySet().stream()
                .sorted(Map.Entry.<NewsPost, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    // Точный поиск (полная фраза)
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

    // Поиск по ключевым словам (подстрока)
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

    /**
     * Ищет новости по основам слов (стемминг). Игнорирует окончания, падежи, склонения.
     * Пример: запрос "Ормузском" найдёт "Ормузский", "Ормузского" и т.д.
     */
    public List<NewsPost> searchByStems(String query) {
        List<NewsPost> allNews = getAllNews();
        if (allNews.isEmpty()) return new ArrayList<>();

        // Извлекаем основы (стемы) из запроса
        Set<String> queryStems = stemText(query);
        if (queryStems.isEmpty()) return new ArrayList<>();

        return allNews.stream()
                .filter(news -> {
                    String text = (news.getTitle() != null ? news.getTitle() : "")
                            + " " + (news.getDescription() != null ? news.getDescription() : "");
                    Set<String> newsStems = stemText(text);
                    // Если есть хотя бы одно пересечение по основе – новость подходит
                    for (String qs : queryStems) {
                        if (newsStems.contains(qs)) return true;
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    /**
     * Разбивает текст на токены и возвращает множество основ (стемов) с помощью RussianAnalyzer.
     */
    private Set<String> stemText(String text) {
        Set<String> stems = new HashSet<>();
        try (TokenStream ts = russianAnalyzer.tokenStream("", new StringReader(text))) {
            ts.reset();
            CharTermAttribute term = ts.getAttribute(CharTermAttribute.class);
            while (ts.incrementToken()) {
                String stem = term.toString();
                if (stem.length() > 1) {   // отсекаем однобуквенные основы
                    stems.add(stem);
                }
            }
            ts.end();
        } catch (IOException e) {
            log.error("Ошибка стемминга текста: {}", e.getMessage());
        }
        return stems;
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