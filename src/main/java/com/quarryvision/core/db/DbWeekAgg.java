package com.quarryvision.core.db;

import java.time.DayOfWeek;

public class DbWeekAgg {
    public final DayOfWeek day;
    public final long detections;
    public final long events;

    public DbWeekAgg(DayOfWeek day, long detections, long events) {
        this.day = day;
        this.detections = detections;
        this.events = events;
    }
}
