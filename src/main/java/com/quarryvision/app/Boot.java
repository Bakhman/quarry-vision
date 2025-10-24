package com.quarryvision.app;

import com.quarryvision.core.db.Pg;
import com.quarryvision.core.importer.IngestProcessor;
import com.quarryvision.core.importer.UsbIngestService;
import com.quarryvision.core.ocr.OcrService;
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
        // страховка на случай внешнего kill
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Pg.close(); } catch (Throwable ignore) {}
        }));
        stage.setOnCloseRequest(e -> {
            System.out.println("Shutting down...");
            try {
                // 1) остановить фоновые задачи UI
                root.shutdown();
            } catch (Throwable ignore) {}
            try { Pg.close(); } catch (Throwable ignore) {}
            javafx.application.Platform.exit();
            System.exit(0);
        });
        stage.setTitle("QuarryVision - MVP");
        stage.setScene(new Scene(root.getRoot(), 900, 600));
        stage.show();

        // опциональный ingest-тест: по флагу --ingest[=path] или -Dqv.ingestTest=true
        List<String> args = getParameters().getRaw();

        // опциональная смок-инициализация OCR: -Dqv.ocr.init=true

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

    /** Одноразовая смок-инициализация OCR по системным свойствам, без зависимости от структуры Config. */
    private static void initOcrIfRequested() {
        if (!Boolean.getBoolean("qv.ocr.init")) return;
        try {
            String datapath = System.getProperty("qv.ocr.datapath", "./tessdata");
            String langs    = System.getProperty("qv.ocr.languages", "eng");
            int psm         = Integer.getInteger("qv.ocr.psm", 7);
            int oem         = Integer.getInteger("qv.ocr.oem", 3);
            new OcrService(new OcrService.Config(true, datapath, langs, psm, oem));
            System.out.println("OCR: initialized datapath=" + datapath + " languages=" + langs + " psm=" + psm + " oem=" + oem);
        } catch (Throwable t) {
            System.out.println("OCR: init failed: " + t);
        }
    }
}
