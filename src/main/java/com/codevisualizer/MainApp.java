package com.codevisualizer;

import com.codevisualizer.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {
    @Override
    public void start(Stage stage) {
        MainView view = new MainView();

        Scene scene = new Scene(view.getRoot(), 1200, 800);
        stage.setScene(scene);
        stage.setTitle("Code Visualizer");
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}
