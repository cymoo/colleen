-- Message editing/deletion
ALTER TABLE messages ADD COLUMN edited_at TIMESTAMP;
ALTER TABLE messages ADD COLUMN is_deleted INTEGER DEFAULT 0;

-- Message threading
ALTER TABLE messages ADD COLUMN reply_to_id INTEGER REFERENCES messages(id);

-- User profiles
ALTER TABLE users ADD COLUMN bio TEXT;
ALTER TABLE users ADD COLUMN status TEXT;

-- Room permissions
ALTER TABLE room_members ADD COLUMN role TEXT DEFAULT 'member';

-- Private messages
CREATE TABLE private_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sender_id INTEGER NOT NULL,
    receiver_id INTEGER NOT NULL,
    message_type TEXT NOT NULL CHECK(message_type IN ('text', 'image', 'file')),
    content TEXT,
    file_url TEXT,
    file_name TEXT,
    file_size INTEGER,
    mime_type TEXT,
    thumbnail_url TEXT,
    is_read INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_private_messages_participants ON private_messages(sender_id, receiver_id, created_at);
CREATE INDEX idx_private_messages_receiver ON private_messages(receiver_id, is_read);
CREATE INDEX idx_messages_reply_to ON messages(reply_to_id);
