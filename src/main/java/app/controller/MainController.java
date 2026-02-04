package app.controller;

import app.dialog.ApEditorDialog;
import app.model.AP;
import app.model.AppState;
import app.model.WifiEnvironment;
import app.ui.MainWindow;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class MainController {

    private final Stage stage;

    private final AppState state = new AppState();
    private final WifiEnvironment env = new WifiEnvironment();

    private final MainWindow window;

    private final ViewportController viewportController;
    private final ToolsController toolsController;

    private BufferedImage floorplanBI;
    private WritableImage heatmapImage;

    // ===== VIEW Pan 상태 =====
    private boolean spaceDown = false;
    private boolean panning = false;
    private boolean panDragged = false;
    private double panStartSceneX, panStartSceneY;
    private double panStartH, panStartV;
    private double panStartTx, panStartTy;

    public MainController(Stage stage) {
        this.stage = stage;
        this.window = new MainWindow(stage);

        this.viewportController = new ViewportController(
                window.getCanvasView().getCanvasSP(),
                window.getCanvasView().getViewportPane(),
                window.getCanvasView().getZoomGroup(),
                window.getCanvasView().getFloorGroup()
        );

        this.toolsController = new ToolsController(env, state);

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
            render();
        });

        window.getTopToolbar().setOnToolChanged(tool -> {
            state.setTool(tool);

            stopPan();
            toolsController.onToolChanged(tool);

            if (tool == AppState.Tool.VIEW) {
                try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}
            }

            updateCursorByMode();
            render();
        });

        // 줌 박스
        try {
            window.getTopToolbar().bindZoomLabel(viewportController.zoomScaleProperty());

            window.getTopToolbar().setOnZoomIn(() -> zoomAtViewportCenter(1.10));
            window.getTopToolbar().setOnZoomOut(() -> zoomAtViewportCenter(1.0 / 1.10));

            window.getTopToolbar().setOnZoom100(() -> {
                viewportController.setZoom(1.0);
                viewportController.updateViewportSize();
                viewportController.centerViewport();
            });

            window.getTopToolbar().setOnZoomFit(() -> {
                if (window.getCanvasView().getBaseImageView().getImage() == null) {
                    showInfo("먼저 평면도를 열어주세요.");
                    return;
                }
                double w = window.getCanvasView().getDrawCanvas().getWidth();
                double h = window.getCanvasView().getDrawCanvas().getHeight();
                viewportController.fitToViewport(20, w, h);
            });
        } catch (Exception ignored) {}

        // LeftPanel: scale apply/reset
        window.getLeftPanel().bind(
                state,
                env,
                () -> {
                    boolean ok = toolsController.applyScaleIfReady(() -> {
                        state.setTool(AppState.Tool.VIEW);
                        toolsController.onToolChanged(AppState.Tool.VIEW);
                        try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}
                    });

                    if (!ok) {
                        showError("스케일 선분(두 점)을 먼저 확정하세요.");
                        stopPan();
                        updateCursorByMode();
                        render();
                        return;
                    }

                    double mPerPx = state.getScaleMPerPx();
                    double inchPerPx = mPerPx * 39.37007874015748;
                    showInfo(String.format(
                            "스케일 적용됨\n1px = %.6f m (%.6f inch)",
                            mPerPx, inchPerPx
                    ));

                    stopPan();
                    updateCursorByMode();
                    render();
                },
                () -> {
                    toolsController.resetScale(() -> {
                        state.setTool(AppState.Tool.VIEW);
                        toolsController.onToolChanged(AppState.Tool.VIEW);
                        try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}
                    });

                    stopPan();
                    updateCursorByMode();
                    render();
                }
        );

        installCanvasHandlers();

        updateCursorByMode();
        render();
    }

    private void zoomAtViewportCenter(double factor) {
        var sp = window.getCanvasView().getCanvasSP();
        Bounds vp = sp.getViewportBounds();
        if (vp == null) return;

        Point2D topLeftScene = sp.localToScene(0, 0);
        double cx = topLeftScene.getX() + vp.getWidth() / 2.0;
        double cy = topLeftScene.getY() + vp.getHeight() / 2.0;

        viewportController.zoomAt(factor, cx, cy);
    }

    private void render() {
        window.getCanvasView().render(
                env,
                state,
                heatmapImage,
                toolsController.getCalibPts(),
                toolsController.getFirstPoint(),
                toolsController.getHoverPoint(),
                toolsController.getHoverAp(),
                toolsController.getSelectedAp()
        );
    }

    private void installSceneShortcuts(Scene scene) {
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                spaceDown = true;
                updateCursorByMode();
                e.consume();
                return;
            }

            if (toolsController.onKeyPressed(e.getCode(), this::render)) {
                e.consume();
                return;
            }

            if (e.getCode() == KeyCode.ESCAPE) {
                state.setTool(AppState.Tool.VIEW);
                toolsController.onToolChanged(AppState.Tool.VIEW);
                try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}
                stopPan();
                updateCursorByMode();
                render();
                e.consume();
            }
        });

        scene.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                spaceDown = false;
                updateCursorByMode();
                e.consume();
            }
        });
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

            viewportController.setBaseContentSize(fx.getWidth(), fx.getHeight());
            viewportController.setZoom(1.0);
            viewportController.updateViewportSize();
            viewportController.centerViewport();

            state.setTool(AppState.Tool.VIEW);
            try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}
            toolsController.onToolChanged(AppState.Tool.VIEW);

            stopPan();
            updateCursorByMode();
            render();

        } catch (Exception ex) {
            showError("이미지 로드 실패: " + ex.getMessage());
        }
    }

    private void installCanvasHandlers() {
        var canvas = window.getCanvasView().getDrawCanvas();
        var sp = window.getCanvasView().getCanvasSP();

        canvas.setOnMousePressed(e -> {
            if (state.getTool() == AppState.Tool.AP) {
                toolsController.onMousePressed(e.getX(), e.getY(), e.getButton(), this::render);
                e.consume();
                return;
            }

            if (state.getTool() != AppState.Tool.VIEW) return;

            boolean startPan =
                    (e.getButton() == MouseButton.SECONDARY) ||
                            (spaceDown && e.getButton() == MouseButton.PRIMARY);

            if (!startPan) return;

            panning = true;
            panDragged = false;

            panStartSceneX = e.getSceneX();
            panStartSceneY = e.getSceneY();

            panStartH = sp.getHvalue();
            panStartV = sp.getVvalue();

            panStartTx = viewportController.getPanTx();
            panStartTy = viewportController.getPanTy();

            canvas.setCursor(Cursor.CLOSED_HAND);
            e.consume();
        });

        canvas.setOnMouseDragged(e -> {
            if (state.getTool() == AppState.Tool.AP) {
                toolsController.onMouseDragged(e.getX(), e.getY(), this::render);
                e.consume();
                return;
            }

            if (!panning) return;

            viewportController.panBy(
                    panStartH, panStartV,
                    panStartTx, panStartTy,
                    panStartSceneX, panStartSceneY,
                    e.getSceneX(), e.getSceneY()
            );

            panDragged = true;
            e.consume();
        });

        canvas.setOnMouseReleased(e -> {
            if (state.getTool() == AppState.Tool.AP) {
                toolsController.onMouseReleased(this::render);
                e.consume();
                return;
            }

            if (!panning) return;
            panning = false;
            updateCursorByMode();
            e.consume();
        });

        canvas.setOnMouseClicked(e -> {
            if (panDragged) {
                panDragged = false;
                e.consume();
                return;
            }

            // ✅ 더블클릭 편집: VIEW/AP에서만
            if (e.getButton() == MouseButton.PRIMARY
                    && e.getClickCount() == 2
                    && (state.getTool() == AppState.Tool.AP || state.getTool() == AppState.Tool.VIEW)) {

                AP hit = toolsController.findApNear(e.getX(), e.getY());
                if (hit != null) {
                    openApEditor(hit);
                    e.consume();
                    return;
                }
            }

            if (state.getTool() == AppState.Tool.VIEW) return;

            toolsController.onMouseClicked(
                    e.getX(), e.getY(),
                    e.getButton(),
                    this::render,
                    () -> {
                        state.setTool(AppState.Tool.VIEW);
                        toolsController.onToolChanged(AppState.Tool.VIEW);
                        try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}
                        updateCursorByMode();
                    }
            );
        });

        canvas.setOnMouseMoved(e -> toolsController.onMouseMoved(e.getX(), e.getY(), this::render));

        sp.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() || e.isShortcutDown()) {
                double factor = (e.getDeltaY() > 0) ? 1.10 : (1.0 / 1.10);
                viewportController.zoomAt(factor, e.getSceneX(), e.getSceneY());
                e.consume();
            }
        });
    }

    private void openApEditor(AP ap) {
        ApEditorDialog.Result r = ApEditorDialog.show(stage, ap, this::render);

        if (r == ApEditorDialog.Result.DELETE) {
            env.getAps().remove(ap);
            toolsController.clearApSelection();
            render();
        } else if (r == ApEditorDialog.Result.OK) {
            render();
        }
    }

    private void updateCursorByMode() {
        var canvas = window.getCanvasView().getDrawCanvas();

        if (panning) {
            canvas.setCursor(Cursor.CLOSED_HAND);
            return;
        }

        if (state.getTool() == AppState.Tool.VIEW) {
            canvas.setCursor(spaceDown ? Cursor.OPEN_HAND : Cursor.DEFAULT);
        } else {
            canvas.setCursor(Cursor.CROSSHAIR);
        }
    }

    private void stopPan() {
        panning = false;
        panDragged = false;
    }

    private void showError(String msg) {
        new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR, msg,
                javafx.scene.control.ButtonType.OK
        ).showAndWait();
    }

    private void showInfo(String msg) {
        new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION, msg,
                javafx.scene.control.ButtonType.OK
        ).showAndWait();
    }
}