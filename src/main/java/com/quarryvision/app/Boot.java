package com.quarryvision.app;

import com.quarryvision.core.db.Pg;
import com.quarryvision.core.importer.IngestProcessor;
import com.quarryvision.core.importer.UsbIngestService;
import com.quarryvision.ui.MainController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;

// JavaFX Application (main)
public class Boot extends Application {

    @Override
    public void start(Stage stage) {
        Config cfg = Config.load();
        System.out.println("Inbox dir: " + cfg.imp().inbox());
        // Инициализация БД и миграций заранее
        try {
            Pg.init();
        } catch (Exception e) {
            e.printStackTrace();
        }
        MainController root = new MainController();
        stage.setOnCloseRequest(e -> {
            System.out.println("Shutting down...");
            try {
                Pg.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            System.exit(0);
        });
        stage.setTitle("QuarryVision - MVP");
        stage.setScene(new Scene(root.getRoot(), 900, 600));
        stage.show();

        // опциональный ingest-тест: по флагу --ingest[=path] или -Dqv.ingestTest=true
        List<String> args = getParameters().getRaw();
    }

    private static void startIngestIfRequested(Config cfg, List<String> args) {
        boolean ingestFlag = Boolean.getBoolean("qv.ingestTest")
                || args.stream().anyMatch(a -> a.startsWith("--ingest"));
        if (!ingestFlag) return;

        Path usbPath = Path.of(cfg.imp().source());
        for (String a : args) {
            if (a.startsWith("--ingest=")) {
                usbPath = Path.of(a.substring("--ingest=".length()));
            }
        }

        final Path usb = usbPath;
        Thread thread = new Thread(() -> {
            try {
                runIngestTest(usb, cfg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "qv-ingest");
        thread.setDaemon(true);
        thread.start();
    }

    public static void main(String[] args) {
        launch(args);
    }

    /** Тестовый запуск USB-ingest (вызывает UsbIngestService поверх IngestProcessor). */
    public static void runIngestTest(Path usbRoot, Config cfg) throws Exception {
        var proc = new IngestProcessor(Path.of(cfg.imp().inbox()));
        var svc = new UsbIngestService(proc, cfg.imp().patterns());
        int n = svc.scanAndIngest(usbRoot);
        System.out.println("Ingest complete. New files: " + n);
    }
}
