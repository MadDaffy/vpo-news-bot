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

/**
 * Парсер RSS-лент с морфологическим поиском на основе Apache Lucene RussianAnalyzer.
 * <p>
 * Никакие LLM/нейросети не используются на этапе поиска.
 * Поиск полностью детерминирован и не зависит от внешних API.
 * <p>
 * <b>Основная логика:</b>
 * <ul>
 *   <li>Загружает все новости из списка RSS-источников (метод {@link #getAllNews()}).</li>
 *   <li>Выполняет поиск по основам слов (стемминг) с ранжированием по количеству совпадений
 *       (метод {@link #searchByStemsRanked(String)}).</li>
 *   <li>Стемминг игнорирует окончания, падежи и склонения — например, запрос «Ормузском»
 *       найдёт «Ормузский», «Ормузского» и т.д.</li>
 * </ul>
 */
@Slf4j
@Component
public class Parser {

    /** HTTP-клиент с повторными попытками при сетевых сбоях */
    private final HttpClient httpClient;
    /** Маппер XML → Java-объекты */
    private final XmlMapper xmlMapper;
    /** Морфологический анализатор Lucene для русского языка */
    private final RussianAnalyzer russianAnalyzer;

    /** Список URL RSS-лент, загружаемый из конфигурации */
    @Value("${rss.urls}")
    private List<String> rssUrls;

    public Parser() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10_000)   // таймаут на установку соединения
                .setSocketTimeout(15_000)    // таймаут на чтение данных
                .build();
        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(config)
                .setRetryHandler(new StandardHttpRequestRetryHandler(3, true)) // до 3-х повторных попыток
                .build();
        this.xmlMapper = new XmlMapper();
        this.russianAnalyzer = new RussianAnalyzer();   // Lucene Russian Analyzer
    }

    /**
     * Загружает все новости из всех RSS-источников без фильтрации.
     * <p>
     * Для каждого источника выполняется HTTP GET-запрос. Если статус ответа не 200,
     * источник пропускается. Кодировка определяется из заголовка Content-Type,
     * по умолчанию используется UTF-8.
     *
     * @return полный список новостей из всех доступных лент
     */
    public List<NewsPost> getAllNews() {
        List<NewsPost> allNews = new ArrayList<>();
        for (String url : rssUrls) {
            try {
                HttpGet request = new HttpGet(url);
                HttpResponse response = httpClient.execute(request);
                if (response.getStatusLine().getStatusCode() != 200) continue;

                // Определяем кодировку из Content-Type (например, windows-1251 или UTF-8)
                String charset = "UTF-8";
                if (response.getEntity().getContentType() != null) {
                    String contentType = response.getEntity().getContentType().getValue();
                    String[] parts = contentType.split("charset=");
                    if (parts.length > 1) charset = parts[1].trim();
                }

                String xml = EntityUtils.toString(response.getEntity(), charset);
                RssWrapper wrapper = xmlMapper.readValue(xml, RssWrapper.class);
                if (wrapper != null && wrapper.getChannel() != null
                        && wrapper.getChannel().getItems() != null) {
                    allNews.addAll(wrapper.getChannel().getItems());
                }
            } catch (IOException e) {
                log.error("Ошибка парсинга {}: {}", url, e.getMessage());
            }
        }
        return allNews;
    }

    /**
     * Ищет новости по основам слов с ранжированием по количеству совпадений.
     * <p>
     * Запрос разбивается на токены через {@link RussianAnalyzer}, для каждого токена
     * выделяется основа (стем). Затем для каждой новости считается, сколько основ запроса
     * встречается в её заголовке или описании. Новости сортируются по убыванию количества
     * совпадений — таким образом, наиболее релевантные оказываются в начале списка.
     * <p>
     * <b>Пример:</b> запрос «сводка СВО на сегодня» после стемминга даст основы
     * «сводк», «сво», «сегодн». Новость, содержащая все три основы, будет в топе.
     *
     * @param query поисковый запрос пользователя
     * @return список новостей, отсортированный по релевантности
     */
    public List<NewsPost> searchByStemsRanked(String query) {
        List<NewsPost> allNews = getAllNews();
        if (allNews.isEmpty()) return new ArrayList<>();

        // Извлекаем основы (стемы) из запроса
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

    /**
     * Разбивает текст на токены и возвращает множество основ (стемов).
     * <p>
     * Использует {@link RussianAnalyzer}, который приводит слова к начальной форме
     * (лемматизация) и отсекает стоп-слова. Однобуквенные токены отбрасываются.
     *
     * @param text исходный текст
     * @return множество основ слов
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

    // ======================== Вспомогательные классы для десериализации RSS ========================

    /**
     * Корневая обёртка RSS-ленты. Содержит элемент {@code <channel>}.
     */
    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RssWrapper {
        @JacksonXmlProperty(localName = "channel")
        private Channel channel;
    }

    /**
     * Элемент {@code <channel>} RSS-ленты. Содержит список новостей {@code <item>}.
     */
    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Channel {
        /** Элементы {@code <item>} идут сразу внутри {@code <channel>}, без обёртки */
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "item")
        private List<NewsPost> items;
    }
}