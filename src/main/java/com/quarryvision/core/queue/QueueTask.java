package com.quarryvision.core.queue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public final class QueueTask {
    public enum Status { PENDING, RUNNING, DONE, FAILED, CANCELED }

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    public final int id = SEQ.getAndIncrement();
    public final Path video;
    public volatile Status status = Status.PENDING;
    public volatile int progress = 0;
    public volatile String message = "";
    public volatile Instant startedAt;
    public volatile Instant finishedAt;

    public QueueTask(Path video) {
        this.video = video;
    }
}
