package com.quarryvision.app;

import com.quarryvision.core.importer.IngestProcessor;
import com.quarryvision.core.importer.UsbIngestService;
import com.quarryvision.ui.MainController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Path;

// JavaFX Application (main)
public class Boot extends Application {

    @Override
    public void start(Stage stage) {
        Config cfg = Config.load();
        System.out.println("Inbox dir: " + cfg.imp().inbox());
        MainController root = new MainController();
        stage.setTitle("QuarryVision - MVP");
        stage.setScene(new Scene(root.getRoot(), 900, 600));
        stage.show();

        // временно: вызвать ingest-test, если нужно
        try {
            var args = getParameters().getRaw();
            var src = args.isEmpty() ? cfg.imp().source() : args.get(0);
            runIngestTest(Path.of("F:/USB"), cfg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Тестовый запуск USB-ingest (вызывает UsbIngestService поверх IngestProcessor). */
    public static void runIngestTest(Path usbRoot, Config cfg) throws Exception {
        var proc = new IngestProcessor(Path.of(cfg.imp().inbox()));
        var svc = new UsbIngestService(proc, cfg.imp().patterns());
        int n = svc.scanAndIngest(usbRoot);
        System.out.println("Ingest complete. New files: " + n);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
