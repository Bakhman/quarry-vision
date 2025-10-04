package com.quarryvision.core.db;

public class DbMergeAgg {
    public final int mergeMs;
    public final long detections;
    public final long events;

    public DbMergeAgg(int mergeMs, long detections, long events) {
        this.mergeMs = mergeMs;
        this.detections = detections;
        this.events = events;
    }
}
