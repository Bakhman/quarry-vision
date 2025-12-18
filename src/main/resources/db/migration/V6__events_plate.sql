-- Persist OCR plate (normalized) for each event
ALTER TABLE events
    ADD COLUMN IF NOT EXISTS plate varchar(16);

CREATE INDEX IF NOT EXISTS events_detection_id_plate_idx
    ON events(detection_id, plate);