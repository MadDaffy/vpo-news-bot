package org.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Сервис для сохранения новостей в PostgreSQL с pgvector
 * и выполнения векторного (семантического) поиска.
 * <p>
 * Уникальность записей контролируется по заголовку (title) –
 * это предотвращает дублирование одной и той же новости,
 * пришедшей из разных источников.
 */
@Slf4j
@Service
public class NewsStorageService {

    private final JdbcTemplate jdbcTemplate;
    private final CloudAiService cloudAiService;

    public NewsStorageService(JdbcTemplate jdbcTemplate, CloudAiService cloudAiService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cloudAiService = cloudAiService;
    }

    /**
     * Сохраняет список новостей в базу данных.
     * Для каждой новости вычисляется эмбеддинг и выполняется вставка в таблицу {@code news}.
     * Новости, чей заголовок уже присутствует в базе, пропускаются.
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
            if (count != null && count > 0) continue;   // уже есть – пропускаем

            // Получаем эмбеддинг для заголовка + описания
            String text = news.getTitle() + " " + (news.getDescription() != null ? news.getDescription() : "");
            double[] embedding = cloudAiService.embed(text);
            if (embedding == null) {
                log.warn("Не удалось получить эмбеддинг для новости: {}", news.getTitle());
                continue;
            }

            // Вставляем новость (ссылку тоже сохраняем как есть, она больше не ключ)
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

    /** Преобразует массив double в строку, понятную pgvector: "[0.1,0.2,0.3]" */
    private String pgvectorString(double[] vec) {
        return "[" + Arrays.stream(vec).mapToObj(Double::toString).collect(Collectors.joining(",")) + "]";
    }
}