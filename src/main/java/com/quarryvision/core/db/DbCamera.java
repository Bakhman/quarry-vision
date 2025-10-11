package com.quarryvision.core.db;

import java.time.OffsetDateTime;

public record DbCamera(int id, String name, String url, boolean active,
                       OffsetDateTime lastSeenAt, String lastError) {
}
