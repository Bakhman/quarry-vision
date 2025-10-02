package com.quarryvision.core.db;

public final class DbVideoAgg {
    public final String path;
    public final long detections;
    public final long events;

    public DbVideoAgg(String path, long detections, long events) {
        this.path = path;
        this.detections = detections;
        this.events = events;
    }
}
