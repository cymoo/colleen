-- Simplify user names: ensure display_name always equals username
-- This removes the separate display_name concept going forward.
UPDATE users SET display_name = username WHERE display_name != username;
