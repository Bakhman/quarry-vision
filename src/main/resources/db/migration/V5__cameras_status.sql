ALTER TABLE cameras ADD COLUMN
    if not exists last_seen_at timestamptz;
ALTER TABLE cameras ADD COLUMN
    if not exists last_error text;