package com.quarryvision.core.video;

// заглушка видеоконвейера
public class CameraWorker implements Runnable {
    private final int id;
    private final String name;
    private volatile boolean run = true;
    public CameraWorker(int id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public void run() {
        int i = 0;
        while (run && i < 100) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
            i++;
            if(i % 10 == 0) {
                System.out.println("[CameraWorker #" + id + " " + name + "] ticks=" + i);
            }
        }
    }

    public void shutdown() {
        run = false;
    }
}
