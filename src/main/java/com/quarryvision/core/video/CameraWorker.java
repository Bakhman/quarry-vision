package com.quarryvision.core.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



// заглушка видеоконвейера
public class CameraWorker implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(CameraWorker.class);
    private final int id;
    private final String name;
    private volatile boolean run = true;
    private int retries = 0;
    private final int maxRetries = 3;
    public CameraWorker(int id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public void run() {
        log.info("CameraWorker #{} '{}' started", id, name);
        int i = 0;
        while (run) {
            try {
                // рабочий тик
                Thread.sleep(200);
                i++;
                if (i % 10 == 0) {
                    log.debug("CameraWorker #{} '{}' ticks={}", id, name, i);
                }
                // эмуляция нормальной работы — сбрасываем счётчик ошибок
                retries = 0;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.info("CameraWorker #{} '{}' interrupted", id, name);
                break;
            } catch (Throwable t) {
                retries++;
                long backoff = Math.min(2000L, 500L * (1L << Math.min(3, retries - 1)));
                log.warn("CameraWorker #{} '{}' error #{}, backoff {} ms: {}",
                        id, name, retries, backoff, t.toString());
                if (retries > maxRetries) {
                    log.error("CameraWorker #{} '{}' stopped after {} retries", id, name, maxRetries);
                    break;
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("CameraWorker #{} '{}' exited", id, name);
    }

    public void shutdown() {
        run = false;
    }
}
