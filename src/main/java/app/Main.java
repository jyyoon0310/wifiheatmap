package app;

import app.controller.MainController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        MainController controller = new MainController(stage);
        Scene scene = new Scene(controller.getRoot(), 1200, 860);
        controller.bindScene(scene);

        stage.setTitle("Wi-Fi Heatmap");
        stage.setScene(scene);
        stage.show();

        controller.afterShown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}