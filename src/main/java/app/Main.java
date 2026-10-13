package app;

import app.controller.MainController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        MainController controller = new MainController(stage);
        Scene scene = new Scene(controller.getRoot(), 1360, 860);
        controller.bindScene(scene);

        // Liquid Glass global stylesheet (scrollbars, list/combo cells, dialogs)
        var cssUrl = Main.class.getResource("/glass.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());

        stage.setTitle("Wi-Fi Heatmap");
        stage.setScene(scene);
        // 툴바는 약 1,100px 이 필요하다 — 그보다 좁아지면 버튼이 '>>' 뒤로 숨는다.
        // 1280×760 은 흔한 프로젝터 해상도에서도 들어가는 최소 크기.
        stage.setMinWidth(1280);
        stage.setMinHeight(760);
        stage.setMaximized(true);
        stage.show();

        controller.afterShown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}