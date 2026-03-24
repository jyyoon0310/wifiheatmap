package app.ui;

import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class MainWindow {

    private final BorderPane root = new BorderPane();

    private final TopToolbar topToolbar = new TopToolbar();
    private final LeftPanel leftPanel = new LeftPanel();
    private final CanvasView canvasView = new CanvasView();
    private final BottomBar bottomBar = new BottomBar();

    public MainWindow(Stage stage) {
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        Runnable applyRoot = () -> root.setStyle(
                "-fx-background-color:" + Styles.bgApp() + ";" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        applyRoot.run();
        Styles.addThemeListener(applyRoot);

        // Top
        root.setTop(topToolbar.getNode());

        // Left (scroll)
        ScrollPane leftSP = new ScrollPane(leftPanel.getRoot());
        leftSP.setFitToWidth(true);
        leftSP.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftSP.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftSP.setPrefViewportWidth(360);

        // 왼쪽 패널: 미세한 frosted 표면
        Runnable applyLeftSP = () -> leftSP.setStyle(
                "-fx-background-color:rgba(242,242,247,0.92);" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-width:0 0.5 0 0;");
        applyLeftSP.run();
        Styles.addThemeListener(applyLeftSP);
        root.setLeft(leftSP);

        // Center
        BorderPane.setMargin(canvasView.getRoot(), new Insets(0));
        root.setCenter(canvasView.getRoot());

        // Bottom
        root.setBottom(bottomBar.getNode());
    }

    public Parent getRoot() { return root; }

    public TopToolbar getTopToolbar() { return topToolbar; }

    public LeftPanel getLeftPanel() { return leftPanel; }

    public CanvasView getCanvasView() { return canvasView; }

    public BottomBar getBottomBar() { return bottomBar; }
}
