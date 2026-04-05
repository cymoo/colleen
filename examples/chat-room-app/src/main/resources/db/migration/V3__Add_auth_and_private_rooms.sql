-- Password-based authentication for users
ALTER TABLE users ADD COLUMN password_hash TEXT;

-- Private chat rooms with optional password protection
ALTER TABLE rooms ADD COLUMN is_private INTEGER DEFAULT 0;
ALTER TABLE rooms ADD COLUMN password_hash TEXT;
ALTER TABLE rooms ADD COLUMN creator_id INTEGER REFERENCES users(id);
