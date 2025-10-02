package com.quarryvision.ui;

import com.quarryvision.app.Config;
import com.quarryvision.core.db.DbDailyAgg;
import com.quarryvision.core.db.DbVideoAgg;
import com.quarryvision.core.db.Pg;
import com.quarryvision.core.importer.IngestProcessor;
import com.quarryvision.core.importer.UsbIngestService;
import com.quarryvision.core.video.CameraWorker;
import com.quarryvision.core.detection.BucketDetector;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.bytedeco.opencv.opencv_core.Size;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;


import javax.imageio.IIOException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
        Button exportCsv = new Button("Export CSV");

        // агрегаты за 30 дней
        TableView<DbDailyAgg> daily = new TableView<>();
        daily.setPrefHeight(200);
        TableColumn<DbDailyAgg, String> cDay = new TableColumn<>("Day");
        cDay.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().day.toString()));
        TableColumn<DbDailyAgg, String> cDet = new TableColumn<>("Detections");
        cDet.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Long.toString(cd.getValue().detections)));
        TableColumn<DbDailyAgg, String> cEv = new TableColumn<>("Events");
        cEv.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Long.toString(cd.getValue().events)));
        daily.getColumns().addAll(cDay, cDet, cEv);

        // агрегаты по видео (топ-20 по недавней активности)
        TableView<DbVideoAgg> byVideo = new TableView<>();
        byVideo.setPrefHeight(220);
        TableColumn<DbVideoAgg, String> vPath = new TableColumn<>("Video path");
        vPath.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().path));
        TableColumn<DbVideoAgg, String> vDet = new TableColumn<>("Detections");
        vDet.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Long.toString(cd.getValue().detections)));
        TableColumn<DbVideoAgg, String> vEv = new TableColumn<>("Events");
        vEv.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Long.toString(cd.getValue().events)));
        byVideo.getColumns().addAll(vPath, vDet, vEv);
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
                    var agg = Pg.listDailyAgg(30);
                    var top = Pg.listVideoAgg(20);
                    Platform.runLater(() -> {
                        list.getItems().setAll(rows);
                        stats.setText("Videos: " + v + " | Detections: " + d + " | Events: " + ev);
                        daily.setItems(FXCollections.observableArrayList(agg));
                        byVideo.setItems(FXCollections.observableArrayList(top));
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

        exportCsv.setOnAction(e -> {
            exportCsv.setDisable(true);
            exec.submit(() -> {
               try {
                   long v = Pg.countVideos();
                   long d = Pg.countDetections();
                   long ev = Pg.countEvents();
                   var agg = Pg.listDailyAgg(365);
                   var top = Pg.listVideoAgg(1000);
                   StringBuilder sb = new StringBuilder();
                   sb.append("Summary\n");
                   sb.append("Videos,Detections,Events\n");
                   sb.append(v).append(',').append(d).append(',').append(ev).append("\r\n\r\n");
                   sb.append("Daily\n");
                   sb.append("Day,Detections,Events\n");
                   for (var a : agg) {
                       sb.append(a.day).append(',').append(a.detections).append(',').append(a.events).append("\r\n");
                   }
                   sb.append("\r\nVideos\n");
                   sb.append("Path,Detections,Events\n");
                   for (var vrow : top) {
                       // экранирование запятых
                       String csvPath = "\"" + vrow.path.replace("\"", "\"\"") + "\"";
                       sb.append(csvPath).append(',').append(vrow.detections).append(',').append(vrow.events).append("\r\n");
                   }
                   String content = sb.toString();
                   Platform.runLater(() -> {
                       try {
                           FileChooser fc = new FileChooser();
                           fc.setTitle("Save Reports CSV");
                           fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));
                           String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                           fc.setInitialFileName("qv-report-" + ts + ".csv");
                           var win = rep.getScene() != null ? rep.getScene().getWindow() : null;
                           var f = fc.showSaveDialog(win);
                           if (f != null) {
                               Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
                               evArea.appendText("Exported CSV: " + f.getAbsolutePath() + "\n");
                           }
                       } catch (IOException io) {
                           evArea.appendText("Export error: " + io + "\n");
                       } finally {
                           exportCsv.setDisable(false);
                       }
                   });
               } catch (Exception ex) {
                   Platform.runLater(() -> {
                       evArea.appendText("Export error: " + ex + "\n");
                       exportCsv.setDisable(false);
                   });
               }
            });
        });

        rep.getChildren().addAll(hdr, new HBox(8, refresh, showEv, del, exportCsv), list, stats, daily, byVideo, evArea);
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
