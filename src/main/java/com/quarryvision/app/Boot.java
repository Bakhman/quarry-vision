package com.quarryvision.app;
:g:::

import com.quarryvision.ui.MainController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

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
    }

    public static void main(String[] args) {
        launch(args);
    }
}
