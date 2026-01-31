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

    public MainWindow(Stage stage) {
        root.setStyle("-fx-background-color: " + Styles.BG_APP + ";");

        // Top
        root.setTop(topToolbar.getNode());

        // Left (scroll)
        ScrollPane leftSP = new ScrollPane(leftPanel.getRoot());
        leftSP.setFitToWidth(true);
        leftSP.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftSP.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftSP.setPrefViewportWidth(360);

        leftSP.setStyle(
                "-fx-background: " + Styles.BG_PANEL + ";" +
                        "-fx-background-color: " + Styles.BG_PANEL + ";" +
                        "-fx-border-color: " + Styles.BORDER_SOFT + ";" +
                        "-fx-border-width: 0 1 0 0;"
        );
        root.setLeft(leftSP);

        // Center
        BorderPane.setMargin(canvasView.getRoot(), new Insets(0));
        root.setCenter(canvasView.getRoot());
    }

    public Parent getRoot() { return root; }

    public TopToolbar getTopToolbar() { return topToolbar; }

    public LeftPanel getLeftPanel() { return leftPanel; }

    public CanvasView getCanvasView() { return canvasView; }
}