package app.controller;

import app.model.AP;
import app.model.AppState;
import app.model.Band;
import app.model.Wall;
import app.model.WallMaterial;
import app.model.WifiEnvironment;
import app.ui.MainWindow;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Point2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
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

    private BufferedImage floorplanBI;

    private WritableImage heatmapImage;

    // SCALE 선분(확정된 2점)
    private final List<Point2D> calibPts = new ArrayList<>();

    // WALL/SCALE 프리뷰 (첫 점 + 마우스 위치)
    private Point2D wallFirstPoint = null;
    private Point2D wallHoverPoint = null;

    public MainController(Stage stage) {
        this.stage = stage;
        this.window = new MainWindow(stage);

        this.viewportController = new ViewportController(
                window.getCanvasView().getCanvasSP(),
                window.getCanvasView().getViewportPane(),
                window.getCanvasView().getZoomGroup(),
                window.getCanvasView().getFloorGroup()
        );

        // ✅ 시작은 VIEW
        state.setTool(AppState.Tool.VIEW);

        wireUi();
    }

    public Parent getRoot() { return window.getRoot(); }

    public void bindScene(Scene scene) {
        installSceneShortcuts(scene);
    }

    public void afterShown() {
        viewportController.centerViewport();
    }

    private void wireUi() {
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

            // 모드 들어갈 때 프리뷰 정리
            wallFirstPoint = null;
            wallHoverPoint = null;

            if (tool == AppState.Tool.SCALE) {
                // SCALE 시작 시 확정선도 초기화(원하면 유지 가능. 지금은 혼동 방지)
                calibPts.clear();
            }

            // VIEW로 들어가면 토글 상태도 정리(안정성)
            if (tool == AppState.Tool.VIEW) {
                try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}
            }

            window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
        });

        window.getLeftPanel().bind(
                state,
                env,
                this::applyScaleIfReady,
                this::resetScalePoints
        );

        installCanvasHandlers();

        window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
    }

    private void installSceneShortcuts(Scene scene) {
        // 아직 비움
    }

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

            window.getCanvasView().getDrawCanvas().setWidth(fx.getWidth());
            window.getCanvasView().getDrawCanvas().setHeight(fx.getHeight());

            heatmapImage = null;
            calibPts.clear();
            wallFirstPoint = null;
            wallHoverPoint = null;

            viewportController.setBaseContentSize(fx.getWidth(), fx.getHeight());
            viewportController.setZoom(1.0);
            viewportController.updateViewportSize();
            viewportController.centerViewport();

            // ✅ 파일 열면 VIEW + 토글 해제
            state.setTool(AppState.Tool.VIEW);
            try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}

            window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);

        } catch (Exception ex) {
            showError("이미지 로드 실패: " + ex.getMessage());
        }
    }

    // ====== Scale apply/reset ======
    private void applyScaleIfReady() {
        if (calibPts.size() != 2) {
            showError("두 점을 먼저 클릭하세요.");
            return;
        }
        double dPx = calibPts.get(0).distance(calibPts.get(1));
        double realM = safeGetCalibRealMeters();
        if (dPx <= 0 || realM <= 0) {
            showError("거리 값이 올바르지 않습니다.");
            return;
        }
        state.setScaleMPerPx(realM / dPx);
        showInfo(String.format("스케일 적용됨: 1px = %.5f m", state.getScaleMPerPx()));

        // 적용 후 VIEW로 복귀 + 토글 해제 (요구사항)
        state.setTool(AppState.Tool.VIEW);
        try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}

        window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
    }

    private void resetScalePoints() {
        calibPts.clear();
        state.setScaleMPerPx(Double.NaN);
        wallFirstPoint = null;
        wallHoverPoint = null;

        // 리셋은 그냥 VIEW로 복귀해도 UX 좋음
        state.setTool(AppState.Tool.VIEW);
        try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}

        window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
    }

    private void installCanvasHandlers() {
        var canvas = window.getCanvasView().getDrawCanvas();
        var sp = window.getCanvasView().getCanvasSP();

        canvas.setOnMouseClicked(e -> {
            double x = e.getX();
            double y = e.getY();

            if (e.getButton() == MouseButton.SECONDARY) return;

            switch (state.getTool()) {
                case SCALE -> {
                    // ✅ 1클릭 시작 / 2클릭 확정
                    if (wallFirstPoint == null) {
                        wallFirstPoint = new Point2D(x, y);
                        wallHoverPoint = wallFirstPoint;
                        calibPts.clear();
                        window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
                        return;
                    }

                    Point2D end = new Point2D(x, y);
                    if (wallFirstPoint.distance(end) >= 3.0) {
                        calibPts.clear();
                        calibPts.add(wallFirstPoint);
                        calibPts.add(end);

                        double dPx = wallFirstPoint.distance(end);
                        double realM = safeGetCalibRealMeters();
                        if (dPx > 0 && realM > 0) {
                            state.setScaleMPerPx(realM / dPx);
                        }
                    }

                    // ✅ 완료 후 VIEW 복귀 + 토글 해제
                    wallFirstPoint = null;
                    wallHoverPoint = null;
                    state.setTool(AppState.Tool.VIEW);
                    try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}

                    window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
                }
                case AP -> {
                    addApAt(x, y);
                    window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
                }
                case WALL -> {
                    if (wallFirstPoint == null) {
                        wallFirstPoint = new Point2D(x, y);
                        wallHoverPoint = wallFirstPoint;
                        window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
                        return;
                    }

                    Point2D end = new Point2D(x, y);
                    if (wallFirstPoint.distance(end) >= 3.0) {
                        Wall w = new Wall();
                        w.x1 = wallFirstPoint.getX();
                        w.y1 = wallFirstPoint.getY();
                        w.x2 = end.getX();
                        w.y2 = end.getY();

                        w.setMaterial(WallMaterial.CONCRETE_WALL);
                        env.getWalls().add(w);
                    }

                    wallFirstPoint = null;
                    wallHoverPoint = null;
                    window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
                }
                default -> {
                    // VIEW
                }
            }
        });

        canvas.setOnMouseMoved(e -> {
            if ((state.getTool() == AppState.Tool.WALL || state.getTool() == AppState.Tool.SCALE) && wallFirstPoint != null) {
                wallHoverPoint = new Point2D(e.getX(), e.getY());
                window.getCanvasView().render(env, state, heatmapImage, calibPts, wallFirstPoint, wallHoverPoint);
            }
        });

        sp.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() || e.isShortcutDown()) {
                double factor = (e.getDeltaY() > 0) ? 1.10 : (1.0 / 1.10);
                viewportController.zoomAt(factor, e.getSceneX(), e.getSceneY());
                e.consume();
            }
        });
    }

    private void addApAt(double x, double y) {
        AP ap = new AP();
        ap.name = "AP-" + (env.getAps().size() + 1);
        ap.x = x;
        ap.y = y;
        ap.enabled = true;

        try {
            ap.radios.get(Band.GHZ_24).ssid = ap.name + "_24G";
            ap.radios.get(Band.GHZ_5).ssid = ap.name + "_5G";
            ap.radios.get(Band.GHZ_6).ssid = ap.name + "_6G";
        } catch (Exception ignored) { }

        env.getAps().add(ap);
    }

    private double safeGetCalibRealMeters() {
        try {
            return state.getCalibRealMeters();
        } catch (Exception ignored) {
            return 5.0;
        }
    }

    private void showError(String msg) {
        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR, msg, javafx.scene.control.ButtonType.OK).showAndWait();
    }

    private void showInfo(String msg) {
        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION, msg, javafx.scene.control.ButtonType.OK).showAndWait();
    }
}