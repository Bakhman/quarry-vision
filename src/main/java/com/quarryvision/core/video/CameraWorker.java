package com.quarryvision.core.video;

import com.quarryvision.core.db.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;


// заглушка видеоконвейера
public class CameraWorker implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(CameraWorker.class);
    private final int id;
    private final String name;
    private volatile boolean run = true;
    private int retries = 0;
    private final int maxRetries = 3;
    private long lastHb = 0;

    public CameraWorker(int id, String name) {
        this.id = id;
        this.name = name;
    }

    private static String trimErr(String s) {
        if (s == null) return null;
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    @Override
    public void run() {
        lastHb = System.currentTimeMillis();
        log.info("CameraWorker #{} '{}' started", id, name);
        int i = 0;
        while (run) {
            try {
                // рабочий тик
                Thread.sleep(200);
                i++;
                long now = System.currentTimeMillis();
                if (now - lastHb >= 2000) { // не чаще, чем раз в 2с
                    log.debug("CameraWorker #{} '{}' ticks={}", id, name, i);
                    try {
                        Pg.setCameraHealth(id, Instant.ofEpochMilli(now), null);
                    } catch (Throwable ignore) {}
                    lastHb = now;
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
                try {
                    Pg.setCameraHealth(id, null, trimErr(t.toString()));
                } catch (Throwable ignore) {}
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
        log.info("CameraWorker #{} '{}' shutdown requested", id, name);
    }
}
