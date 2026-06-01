-- Включаем расширение pgvector, если оно ещё не включено
CREATE EXTENSION IF NOT EXISTS vector;

-- Создаём таблицу новостей, если её нет
CREATE TABLE IF NOT EXISTS news (
                                    id SERIAL PRIMARY KEY,
                                    title TEXT NOT NULL,
                                    description TEXT,
                                    link TEXT NOT NULL UNIQUE,
                                    pub_date TEXT,
                                    embedding vector(1536),
    created_at TIMESTAMP DEFAULT now()
    );