package com.quarryvision.ui;

import com.quarryvision.core.db.Pg;
import com.quarryvision.core.video.CameraWorker;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// простая сцена/вкладки
public class MainController {
    private final BorderPane root = new BorderPane();
    private final ExecutorService exec = Executors.newFixedThreadPool(4);

    public MainController() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Import
        VBox importBox = new VBox(10);
        importBox.setPadding(new Insets(12));
        TextField path = new TextField();
        path.setPromptText("F:/");
        Button scan = new Button("Сканировать (заглушка)");
        TextArea log = new TextArea();
        log.setEditable(false);
        log.setPrefRowCount(10);
        scan.setOnAction(e -> log.appendText("Сканирую: " + path.getText() + " .../n"));
        importBox.getChildren().addAll(new Label("Import video"), path, scan, log);
        tabs.getTabs().add(new Tab("Import", importBox));

        // Queue
        VBox q = new VBox(10);
        q.setPadding(new Insets(12));
        ProgressBar pb = new ProgressBar(0);
        Button sim = new Button("Симуляция очереди");
        sim.setOnAction(e -> pb.setProgress(Math.min(1.0, pb.getProgress()+0.1)));
        tabs.getTabs().add(new Tab("Queue", q));

        // Reports
        VBox rep = new VBox(10);
        rep.setPadding(new Insets(12));
        rep.getChildren().addAll(new Label("Отчеты появятся позже"));
        tabs.getTabs().add(new Tab("Reports", rep));

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
    public Pane getRoot() {
        return root;
    }
}
