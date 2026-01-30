package app.controller;

import app.model.AppState;
import app.model.WifiEnvironment;
import app.ui.MainWindow;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Point2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainController {

    private final Stage stage;

    private final AppState state = new AppState();
    private final WifiEnvironment env = new WifiEnvironment();

    private final MainWindow window;

    private final ViewportController viewportController;

    // floorplan 원본(추후 벽 자동인식/스냅 등에 쓸 수 있음)
    private BufferedImage floorplanBI;

    // (임시) 히트맵 이미지: 다음 단계에서 HeatmapController로 이동
    private WritableImage heatmapImage;

    // (임시) 인터랙티브 상태: 다음 단계에서 각 Tool Controller로 이동
    private final List<Point2D> calibPts = new ArrayList<>();
    private Point2D wallFirstPoint = null;
    private Point2D wallHoverPoint = null;

    public MainController(Stage stage) {
        this.stage = stage;
        this.window = new MainWindow(stage);

        // ViewportController는 CanvasView에서 꺼내서 연결
        this.viewportController = new ViewportController(
                window.getCanvasView().getCanvasSP(),
                window.getCanvasView().getViewportPane(),
                window.getCanvasView().getZoomGroup(),
                window.getCanvasView().getFloorGroup()
        );

        wireUi();
    }

    public Parent getRoot() {
        return window.getRoot();
    }

    public void bindScene(Scene scene) {
        installSceneShortcuts(scene);
    }

    public void afterShown() {
        viewportController.centerViewport();
    }

    private void wireUi() {
        // TopToolbar
        window.getTopToolbar().setOnOpenFloorplan(this::openFloorplan);
        window.getTopToolbar().setOnGenerateHeatmap(() ->
                showInfo("아직 HeatmapController 연결 전입니다.\n다음 단계에서 generateAsync로 붙입니다.")
        );
        window.getTopToolbar().setOnClearHeatmap(() -> {
            heatmapImage = null;
            window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
        });

        window.getTopToolbar().setOnToolChanged(tool -> {
            state.setTool(tool);

            wallFirstPoint = null;
            wallHoverPoint = null;
            if (tool != AppState.Tool.SCALE) calibPts.clear();
            window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
        });

        // LeftPanel: 지금 LeftPanel은 scale만 바인딩 가능하게 만들어둔 상태
        window.getLeftPanel().bind(
                state,
                env,
                this::applyScaleIfReady,
                this::resetScalePoints
        );
    }

    // ====== Scene shortcuts (원래 Main에 있던 Space 팬/줌 shortcut 같은 것들 다음 단계에서 추가) ======
    private void installSceneShortcuts(Scene scene) {
        // 지금은 최소. (Space 팬 같은 건 Viewport/Canvas 이벤트로 붙일 예정)
    }

    // ====== Floorplan open ======
    private void openFloorplan() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));

        File f = fc.showOpenDialog(stage);
        if (f == null) return;

        try {
            floorplanBI = ImageIO.read(f);
            if (floorplanBI == null) throw new IOException("이미지 로드 실패");

            Image fx = SwingFXUtils.toFXImage(floorplanBI, null);
            window.getCanvasView().getBaseImageView().setImage(fx);

            // Canvas도 이미지 크기로 맞춰줌
            window.getCanvasView().getDrawCanvas().setWidth(fx.getWidth());
            window.getCanvasView().getDrawCanvas().setHeight(fx.getHeight());

            heatmapImage = null;
            calibPts.clear();
            wallFirstPoint = null;
            wallHoverPoint = null;

            // Viewport 기준 크기 갱신 + 중앙정렬
            viewportController.setBaseContentSize(fx.getWidth(), fx.getHeight());
            viewportController.setZoom(1.0);
            viewportController.updateViewportSize();
            viewportController.centerViewport();

            window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);

            // 스케일 초기화(선택)
            // state.setScaleMPerPx(Double.NaN); // 필요하면 켜라

        } catch (Exception ex) {
            showError("이미지 로드 실패: " + ex.getMessage());
        }
    }

    // ====== Scale apply/reset (현재 ScaleController 분리 전 임시) ======
    private void applyScaleIfReady() {
        // 아직 ScaleController를 여기 연결 안 했으니, 다음 단계에서 교체할 자리.
        // 지금은 “스케일 값이 이미 세팅되었다고 가정”하는 형태로만 둠.

        if (Double.isNaN(state.getScaleMPerPx())) {
            showError("아직 스케일 계산 로직(ScaleController) 연결 전입니다.\n다음 단계에서 두 점 클릭 선분 방식으로 붙입니다.");
            return;
        }
        showInfo(String.format("스케일 적용됨: 1px = %.5f m", state.getScaleMPerPx()));
    }

    private void resetScalePoints() {
        showInfo("아직 스케일 점/선 관리(ScaleController) 연결 전입니다.\n다음 단계에서 reset이 실제로 동작하도록 붙입니다.");
    }

    // ====== Alerts ======
    private void showError(String msg) {
        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR, msg, javafx.scene.control.ButtonType.OK).showAndWait();
    }

    private void showInfo(String msg) {
        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION, msg, javafx.scene.control.ButtonType.OK).showAndWait();
    }
}