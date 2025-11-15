package com.quarryvision.core.db;

import java.time.OffsetDateTime;

public record DbDetectionRow(
        int id,
        int videoId,
        String videoPath,
        int mergeMs,
        int eventsCount,
        OffsetDateTime createdAt
) {
}
