package com.quarryvision.ui;

import com.quarryvision.app.Config;
import com.quarryvision.core.db.Pg;
import com.quarryvision.core.importer.IngestProcessor;
import com.quarryvision.core.importer.UsbIngestService;
import com.quarryvision.core.video.CameraWorker;
import com.quarryvision.core.detection.BucketDetector;
import javafx.scene.layout.*;
import org.bytedeco.opencv.opencv_core.Size;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// простая сцена/вкладки
public class MainController {
    private final BorderPane root = new BorderPane();
    private final ExecutorService exec = Executors.newFixedThreadPool(4);
    private final Config cfg = Config.load();

    public MainController() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Import
        VBox importBox = new VBox(10);
        importBox.setPadding(new Insets(12));
        TextField path = new TextField();
        path.setPromptText(cfg.imp().source() != null ? cfg.imp().source() : "F:/USB");
        Button scan = new Button("Сканировать");
        Button detect = new Button("Detect");
        TextArea log = new TextArea();
        log.setEditable(false);
        log.setPrefRowCount(10);
        scan.setOnAction(e -> {
            String src = path.getText().isBlank()
                    ? (cfg.imp().source() == null ? "" : cfg.imp().source())
                    : path.getText();
            if (src.isBlank()) {
                log.appendText("Укажи путь к источнику.\n");
                return;
            }
            scan.setDisable(true);
            log.appendText("Сканирую: " + src + " ...\n");
            exec.submit(() -> {
                try {
                    var proc = new IngestProcessor(Path.of(cfg.imp().inbox()));
                    var svc  = new UsbIngestService(proc, cfg.imp().patterns());
                    int n = svc.scanAndIngest(Path.of(src));
                    Platform.runLater(() -> log.appendText("Готово. Новых файлов: " + n + "\n"));
                } catch (Exception ex) {
                    Platform.runLater(() -> log.appendText("Ошибка: " + ex + "\n"));
                } finally {
                    Platform.runLater(() -> scan.setDisable(false));
                }
            });
        });
        // Detect: запустить BucketDetector по указанному видео (путь в поле)
        detect.setOnAction(e -> {
            String src = path.getText().isBlank()
                    ? (cfg.imp().source() == null ? "" : cfg.imp().source())
                    : path.getText();
            if (src.isBlank()) {
                log.appendText("Укажи путь к видео для детекции.\n");
                return;
            }
            Path p = Path.of(src);
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                log.appendText("File <<" + p + ">> not found\n");
                return;
            }
            detect.setDisable(true);
            log.appendText("Detect: " + src + " ...\n");
            exec.submit(() -> {
                try {
                    var dc = cfg.detection();
                    var det = new BucketDetector(
                            dc.stepFrames(),
                            dc.diffThreshold(),
                            dc.eventRatio(),
                            dc.cooldownFrames(),
                            dc.minChangedPixels(),
                            new Size(dc.morphW(), dc.morphH()),
                            dc.mergeMs()
                    );
                    var res = det.detect(p);
                    javafx.application.Platform.runLater(() -> {
                        var list = res.timestampsMs();
                        log.appendText("Events=" + list.size() +
                                " fps=" + res.fps() + " frames=" + res.frames() + "\n");
                        int show = Math.min(10, list.size());
                        for (int i = 0; i < show; i++) {
                            log.appendText("  @" + list.get(i).toEpochMilli() + " ms\n");
                        }
                        if (list.size() > show) {
                            log.appendText("  ... +" + (list.size() - show) + " more\n");
                        }
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> log.appendText("Detect error: " + ex + "\n"));
                } finally {
                    javafx.application.Platform.runLater(() -> detect.setDisable(false));
                }
            });
        });

        importBox.getChildren().addAll(new Label("Import video"), path, scan, detect, log);
        tabs.getTabs().add(new Tab("Import", importBox));

        // Detect UI: поле и кнопка
        TextField detectPath = new TextField();
        detectPath.setPromptText("E:/INBOX/locs.mp4");
        Button detectBtn = new Button("Detect");
        detectBtn.setOnAction(e -> {
            String src = detectPath.getText().trim();
            Path p = Path.of(src);
            if (!Files.isRegularFile(p)) {
                log.appendText("File <<" + src + ">> not found\n");
                return;
            }
            detectBtn.setDisable(true);
            exec.submit(() -> {
                try {
                    var dc = cfg.detection();
                    var det = new BucketDetector(dc.stepFrames(), dc.diffThreshold(), dc.eventRatio(),
                            dc.cooldownFrames(), dc.minChangedPixels(), new Size(dc.morphW(), dc.morphH()), dc.mergeMs());
                    var res = det.detect(p);
                    int videoId = Pg.upsertVideo(p, res.fps(), res.frames());
                    int detId = Pg.insertDetection(videoId, dc.mergeMs(), res.timestampsMs());
                    Platform.runLater(() -> {
                        log.appendText(String.format("Detect: %s ...%n", src));
                        log.appendText(String.format("Saved detection id=%d videoId=%d events=%d%n",
                                detId, videoId, res.timestampsMs().size()));
                    });
                } catch (Exception ex2) {
                    Platform.runLater(() -> log.appendText("Detect error: " + ex2 + "\n"));
                } finally {
                    Platform.runLater(() -> detectBtn.setDisable(false));
                }
            });
        });
        importBox.getChildren().addAll(new Separator(), new Label("Detect video"), detectPath, detectBtn);

        // Queue
        VBox q = new VBox(10);
        q.setPadding(new Insets(12));
        ProgressBar pb = new ProgressBar(0);
        Button sim = new Button("Симуляция очереди");
        sim.setOnAction(e -> pb.setProgress(Math.min(1.0, pb.getProgress()+0.1)));
        q.getChildren().addAll(pb, sim);
        tabs.getTabs().add(new Tab("Queue", q));

        // Reports
        VBox rep = new VBox(10);
        rep.setPadding(new Insets(12));
        Label hdr = new Label("Detections history");
        ListView<String> list = new ListView<>();
        list.setPrefHeight(260);
        Button refresh = new Button("Refresh");
        Button showEv = new Button("Show events");
        Button del = new Button("Delete detection");
        TextArea evArea = new TextArea();
        evArea.setEditable(false);
        evArea.setPrefRowCount(10);
        Label stats = new Label(); // Videos | Detections | Events
        refresh.setOnAction(e -> {
            refresh.setDisable(true);
            evArea.clear();
            exec.submit(() -> {
                try {
                    List<String> rows = Pg.listRecentDetections(50);
                    long v = Pg.countVideos();
                    long d = Pg.countDetections();
                    long ev = Pg.countEvents();
                    Platform.runLater(() -> {
                        list.getItems().setAll(rows);
                        stats.setText("Videos: " + v + " | Detections: " + d + " | Events: " + ev);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        evArea.appendText("Load error: " + ex + "\n");
                    });
                } finally {
                    Platform.runLater(() -> refresh.setDisable(false));
                }
            });
        });

        showEv.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null || sel.isBlank()) return;
            // формат строки: "#<id> | ..."
            int hash = sel.indexOf('#');
            int sp = sel.indexOf(' ');
            if (hash != -1 && sp > hash) {
                try {
                    // fix: корректный разбор "#<id> ..."
                    int detId = Integer.parseInt(sel.substring(hash + 1, sp));
                    evArea.clear();
                    exec.submit(() -> {
                        try {
                            List<Long> ts = Pg.listEventsMs(detId);
                            Platform.runLater(() -> {
                                evArea.appendText("Detection #" + detId + " events: " + ts.size() + "\n");
                                for (Long t : ts) {
                                    evArea.appendText(" @ " + fmtMs(t) + "\n");
                                }
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> evArea.appendText("Load error: " + ex + "\n"));
                        }
                    });
                } catch (NumberFormatException ignore) { /* no-op */}
            }
        });

        del.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null || sel.isBlank()) return;
            int hash = sel.indexOf('#');
            int sp = sel.indexOf(' ');
            if (hash != -1 && sp > hash) {
                try {
                    int detId = Integer.parseInt(sel.substring(hash + 1, sp));
                    del.setDisable(true);
                    exec.submit(() -> {
                        try {
                            Pg.deleteDetection(detId);
                            // после удаления — перезагрузить список и статистику
                            List<String> rows = Pg.listRecentDetections(50);
                            long v = Pg.countVideos();
                            long d = Pg.countDetections();
                            long ev = Pg.countEvents();
                            Platform.runLater(() -> {
                                list.getItems().setAll(rows);
                                stats.setText("Videos: " + v + " | Detections: " + d + " | Events: " + ev);
                                evArea.clear();
                                evArea.appendText("Deleted detection #" + detId + "\n");
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> {
                                evArea.appendText("Delete error: " + ex + "\n");
                            });
                        } finally {
                            Platform.runLater(() -> {
                                del.setDisable(false);
                            });
                        }
                    });
                } catch (NumberFormatException ignore) {/* no-op */}
            }
        });

        rep.getChildren().addAll(hdr, new HBox(8, refresh, showEv, del), list, stats, evArea);
        tabs.getTabs().add(new Tab("Reports", rep));

        // авто-подгрузка при открытии
        Platform.runLater(refresh::fire);

        // Cameras (stubs)
        GridPane cams = new GridPane();
        cams.setPadding(new Insets(12));
        cams.setHgap(10);
        cams.setVgap(10);
        for (int i = 1; i <= 4; i++) {
            Label l = new Label("Camera " + i + " -preview");
            l.setStyle("-fx-border-color: #888; -fx-min-width: 320; -fx-min-height: 180; -fx-alignment: center;");
            cams.add(l, (i-1)%2, (i-1)/2);
        }
        tabs.getTabs().add(new Tab("Cameras", cams));


        root.setCenter(tabs);

        // Initialization stubs
        Pg.init();
        exec.submit(new CameraWorker(1, "DEMO-1"));
        exec.submit(new CameraWorker(2, "DEMO-2"));
    }

    private static String fmtMs(long ms) {
        long s = ms / 1000;
        long h = s  / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        long msPart = ms % 1000;
        return String.format("%02d:%02d:%02d.%03d", h, m, sec, msPart);
    }

    public Pane getRoot() {
        return root;
    }
}
