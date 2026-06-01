-- Включаем расширение pgvector
CREATE EXTENSION IF NOT EXISTS vector;

-- Таблица новостей с эмбеддингами
CREATE TABLE IF NOT EXISTS news (
                                    id SERIAL PRIMARY KEY,
                                    title TEXT NOT NULL UNIQUE,          -- уникальность по заголовку
                                    description TEXT,
                                    link TEXT NOT NULL,
                                    pub_date TEXT,
                                    embedding vector(1536),             -- эмбеддинг размерности 1536
                                    created_at TIMESTAMP DEFAULT now()
);