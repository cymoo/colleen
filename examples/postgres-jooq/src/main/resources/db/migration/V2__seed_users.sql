INSERT INTO users (username, email)
VALUES
    ('ada', 'ada@example.com'),
    ('grace', 'grace@example.com')
ON CONFLICT (username) DO NOTHING;
