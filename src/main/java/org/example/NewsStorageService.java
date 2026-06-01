package org.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Сервис для сохранения новостей в PostgreSQL с pgvector,
 * обновления эмбеддингов и выполнения семантического поиска.
 * <p>
 * Уникальность записей контролируется по заголовку (title) —
 * это предотвращает дублирование одной и той же новости,
 * пришедшей из разных источников.
 * <p>
 * Каждые 6 часов автоматически запускается обновление новостей
 * из RSS‑лент (метод {@link #refreshNews()}).
 * Первое обновление стартует только через 6 часов после запуска,
 * чтобы не конфликтовать с фоновой загрузкой при старте.
 */
@Slf4j
@Service
public class NewsStorageService {

    private final JdbcTemplate jdbcTemplate;
    private final CloudAiService cloudAiService;
    private final Parser parser;

    public NewsStorageService(JdbcTemplate jdbcTemplate,
                              CloudAiService cloudAiService,
                              Parser parser) {
        this.jdbcTemplate = jdbcTemplate;
        this.cloudAiService = cloudAiService;
        this.parser = parser;
    }

    /**
     * Сохраняет список новостей в базу данных.
     * Для каждой новости вычисляется эмбеддинг и выполняется вставка.
     * Новости с уже существующим заголовком пропускаются.
     * При возникновении ошибки уникальности (редкий случай гонки)
     * вставка логируется как предупреждение и не прерывает процесс.
     *
     * @param newsList список новостей для сохранения
     */
    public void saveNews(List<NewsPost> newsList) {
        int total = newsList.size();
        int processed = 0;

        for (NewsPost news : newsList) {
            if (news.getTitle() == null || news.getTitle().isBlank()) continue;

            // Проверяем уникальность по заголовку
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM news WHERE title = ?",
                    Integer.class, news.getTitle()
            );
            if (count != null && count > 0) continue;

            // Формируем текст для эмбеддинга: заголовок + описание
            String text = (news.getTitle() != null ? news.getTitle() : "")
                    + " " + (news.getDescription() != null ? news.getDescription() : "");
            double[] embedding = cloudAiService.embed(text);
            if (embedding == null) {
                log.warn("Не удалось получить эмбеддинг для новости: {}", news.getTitle());
                continue;
            }

            try {
                jdbcTemplate.update(
                        "INSERT INTO news (title, description, link, pub_date, embedding) VALUES (?, ?, ?, ?, ?::vector)",
                        news.getTitle(),
                        news.getDescription(),
                        news.getLink(),
                        news.getPubDate(),
                        pgvectorString(embedding)
                );
                processed++;
                if (processed % 10 == 0) {
                    log.info("Прогресс загрузки эмбеддингов: {}/{}", processed, total);
                }
            } catch (DuplicateKeyException e) {
                log.warn("Пропущен дубликат заголовка: {}", news.getTitle());
            }
        }

        log.info("Сохранено {} новых новостей (всего обработано {})", processed, total);
    }

    /**
     * Выполняет семантический поиск новостей по векторному сходству.
     *
     * @param query поисковый запрос
     * @param limit максимальное количество возвращаемых результатов
     * @return список новостей, отсортированный по убыванию сходства
     */
    public List<NewsPost> semanticSearch(String query, int limit) {
        double[] queryEmbedding = cloudAiService.embed(query);
        if (queryEmbedding == null) return Collections.emptyList();

        String sql = """
            SELECT title, description, link, pub_date,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM news
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    NewsPost post = new NewsPost();
                    post.setTitle(rs.getString("title"));
                    post.setDescription(rs.getString("description"));
                    post.setLink(rs.getString("link"));
                    post.setPubDate(rs.getString("pub_date"));
                    return post;
                },
                pgvectorString(queryEmbedding),
                pgvectorString(queryEmbedding),
                limit
        );
    }

    /**
     * Автоматически обновляет новости из RSS‑лент каждые 6 часов.
     * Первое обновление стартует только через 6 часов после запуска.
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000, initialDelay = 6 * 60 * 60 * 1000)
    public void refreshNews() {
        log.info("Плановое обновление новостей из RSS...");
        List<NewsPost> freshNews = parser.getAllNews();
        saveNews(freshNews);
        log.info("Плановое обновление завершено.");
    }

    /** Преобразует массив double в строку, понятную pgvector: "[0.1,0.2,0.3]" */
    private String pgvectorString(double[] vec) {
        return "[" + Arrays.stream(vec).mapToObj(Double::toString).collect(Collectors.joining(",")) + "]";
    }
}