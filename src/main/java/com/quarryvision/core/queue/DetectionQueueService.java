package com.quarryvision.core.queue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class DetectionQueueService implements AutoCloseable {

    /** Выполняет обработку одного видео и репортит прогресс. Должна бросать исключение при ошибке. */
    @FunctionalInterface
    public interface Processor {
        void process(Path video, Consumer<Integer> onProgress) throws Exception;
    }

    /** Слушатель событий задач (UI может подписаться). */
    @FunctionalInterface
    public interface Listener {
        void onUpdate(QueueTask task);
    }

    private final BlockingQueue<QueueTask> queue = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
       Thread t = new Thread(r, "qv-queue-worker");
       t.setDaemon(true);
       return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Future<?> workerFuture;

    public void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }
    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<QueueTask> snapshot() {
        return List.copyOf(queue);
    }

    public QueueTask enqueue(Path video) {
        Objects.requireNonNull(video, "video");
        QueueTask t = new QueueTask(video);
        queue.add(t);
        notifyListeners(t);
        return t;
    }

    /** Запустить обработчик очереди. Повторный вызов, если уже запущен, игнорируется. */
    public synchronized void start(Processor processor) {
        Objects.requireNonNull(processor, "processor");
        if (running.get()) return;
        running.set(true);
        workerFuture = exec.submit(() -> workerLoop(processor));
    }

    /** Остановить после текущей задачи. */
    public synchronized void stop() {
        running.set(false);
        if (workerFuture != null) workerFuture.cancel(false);
    }

    /** Отменить конкретную задачу, если она ещё не началась. */
    public boolean cancel(QueueTask t) {
        boolean removed = queue.remove(t);
        if (removed) {
            t.status = QueueTask.Status.CANCELED;
            t.finishedAt = Instant.now();
            notifyListeners(t);
        }
        return removed;
    }

    private void workerLoop(Processor processor) {
        while (running.get()) {
            try {
                QueueTask t = queue.poll(250, TimeUnit.MILLISECONDS);
                if (t == null) continue;

                t.status = QueueTask.Status.RUNNING;
                t.startedAt = Instant.now();
                t.message = "running";
                t.progress = 0;
                notifyListeners(t);

                try {
                    processor.process(t.video, p-> {
                        t.progress = clamp(p, 0, 100);
                        notifyListeners(t);
                    });
                    t.progress = 100;
                    t.status = QueueTask.Status.DONE;
                    t.message = "done";
                } catch (Exception ex) {
                    t.status = QueueTask.Status.FAILED;
                    t.message = String.valueOf(ex.getMessage());
                } finally {
                    t.finishedAt = Instant.now();
                    notifyListeners(t);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable th) {
                // не падаем, логика очереди продолжает работу
            }
        }
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void notifyListeners(QueueTask t) {
        for (Listener l : listeners) {
            try { l.onUpdate(t); } catch (Throwable ignore) {}
        }
    }

    @Override
    public void close() {
        stop();
        exec.shutdownNow();
    }
}
