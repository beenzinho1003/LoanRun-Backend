CREATE TABLE catalog_sync_state (
    id INTEGER PRIMARY KEY CHECK (id=1),
    last_success_at TIMESTAMP WITH TIME ZONE NOT NULL
);
