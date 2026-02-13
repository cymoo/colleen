DROP TABLE IF EXISTS posts;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    email TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE posts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    content TEXT,
    published BOOLEAN DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

INSERT INTO users (username, email) VALUES
    ('alice', 'alice@example.com'),
    ('bob', 'bob@example.com'),
    ('charlie', 'charlie@example.com');

INSERT INTO posts (user_id, title, content, published) VALUES
    (1, 'First Post', 'This is Alice''s first post', 1),
    (1, 'Second Post', 'This is Alice''s second post', 0),
    (2, 'Bob''s Introduction', 'Hello from Bob!', 1),
    (3, 'Charlie''s Thoughts', 'Some thoughts from Charlie', 1);