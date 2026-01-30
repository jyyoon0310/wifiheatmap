package app.ui;

import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class MainWindow {

    private final BorderPane root = new BorderPane();

    private final TopToolbar topToolbar = new TopToolbar();
    private final LeftPanel leftPanel;
    private final CanvasView canvasView = new CanvasView();

    private final StackPane centerStack = new StackPane();

    public MainWindow(Stage owner) {
        this.leftPanel = new LeftPanel(owner);

        root.setStyle("-fx-background-color: " + Styles.BG_APP + "; -fx-font-family: 'System'; -fx-text-fill: " + Styles.TEXT_MAIN + ";");

        root.setTop(topToolbar.getNode());
        root.setLeft(leftPanel.getNode());

        centerStack.setStyle("-fx-background-color: " + Styles.BG_APP + ";");
        centerStack.getChildren().add(canvasView.getCanvasSP());
        root.setCenter(centerStack);
    }

    public Parent getRoot() { return root; }

    public TopToolbar getTopToolbar() { return topToolbar; }
    public LeftPanel getLeftPanel() { return leftPanel; }
    public CanvasView getCanvasView() { return canvasView; }

    public StackPane getCenterStack() { return centerStack; }
}