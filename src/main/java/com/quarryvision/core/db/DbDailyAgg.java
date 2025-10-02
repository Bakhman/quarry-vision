package com.quarryvision.core.db;

import java.time.LocalDate;

public final class DbDailyAgg {
    public final LocalDate day;
    public final long detections;
    public final long events;

    public DbDailyAgg(LocalDate day, long detections, long events) {
        this.day = day;
        this.detections = detections;
        this.events = events;
    }
}
