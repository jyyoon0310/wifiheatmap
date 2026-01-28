package app.controller;

import app.model.AppState;
import app.model.WifiEnvironment;
import app.ui.MainWindow;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainController {

    private final Stage stage;
    private final AppState state = new AppState();
    private final WifiEnvironment env = new WifiEnvironment();

    private final MainWindow window;

    private final ViewportController viewportController;
    private final ToolsController toolsController;
    private final HeatmapController heatmapController;

    public MainController(Stage stage) {
        this.stage = stage;
        this.window = new MainWindow(stage);

        // Viewport
        this.viewportController = new ViewportController(
                window.getCanvasSP(),
                window.getViewportPane(),
                window.getZoomGroup(),
                window.getFloorGroup()
        );

        // Heatmap
        this.heatmapController = new HeatmapController(env, state);

        // Tools (scale/wall/ap 라우팅)
        this.toolsController = new ToolsController(env, state);

        wireUi();
    }

    public Parent getRoot() { return window.getRoot(); }

    public void bindScene(Scene scene) {
        window.installSceneShortcuts(scene, viewportController);
    }

    public void afterShown() {
        viewportController.centerViewport();
    }

    private void wireUi() {
        // 1) UI 이벤트 → controller
        window.getTopToolbar().setOnOpenFloorplan(() -> {/* file open -> state/env update */});
        window.getTopToolbar().setOnGenerateHeatmap(() -> heatmapController.generateAsync(window));
        window.getTopToolbar().setOnClearHeatmap(() -> { state.setHeatmap(null); window.getCanvasView().render(env, state); });

        window.getTopToolbar().setOnToolChanged(tool -> {
            state.setTool(tool);
            toolsController.clearInteractive();
            window.getCanvasView().render(env, state);
        });

        // 2) Canvas mouse events → toolsController / viewportController
        window.getCanvasView().bindMouseHandlers(state, toolsController, viewportController, () -> {
            // “변경 발생” 공통 처리
            if (state.getHeatmap() != null) heatmapController.generateAsync(window);
            window.getCanvasView().render(env, state);
        });

        // 3) LeftPanel 값 바인딩(기본재질/기본감쇠/실제거리)
        window.getLeftPanel().bind(state, env, toolsController, heatmapController, window);
    }
}