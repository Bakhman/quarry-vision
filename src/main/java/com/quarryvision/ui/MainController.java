package com.quarryvision.ui;

import com.quarryvision.app.Config;
import com.quarryvision.core.db.*;
import com.quarryvision.core.detection.DetectionResult;
import com.quarryvision.core.importer.IngestProcessor;
import com.quarryvision.core.importer.UsbIngestService;
import com.quarryvision.core.queue.DetectionQueueService;
import com.quarryvision.core.queue.QueueTask;
import com.quarryvision.core.video.CameraWorker;
import com.quarryvision.core.detection.BucketDetector;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.HPos;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.bytedeco.opencv.opencv_core.Size;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;
import org.opencv.core.Core;


import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// простая сцена/вкладки
public class MainController {
    private final BorderPane root = new BorderPane();
    private final ExecutorService exec = Executors.newFixedThreadPool(4);
    private final Config cfg = Config.load();
    private Runnable reportsReload = null;
    private final Map<Integer, CameraWorker> camWorkers = new ConcurrentHashMap<>();
    private boolean camAutoStarted = false;
    private Timeline camAutoRefresh;


    public MainController() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Import
        tabs.getTabs().add(buildImportTab());
        // Queue
        tabs.getTabs().add(buildQueueTab());
        // Reports
        tabs.getTabs().add(buildReportsTab());
        // Heatmap
        tabs.getTabs().add(buildHeatMapTab());
        // Cameras
        tabs.getTabs().add(buildCamerasTab(tabs));

        root.setCenter(tabs);
    }

    private Button getButton(TextField detectPath, TextArea log) {
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
                            dc.cooldownFrames(), dc.minChangedPixels(), new Size(dc.morphW(), dc.morphH()), resolveMergeMs(cfg),
                            dc.emaAlpha(), dc.thrLowFactor(), dc.minActiveMs(), dc.nmsWindowMs(), dc.trace());
                    var res = det.detect(p);
                    int videoId = Pg.upsertVideo(p, res.fps(), res.frames());
                    int detId = Pg.insertDetection(videoId, det.effectiveMergeMs(), res.timestampsMs());
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
        return detectBtn;
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

    // Проверка доступности видеопотока: пытается открыть cap в пределах timeoutM
    private static boolean validateCamera(String url, int timeoutMs) {
        try {
            // загрузка нативной библиотеки OpenCV (безопасно вызывать многократно)
            try {
                System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
            } catch (UnsatisfiedLinkError ignore) {}

            if (url.startsWith("http") && (url.contains("youtube.com") || url.contains("youtu.be"))) return false;

            long deadline = System.currentTimeMillis() + Math.max(500, timeoutMs);
            // локальный файл: сначала конструктор (часто стабильнее на Windows)
            Path p = Path.of(url);
            if (Files.isRegularFile(p)) {
                try(VideoCapture capStore = new VideoCapture(p.toString())){
                    if (capStore.isOpened()) return true;
                }
            }

            // общий путь: попытки открыть до таймаута
            try (VideoCapture cap = new VideoCapture()) {
                boolean opened = false;
                // некоторые backend’ы открываются не мгновенно. Подождём до таймаута.
                while (System.currentTimeMillis() < deadline) {
                    opened = cap.open(url);
                    if (opened || Thread.currentThread().isInterrupted()) break;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return opened; // true если открыли, иначе false
            }
        } catch (Throwable t) {
            return false;
        }
    }

    // Маскируем креды в rtsp/http url: user:***@host
    private static String maskUrl(String url) {
        if (url == null) return "";
        int at = url.indexOf('@');
        int proto = url.indexOf("://");
        if (proto > 0 && at > proto) {
            int credsStart = proto + 3;
            String creds = url.substring(credsStart, at);
            if (creds.contains(":")) {
                return url.substring(0, credsStart) + "*****@" + url.substring(at + 1);
            }
        }
        return url;
    }

    private Tab buildImportTab() {
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
                            resolveMergeMs(cfg),
                            dc.emaAlpha(),
                            dc.thrLowFactor(),
                            dc.minActiveMs(),
                            dc.nmsWindowMs(),
                            dc.trace()
                    );
                    var res = det.detect(p);
                    Platform.runLater(() -> {
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
                    Platform.runLater(() -> log.appendText("Detect error: " + ex + "\n"));
                } finally {
                    Platform.runLater(() -> detect.setDisable(false));
                }
            });
        });

        // Detect UI: поле и кнопка
        TextField detectPath = new TextField();
        detectPath.setPromptText("E:/INBOX/locs.mp4");
        Button detectBtn = getButton(detectPath, log);

        importBox.getChildren().addAll(
                new Label("Import video"),
                path,
                scan,
                detect,
                log,
                new Separator(),
                new Label("Detect video"),
                detectPath,
                detectBtn
        );
        return new Tab("Import", importBox);
    }

    private Tab buildQueueTab() {
        // Queue — рабочая вкладка очереди обработки
        // Назначение: управлять обработкой видео в одном фоновом потоке,
        // видеть статус и прогресс. Сейчас процессор — симулятор (10 шагов по 10%).
        // Далее подменим на реальную детекцию + запись в БД.
        VBox q = new VBox(10);
        q.setPadding(new Insets(12));
        Label qHdr = new Label("Processing queue");
        Button qAdd = new Button("Add videos");
        Button qStart = new Button("Start");
        Button qStop = new Button("Stop");
        Button qCancel = new Button("Cancel selected");
        Button qClear = new Button("Clear finished");
        TextArea qLog = new TextArea();
        qLog.setEditable(false);
        qLog.setPrefRowCount(8);

        // Очередь: движок и модель задачи
        DetectionQueueService queue = new DetectionQueueService();

        // Таблица задач: id | path | status | progress
        TableView<QueueTask> qTable = new TableView<>();
        qTable.setPrefHeight(260);
        TableColumn<QueueTask, String> qcId = new TableColumn<>("ID");
        qcId.setCellValueFactory(c -> new ReadOnlyStringWrapper(Integer.toString(c.getValue().id)));
        TableColumn<QueueTask, String> qcPath = new TableColumn<>("Video");
        qcPath.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().video != null ? c.getValue().toString() : ""));
        TableColumn<QueueTask, String> qcStatus = new TableColumn<>("Status");
        qcStatus.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().status.toString()));
        TableColumn<QueueTask, ProgressBar> qcProg = new TableColumn<>("Progress");
        qcProg.setCellValueFactory(c -> {
            ProgressBar bar = new ProgressBar();
            bar.setPrefWidth(120);
            bar.setProgress(Math.max(0, Math.min(100, c.getValue().progress)) / 100.0);
            return new ReadOnlyObjectWrapper<>(bar);
        });
        qTable.getColumns().addAll(qcId, qcPath, qcStatus, qcProg);

        // Данные таблицы (перерисовываем по событиям очереди)
        var qItems = FXCollections.<QueueTask>observableArrayList();
        qTable.setItems(qItems);

        // Подписка: любое изменение задачи → обновление UI
        queue.addListener(task -> Platform.runLater(() -> {
            if (!qItems.contains(task)) qItems.add(task);
            qTable.refresh();
            qLog.appendText("Task #" + task.id + " " + task.status + " " + task.progress + "% " + task.message + "\n");
        }));

        // Добавить видео
        qAdd.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Chose videos");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.avi", "*.*"));
            var win = q.getScene() != null ? q.getScene().getWindow() : null;
            var files = fc.showOpenMultipleDialog(win);
            if (files != null) {
                for (var f : files) {
                    var t = queue.enqueue(Path.of(f.getAbsolutePath()));
                    qItems.add(t);
                }
                qTable.refresh();
            }
        });

        // Старт: реальная обработка — детектор + запись результата в БД
        qStart.setOnAction(e -> {
            // Processor вызывается воркером для каждого видео из очереди.
            DetectionQueueService.Processor processor = ((video, onProgress) -> {
                // 1) Загружаем конфиг и создаём детектор
                var cfg = Config.load();
                BucketDetector det = new BucketDetector(cfg);

                // 2) Запускаем детекцию
                onProgress.accept(10);
                DetectionResult dr = det.detect(video);
                onProgress.accept(70);

                // 3) Создаём/обновляем запись видео с реальными fps и frames
                int videoId = Pg.upsertVideo(video, dr.fps(), dr.frames());
                onProgress.accept(85);

                // 4) mergeMs — с учётом системного свойства (-Dqv.mergeMs)
                int mergeMs = det.effectiveMergeMs();

                // 5) Сохраняем детекцию и события
                int detId = Pg.insertDetection(videoId, mergeMs, dr.timestampsMs());
                onProgress.accept(100);

                // 6) Обновляем лог и Reports
                Platform.runLater(() -> {
                    qLog.appendText(
                            "Saved detection #" + detId +
                                    " videoId=" + videoId +
                                    " events=" + dr.events() +
                                    " fps=" + dr.fps() +
                                    " frames=" +dr.frames() + "\n");
                    if (reportsReload != null) reportsReload.run();
                });
            });
            queue.start(processor);
            qLog.appendText("Queue started\n");
        });

        // Мягкая остановка после текущей задачи
        qStop.setOnAction(e -> {
            queue.stop();
            qLog.appendText("Queue stop requested\n");
        });

        // Отмена выбранной (если ещё не RUNNING)
        qCancel.setOnAction(e -> {
            var sel = qTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            boolean ok = queue.cancel(sel);
            qLog.appendText(ok ? ("Canceled #" + sel.id + "\n") : ("Cannot cancel #" + sel.id +"\n"));
            qTable.refresh();
        });

        // Очистить завершённые (DONE/FAILED/CANCELED)
        qClear.setOnAction(e -> {
            qItems.removeIf(t ->
                    switch (t.status) {
                        case DONE, FAILED, CANCELED -> true;
                        default -> false;
                    });
            qTable.refresh();
        });

        q.getChildren().addAll(
                qHdr,
                new HBox(8, qAdd, qStart, qStop, qCancel, qClear),
                qTable, qLog
        );

        return new Tab("Queue", q);
    }

    private Tab buildReportsTab() {
        // Reports
        VBox rep = new VBox(10);
        rep.setPadding(new Insets(12));
        Label hdr = new Label("Detections history");
        ListView<String> list = new ListView<>();
        list.setPrefHeight(260);
        TextField filterText = new TextField();
        filterText.setPromptText("Filter: id, path, merge, date... (Ctrl+F)");
        Button refreshBtn = new Button("Refresh");
        Button showEv = new Button("Show events");
        Button del = new Button("Delete detection");
        Button openFolder = new Button("Open video folder");
        Button exportCsv = new Button("Export CSV");
        Button exportEvents = new Button("Export events");

        // пагинация
        Button prev = new Button("Prev");
        Button next = new Button("Next");
        TextField pageSizeTf = new TextField("50");
        pageSizeTf.setPrefWidth(60);
        Label pageInfo = new Label();

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

        // агрегаты по merge_ms
        TableView<DbMergeAgg> byMerge = new TableView<>();
        byMerge.setPrefHeight(160);
        TableColumn<DbMergeAgg, String> mVal = new TableColumn<>("merge_ms");
        mVal.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Integer.toString(cd.getValue().mergeMs)));
        TableColumn<DbMergeAgg, String> mDet = new TableColumn<>("Detections");
        mDet.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Long.toString(cd.getValue().detections)));
        TableColumn<DbMergeAgg, String> mEv = new TableColumn<>("Events");
        mEv.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Long.toString(cd.getValue().events)));
        byMerge.getColumns().addAll(mVal, mDet, mEv);

        TextArea evArea = new TextArea();
        evArea.setEditable(false);
        evArea.setPrefRowCount(10);
        Label stats = new Label(); // Videos | Detections | Events

        // список и фильтрация
        final ObservableList<String> master = FXCollections.observableArrayList();
        final FilteredList<String> filtered = new FilteredList<>(master, s -> true);
        list.setItems(filtered);

        filterText.textProperty().addListener((obs, o, n) -> {
            String query = n ==null ? "" : n.trim().toLowerCase();
            if (query.isEmpty()) {
                filtered.setPredicate(s -> true);
            } else {
                filtered.setPredicate(s -> s.toLowerCase().contains(query));
            }
        });

        // Ctrl+F фокус в фильтре
        rep.setOnKeyPressed(k -> {
            switch (k.getCode()) {
                case F: if (k.isControlDown()) filterText.requestFocus(); break;
            }
        });
        // состояние пагинации
        final int[] page = {0};
        final int[] pageSize = {50};

        Runnable loadPage = () -> {
            refreshBtn.setDisable(true);
            evArea.clear();
            exec.submit(() -> {
                int sz;
                try {
                    sz = Math.max(1, Integer.parseInt(pageSizeTf.getText().trim()));
                } catch (Exception ignore) {
                    sz = pageSize[0]; pageSizeTf.setText(Integer.toString(sz));
                }
                pageSize[0] = sz;
                int offset = page[0] * pageSize[0];
                List<DbDetectionRow> rows = Pg.listDetections(pageSize[0], offset);
                try {
                    if (rows.isEmpty() && page[0] > 0) {
                        // откат на предыдущую страницу
                        page[0]--;
                        offset = page[0] * pageSize[0];
                        rows = Pg.listDetections(pageSize[0], page[0] * pageSize[0]);
                    }
                    // offset ТОЛЬКО внутри этой лямбды
                    final int effectiveOffset = offset;
                    final List<DbDetectionRow> pageRows = rows; // <— финализируем для lambda
                    final long v = Pg.countVideos();
                    final long d = Pg.countDetections();
                    final long ev = Pg.countEvents();
                    final var agg = Pg.listDailyAgg(30);
                    final var top = Pg.listVideoAgg(20);
                    final var merges = Pg.listMergeAgg();

                    Platform.runLater(() -> {
                        List<String> formatted = pageRows.stream()
                                        .map(DetectionRowFormat::formatDetectionRow)
                                        .toList();
                        master.setAll(formatted);
                        stats.setText("Videos: " + v + " | Detections: " + d + " | Events: " + ev);
                        int from = pageRows.isEmpty() ? 0 : effectiveOffset + 1;
                        int to = effectiveOffset + pageRows.size();
                        pageInfo.setText("Showing " + from + "-" + to + " of " + d);
                        prev.setDisable(page[0] == 0);
                        next.setDisable(to >= d);
                        daily.setItems(FXCollections.observableArrayList(agg));
                        byVideo.setItems(FXCollections.observableArrayList(top));
                        byMerge.setItems(FXCollections.observableArrayList(merges));
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        evArea.appendText("Load error: " + ex + "\n");
                    });
                } finally {
                    Platform.runLater(() -> refreshBtn.setDisable(false));
                }
            });
        };

        refreshBtn.setOnAction(e -> {
            page[0] = 0;
            loadPage.run();
        });
        prev.setOnAction(e -> {
            if (page[0] > 0) {
                page[0]--;
                loadPage.run();
            }
        });
        next.setOnAction(e -> {
            page[0]++;
            loadPage.run();
        });
        pageSizeTf.setOnAction(e -> {
            page[0] = 0;
            loadPage.run();
        });

        // авто-подгрузка при открытии
        Platform.runLater(() -> loadPage.run());
        this.reportsReload = () -> Platform.runLater(loadPage);
        Platform.runLater(loadPage);

        showEv.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            Integer detId = DetectionRowFormat.parseDetectionId(sel);
            if (detId == null) {
                return;
            }
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
        });

        del.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            Integer detId = DetectionRowFormat.parseDetectionId(sel);
            if (detId == null) {
                return;
            }
            del.setDisable(true);
            exec.submit(() -> {
                try {
                    Pg.deleteDetection(detId);
                    // после удаления — перезагрузить список и статистику
                    List<DbDetectionRow> rows = Pg.listDetections(50);
                    long v = Pg.countVideos();
                    long d = Pg.countDetections();
                    long ev = Pg.countEvents();
                    Platform.runLater(() -> {
                        var formatted = rows.stream()
                                        .map(DetectionRowFormat::formatDetectionRow)
                                        .toList();
                        master.setAll(formatted);
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

        openFolder.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            Integer detId = DetectionRowFormat.parseDetectionId(sel);
            if (detId == null) {
                return;
            }
            openFolder.setDisable(true);
            exec.submit(() -> {
                try {
                    String p = Pg.findVideoPathByDetection(detId);
                    if (p == null || p.isBlank()) {
                        Platform.runLater(() -> evArea.appendText("Path not found for detection #" + detId + "\n"));
                        return;
                    }
                    File f = new File(p);
                    File dir = f.getParentFile() != null ? f.getParentFile() : f;
                    String os = System.getProperty("os.name", "").toLowerCase();
                    boolean handled = false;
                    // Windows: открыть проводник и подсветить файл если есть
                    if (os.contains("win")) {
                        if (f.isFile()) {
                            new ProcessBuilder("explorer", "/select,", f.getAbsolutePath()).start();
                        } else {
                            new ProcessBuilder("explorer", dir.getAbsolutePath()).start();
                        }
                        handled = true;
                    }
                   // macOS: показать в Finder
                   if (!handled && os.contains("mac")) {
                       if (f.isFile()) {
                           new ProcessBuilder("open", "-R", f.getAbsolutePath()).start();
                       } else {
                           new ProcessBuilder("open", dir.getAbsolutePath()).start();
                       }
                        handled = true;
                    }
                    // Linux/прочее: Desktop.open папки
                    if(!handled) {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(dir);
                            handled = true;
                        }
                    }
                    if (handled) {
                        Platform.runLater(() -> evArea.appendText("Opened: " + dir.getAbsolutePath() + "\n"));
                    } else {
                        Platform.runLater(() -> evArea.appendText("Open folder not supported on this platform\n"));
                    }
                } catch (Exception ex) {
                    Platform.runLater(() -> evArea.appendText("Open folder error: " + ex + "\n"));
                } finally {
                    Platform.runLater(() -> openFolder.setDisable(false));
                }
            });
        });

        exportEvents.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            Integer detId = DetectionRowFormat.parseDetectionId(sel);
            if (detId == null) {
                return;
            }
            exportEvents.setDisable(true);
            exec.submit(() -> {
                try {
                    var stamps = Pg.listEventsMs(detId);
                    String vpath = Pg.findVideoPathByDetection(detId);
                    StringBuilder sb = new StringBuilder();
                    sb.append("Detection,").append(detId).append("\r\n");
                    if (vpath != null) sb.append("Video,\"").append(vpath.replace("\"","\"\"")).append("\"\r\n");
                    sb.append("\r\n#;t_ms;time\n");
                    for (int i =0; i < stamps.size(); i++) {
                        long t = stamps.get(i);
                        sb.append(i + 1).append(';').append(t).append(';').append(fmtMs(t)).append("\r\n");
                    }
                    String content = sb.toString();
                    Platform.runLater(() -> {
                        try {
                            FileChooser fc = new FileChooser();
                            fc.setTitle("Save Events CSV");
                            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
                            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                            fc.setInitialFileName("qv-events-" + detId + "-" + ts + ".csv");
                            var win = rep.getScene() != null ? rep.getScene().getWindow() : null;
                            var f = fc.showSaveDialog(win);
                            if (f != null) {
                                Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
                                evArea.appendText("Exported events CSV: " + f.getAbsolutePath() + "\n");
                            } else {
                                evArea.appendText("Export canceled\n");
                            }
                        } catch (IOException io) {
                            evArea.appendText("Export error: " + io + "\n");
                        } finally {
                            exportEvents.setDisable(false);
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        evArea.appendText("Export error: " + ex + "\n");
                        exportEvents.setDisable(false);
                    });
                }
            });

        });

        rep.getChildren().addAll(
                hdr,
                new HBox(8, refreshBtn, showEv, del, openFolder, exportCsv, exportEvents),
                new HBox(8, new Label("Page size:"), pageSizeTf, prev, next, pageInfo),
                filterText, list, stats, daily, byVideo, byMerge, evArea);
        return new Tab("Reports", rep);
    }

    private Tab buildHeatMapTab() {
        VBox heatPane = new VBox(10);
        heatPane.setPadding(new Insets(12));
        Label heatHdr = new Label("Week heatmap (by Events)");
        Button refreshHeat = new Button("Refresh");
        TextArea evArea = new TextArea();
        evArea.setEditable(false);
        evArea.setPrefRowCount(10);

        TableView<DbWeekAgg> byWeek = new TableView<>();
        byWeek.setPrefHeight(220);
        TableColumn<DbWeekAgg, String> wDay = new TableColumn<>("Weekday");
        wDay.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().day.toString()));
        TableColumn<DbWeekAgg, String> wDet = new TableColumn<>("Detections");
        wDet.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Long.toString(cd.getValue().detections)));
        TableColumn<DbWeekAgg, String> wEv = new TableColumn<>("Events");
        wEv.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Long.toString(cd.getValue().events)));
        byWeek.getColumns().addAll(wDay, wDet, wEv);

        GridPane heat = new GridPane();
        heat.setHgap(8);
        heat.setVgap(4);
        final Region[] bars = new Region[7];
        final Label[] lbls = new Label[7];
        for (int i = 0; i < 7; i++) {
            Label d = new Label(DayOfWeek.of(i+1).toString());
            Region bar = new Region();
            bar.setMinHeight(18);
            bar.setPrefHeight(18);
            Label val = new Label("0");
            bars[i] = bar;
            lbls[i] = val;
            heat.add(d, 0, i);
            heat.add(bar, 1, i);
            heat.add(val, 2, i);
            GridPane.setHalignment(val, HPos.RIGHT);
            bar.setStyle("-fx-background-color: rgb(230,230,230); -fx-border-color: #ccc; -fx-border-radius: 3; -fx-background-radius: 3;");
        }

        refreshHeat.setOnAction(e -> {
            refreshHeat.setDisable(true);
            exec.submit(() -> {
                try {
                    var week = Pg.listWeekAgg();
                    Platform.runLater(() -> {
                        byWeek.setItems(FXCollections.observableArrayList(week));
                        long maxEv = week.stream().mapToLong(w -> w.events).max().orElse(1L);
                        for(int i =0; i < 7; i++) {
                            long evv = week.get(i).events;
                            double r = maxEv == 0 ? 0.0 : (double) evv / (double) maxEv;
                            int c = 230 - (int)Math.round(r * 180);
                            int width = 40 + (int)Math.round(r * 240);
                            bars[i].setPrefWidth(width);
                            bars[i].setStyle(String.format("-fx-background-color: rgb(%d,%d,%d); -fx-border-color: #ccc; -fx-border-radius: 3; -fx-background-radius: 3;", c, 240 - (230-c)/2, 255));
                            lbls[i].setText(Long.toString(evv));
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        evArea.appendText("Heatmap load error: " + ex + "\n");
                    });
                } finally {
                    Platform.runLater(() -> refreshHeat.setDisable(false));
                }
            });
        });

        heatPane.getChildren().addAll(heatHdr, refreshHeat, heat, byWeek);
        Platform.runLater(refreshHeat::fire);
        return new Tab("Heatmap", heatPane);
    }

    private Tab buildCamerasTab(final TabPane tabs) {
        // Cameras
        VBox camPane = new VBox(10);
        camPane.setPadding(new Insets(12));
        Button camRefresh = new Button("Refresh");
        Button camAdd = new Button("Add");
        Button camDel = new Button("Delete");
        Button camEdit = new Button("Edit");
        Button camToggle = new Button("Enable/Disable");
        Button camStart = new Button("Start");
        Button camStop = new Button("Stop");

        TableView<DbCamera> camTable = new TableView<>();
        TextArea camLog = new TextArea();
        camLog.setEditable(false);
        camLog.setPrefRowCount(7);
        camTable.setPrefHeight(260);

        TableColumn<DbCamera,String> cId = new TableColumn<>("ID");
        cId.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Integer.toString(cd.getValue().id())));
        TableColumn<DbCamera,String> cName = new TableColumn<>("Name");
        cName.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().name()));
        TableColumn<DbCamera,String> cUrl = new TableColumn<>("URL");
        cUrl.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().url()));
        TableColumn<DbCamera,String> cAct = new TableColumn<>("Active");
        cAct.setCellValueFactory(cd -> new ReadOnlyStringWrapper(Boolean.toString(cd.getValue().active())));
        TableColumn<DbCamera,String> cState = new TableColumn<>("State");
        cState.setCellValueFactory(cd -> new ReadOnlyStringWrapper(camWorkers
                .containsKey(cd.getValue().id()) ? "RUNNING" : "STOPPED"));
        TableColumn<DbCamera,String> cSeen = new TableColumn<>("Last seen");
        cSeen.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue()
                .lastSeenAt() == null ? "" : cd.getValue().lastSeenAt().toString().replace('T', ' ')));
        TableColumn<DbCamera,String> cErr = new TableColumn<>("Last error");
        cErr.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue()
                .lastError() == null ? "" : cd.getValue().lastError()));
        camTable.getColumns().addAll(cId,cName,cUrl,cAct,cState,cSeen,cErr);

        ObservableList<DbCamera> camItems = FXCollections.observableArrayList();
        camTable.setItems(camItems);

        // Вспомогательный апдейтер доступности кнопок Start/Stop
        Runnable updateCamButtons = () -> {
            DbCamera sel = camTable.getSelectionModel().getSelectedItem();
            boolean hasSel = sel != null;
            boolean running = hasSel && camWorkers.containsKey(sel.id());
            camStart.setDisable(!hasSel || running);
            camStop.setDisable(!hasSel || !running);
        };
        camTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, o, n) -> updateCamButtons.run());
        updateCamButtons.run();

        // --- Автозапуск всех active=true при старте (с валидацией) ---
        Runnable autostartActive = () -> exec.submit(() -> {
            try {
                for (DbCamera c : Pg.listCameras()) {
                    if (!c.active() || camWorkers.containsKey(c.id())) continue;
                    final DbCamera cam = c;
                    Platform.runLater(() -> {
                        camLog.appendText("[Cameras] Validating " + cam.name() + " → " +
                                maskUrl(cam.url()) +"\n");
                    });
                    boolean ok;
                    try {
                        ok = validateCamera(cam.url(), 3000);
                    } catch (Exception ex) { ok = false;}
                    if (ok) {
                        CameraWorker w = new CameraWorker(cam.id(), cam.name());
                        camWorkers.put(cam.id(), w);
                        Thread t = new Thread(w, "cam-" + cam.id());
                        t.setDaemon(true);
                        t.start();
                        Platform.runLater(() -> {
                            camLog.appendText("[Cameras] Started worker for " + cam.name() + "\n");
                            camTable.refresh();
                            updateCamButtons.run();
                        });
                    } else {
                        Platform.runLater(() -> {
                            camLog.appendText("[Cameras] FAIL: " + cam.name() + "\n");
                            camTable.refresh();
                            updateCamButtons.run();
                        });
                    }
                }
            } catch (Exception ex) {
                Platform.runLater(() -> camLog.appendText("Auto-start error: " + ex + "\n"));
            }
        });

        Runnable loadCams = () -> {
            exec.submit(() -> {
                try {
                    List<DbCamera> rows = Pg.listCameras();
                    Platform.runLater(() -> {
                        // сохранить выбор
                        int selIdx = camTable.getSelectionModel().getSelectedIndex();

                        camItems.setAll(rows);

                        // восстановить выбор
                        if (selIdx >= 0 && selIdx < camItems.size()) {
                            camTable.getSelectionModel().select(selIdx);
                            camTable.scrollTo(selIdx);
                        }

                        camTable.refresh();
                        updateCamButtons.run();
                        // автозапуск active-камер только один раз после первой загрузки списка
                        if (!camAutoStarted) {
                            camAutoStarted = true;
                            autostartActive.run();
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                            camLog.appendText("Load cameras error: " + ex + "\n"));
                }
            });
        };
        camRefresh.setOnAction(e -> {
            camLog.clear();
            loadCams.run();
        });

        // авто-рефреш раз в 2с, только когда активна вкладка Cameras
        camAutoRefresh = new Timeline(
                new KeyFrame(Duration.seconds(2), ev -> {
                    Tab tab = tabs.getSelectionModel().getSelectedItem();
                    if (tab != null && "Cameras".equals(tab.getText())) {
                        loadCams.run();
                    }
                })
        );
        camAutoRefresh.setCycleCount(Animation.INDEFINITE);
        camAutoRefresh.play();

        // Add dialog: простые TextInputDialog'и
        camAdd.setOnAction(e -> {
            TextInputDialog dn = new TextInputDialog();
            dn.setHeaderText("Camera name");
            String n = dn.showAndWait().orElse("").trim();
            if (n.isEmpty()) return;
            TextInputDialog du = new TextInputDialog();
            du.setHeaderText("Camera URL (RTSP/HTTP/file)");
            String u = du.showAndWait().orElse("").trim();
            if (u.isEmpty()) return;
            exec.submit(() -> {
                try {
                    Pg.insertCamera(n, u, true);
                    Platform.runLater(loadCams);
                } catch (Exception ex) {
                    Platform.runLater(() -> camLog.appendText("Add camera error: " + ex +"\n"));
                }
            });
        });

        // Delete
        camDel.setOnAction(e -> {
            DbCamera sel = camTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            exec.submit(() -> {
                try {
                    Pg.deleteCamera(sel.id());
                    Platform.runLater(loadCams);
                } catch (Exception ex) {
                    Platform.runLater(() -> camLog.appendText("Delete camera error: " + ex + "\n"));
                }
            });
        });

        // Edit
        camEdit.setOnAction(e -> {
            DbCamera sel = camTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;

            TextInputDialog dn = new TextInputDialog(sel.name());
            dn.setHeaderText("Camera name");
            String name = dn.showAndWait().orElse("").trim();
            if (name.isEmpty()) return;

            TextInputDialog du = new TextInputDialog(sel.url());
            du.setHeaderText("Camera URL (rtsp/http/file");
            String url = du.showAndWait().orElse("").trim();
            if (url.isEmpty()) return;

            // checkbox Active
            CheckBox cb = new CheckBox("Active");
            cb.setSelected(sel.active());
            VBox pane = new VBox(8, new Label("Active:"), cb);
            Dialog<Boolean> dlg = new Dialog<>();
            dlg.setTitle("Active flag");
            dlg.getDialogPane().setContent(pane);
            dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            // Возвращаем выбранное значение чекбокса только при OK
            dlg.setResultConverter(btn -> btn == ButtonType.OK ? cb.isSelected() : null);
            boolean active = dlg.showAndWait().orElse(sel.active());
            exec.submit(() -> {
                try {
                    Pg.editCamera(sel.id(), name, url, active);
                    Platform.runLater(() -> {
                        camLog.appendText("[Cameras] Updated #" + sel.id() + "\n");
                        loadCams.run();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() ->
                            camLog.appendText("Edit camera error: " + ex + "\n"));
                }
            });
        });

        // Toggle active
        camToggle.setOnAction(e -> {
            DbCamera sel = camTable.selectionModelProperty().get().getSelectedItem();
            if (sel == null) return;
            exec.submit(() -> {
                try {
                    Pg.setCameraActive(sel.id(), !sel.active());
                    Platform.runLater(loadCams);
                } catch (Exception ex) {
                    Platform.runLater(() -> camLog.appendText("Toggle camera error: " + ex + "\n"));
                }
            });
        });

        // --- Запуск воркера камеры ---
        camStart.setOnAction(e -> {
            DbCamera sel = camTable.getSelectionModel().getSelectedItem();
            if (sel ==null) return;                       // нет выбранной камеры
            if (camWorkers.containsKey(sel.id())) return; // уже запущена
            camStart.setDisable(true);
            String url = sel.url();
            camLog.appendText("[Cameras] Validating " + sel.name() + " \u2192 " + maskUrl(url) + "\n");
            exec.submit(() -> {
                boolean ok;
                String err = null;
                try {
                    ok = validateCamera(url, 3000); // 3s timeout
                } catch (Exception ex) {
                    ok = false;
                    err = ex.toString();
                }
                final boolean passed = ok;
                final String ferr = err;
                Platform.runLater(() -> {
                    if (passed) {
                        camLog.appendText("[Cameras] OK: " + sel.name() + "\n");
                        try {
                            Pg.setCameraHealth(sel.id(), Instant.now(), null);
                        } catch (Exception ignore) {}
                        CameraWorker w = new CameraWorker(sel.id(), sel.name());
                        camWorkers.put(sel.id(), w);
                        Thread t = new Thread(w, "cam-" + sel.id());
                        t.setDaemon(true);
                        t.start();
                        camLog.appendText("[Cameras] Started worker for " + sel.name() + "\n");
                    } else {
                        camLog.appendText("[Cameras] FAIL: " + sel.name() +
                                (ferr != null ? " __ " + ferr : "") + "\n");
                        try {
                            Pg.setCameraHealth(sel.id(), null,
                                    ferr == null ? "validate failed" : ferr);
                        } catch (Exception ignore) {}
                    }
                    camTable.refresh();
                    updateCamButtons.run();
                });
            });
        });


        // --- Остановка воркера камеры --
        camStop.setOnAction(e -> {
            DbCamera sel = camTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            CameraWorker w = camWorkers.remove(sel.id());
            if (w != null) {
                w.shutdown(); // мягкая остановка цикла run()
                camLog.appendText("[Cameras] Stopped worker for " + sel.name() + "\n");
            }
            camTable.refresh();
            updateCamButtons.run();
        });

        camPane.getChildren().addAll(
                new HBox(8, camRefresh, camAdd, camDel, camEdit, camToggle, camStart, camStop),
                camTable,
                camLog
        );
        Tab tab = new Tab("Cameras", camPane);
        Platform.runLater(loadCams);
        return tab;
    }

    /** Корректная остано вка фоновых работ UI. Вызывать при выходе. */
    public void shutdown() {
        try {
            if (camAutoRefresh != null) camAutoRefresh.stop();
        } catch (Throwable ignore) {

        }
        try {
            exec.shutdownNow();
            exec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (Throwable ignore) {}
    }

    /** Возвращает effective mergeMs: -Dqv.mergeMs приоритетнее YAML. */
    private static int resolveMergeMs(Config cfg) {
        return Integer.getInteger("qv.mergeMs", cfg.detection().mergeMs());
    }
}
