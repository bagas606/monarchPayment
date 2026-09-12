-- V14's DEFAULT 0 only existed to satisfy NOT NULL for rows created before that column existed;
-- every real writer (ChildOrderService.createForPattern) always supplies a real value, so the
-- default is dropped here rather than left as a silent zero-fallback for a future writer that
-- forgets to set it.
ALTER TABLE child_order ALTER COLUMN face_value DROP DEFAULT;
