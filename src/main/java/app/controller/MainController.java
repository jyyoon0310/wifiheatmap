package app.controller;

import app.dialog.ApEditorDialog;
import app.engine.FdtdWaveSimulator;
import app.engine.HeatmapGenerator;
import app.engine.fdtd.FdtdConfig;
import app.engine.fdtd.FdtdHeatmapGenerator;
import app.engine.fdtd.FdtdProgress;
import app.model.AP;
import app.model.AppState;
import app.model.Band;
import app.model.RadioConfig;
import app.model.RssiResult;
import app.model.PropagationPath;
import app.model.Wall;
import app.model.WallMaterial;
import app.model.WifiEnvironment;
import app.ui.MainWindow;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
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
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class MainController {

    private final Stage stage;

    private final AppState state = new AppState();
    private final WifiEnvironment env = new WifiEnvironment();

    private final MainWindow window;

    private final ViewportController viewportController;
    private final ToolsController toolsController;

    private BufferedImage floorplanBI;
    private WritableImage heatmapImage;
    private WritableImage solverOverlayImage;

    // ===== VIEW Pan 상태 =====
    private boolean spaceDown = false;
    private boolean panning = false;
    private boolean panDragged = false;
    private double panStartSceneX, panStartSceneY;
    private double panStartH, panStartV;
    private double panStartTx, panStartTy;
    private boolean hasMouseProbe = false;
    private double mouseProbeX = 0.0;
    private double mouseProbeY = 0.0;
    private final PauseTransition heatmapRefreshDebounce = new PauseTransition(Duration.millis(280));
    private boolean gpuFallbackWarned = false;
    private FdtdWaveSimulator fdtdSolver;
    private final AnimationTimer solverTimer;
    private boolean solverRunning = false;
    private boolean solverDirty = true;
    private long solverLastFrameNs = 0L;
    private int solverRenderFrameMod = 2;
    private double solverFpsEma = Double.NaN;
    private Task<WritableImage> fdtdHeatmapTask;
    private boolean fdtdRegeneratePending = false;

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
        this.heatmapRefreshDebounce.setOnFinished(e -> regenerateHeatmapIfVisible());
        this.solverTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                onSolverFrame(now);
            }
        };
        this.env.setClientHeightM(1.0);
        this.env.setPathLossN(3.5);

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

        window.getTopToolbar().setOnGenerateHeatmap(this::generateHeatmapNow);
        state.setHeatmapModel(AppState.HeatmapModel.LEGACY);
        window.getTopToolbar().setHeatmapModel(AppState.HeatmapModel.LEGACY);
        window.getTopToolbar().setOnHeatmapModelChanged(mode -> {
            // FDTD 히트맵은 잠시 비활성: Legacy 고정
            state.setHeatmapModel(AppState.HeatmapModel.LEGACY);
            window.getTopToolbar().setHeatmapModel(AppState.HeatmapModel.LEGACY);
        });
        window.getTopToolbar().setHeatmapSolverMode(state.getHeatmapSolverMode());
        window.getTopToolbar().setOnHeatmapSolverModeChanged(mode -> {
            state.setHeatmapSolverMode(mode);
            if (state.getHeatmapSolverMode() == AppState.HeatmapSolverMode.CPU) {
                gpuFallbackWarned = false;
            }
            scheduleHeatmapRefreshIfVisible();
        });
        window.getTopToolbar().setOnStartSolver(this::startSolver);
        window.getTopToolbar().setOnStopSolver(this::stopSolver);
        window.getTopToolbar().setOnResetSolver(this::resetSolver);
        window.getTopToolbar().setSolverRunning(false);

        window.getTopToolbar().setOnClearHeatmap(() -> {
            heatmapImage = null;
            render();
        });

        window.getBottomBar().setClientHeight(env.getClientHeightM());
        window.getBottomBar().setRssiLegendRange(state.legendMinProperty().get(), state.legendMaxProperty().get());
        window.getBottomBar().setCurrentRssi(Double.NaN);
        window.getBottomBar().setOnApplyClientHeight(h -> {
            env.setClientHeightM(Math.max(0.1, h));
            solverDirty = true;
            scheduleHeatmapRefreshIfVisible();
        });

        window.getTopToolbar().setOnToolChanged(tool -> {
            state.setTool(tool);

            stopPan();
            toolsController.onToolChanged(tool);
            window.getTopToolbar().setSolverToolActive(tool == AppState.Tool.SOLVER);
            window.getLeftPanel().setSolverToolActive(tool == AppState.Tool.SOLVER);
            if (tool != AppState.Tool.SOLVER) {
                stopSolver();
            }

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
                    env.setScaleMPerPx(mPerPx);
                    solverDirty = true;
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
                    env.setScaleMPerPx(Double.NaN);
                    solverDirty = true;

                    stopPan();
                    updateCursorByMode();
                    render();
                },
                this::onApConfigChanged,
                () -> {
                    toolsController.clearApSelection();
                    toolsController.clearApInteraction();
                },
                this::scheduleHeatmapRefreshIfVisible,
                toolsController::clearWallSelection
        );
        window.getLeftPanel().setOnSelectWall(wall -> toolsController.setSelectedWall(wall, this::render));
        window.getLeftPanel().setOnShowPathsChanged(this::render);
        window.getLeftPanel().setOnSolverConfigChanged(() -> {
            solverDirty = true;
            render();
        });

        window.getTopToolbar().setSolverToolActive(false);
        window.getLeftPanel().setSolverToolActive(false);

        installCanvasHandlers();

        updateCursorByMode();
        render();
    }

    private void openWallMaterialDialog(Wall wall) {
        if (wall == null) return;

        java.util.LinkedHashMap<String, WallMaterial> options = new java.util.LinkedHashMap<>();
        for (WallMaterial m : WallMaterial.values()) {
            options.put(m.labelWithAttn(), m);
        }
        String current = wall.getMaterial() == null ? WallMaterial.CONCRETE_WALL.labelWithAttn()
                : wall.getMaterial().labelWithAttn();

        javafx.scene.control.ChoiceDialog<String> dlg =
                new javafx.scene.control.ChoiceDialog<>(current, options.keySet());
        dlg.setTitle("벽 재질 선택");
        dlg.setHeaderText("벽 재질을 선택하세요 (2.4GHz/5GHz 감쇠)");
        dlg.setContentText("재질");

        Optional<String> selected = dlg.showAndWait();
        selected.ifPresent(key -> {
            WallMaterial material = options.getOrDefault(key, WallMaterial.CONCRETE_WALL);
            wall.setMaterial(material);
            scheduleHeatmapRefreshIfVisible();
            render();
        });
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
        window.getTopToolbar().setSolverToolActive(state.getTool() == AppState.Tool.SOLVER);
        AP selectedAp = toolsController.getSelectedAp();
        Wall selectedWall = toolsController.getSelectedWall();
        Wall hoverWall = toolsController.getHoverWall();
        window.getLeftPanel().setSelectedAp(selectedAp);
        window.getLeftPanel().setSelectedWall(selectedWall);
        window.getLeftPanel().setWalls(env.getWalls());
        window.getLeftPanel().setScaleVisible(state.getTool() == AppState.Tool.SCALE);
        window.getLeftPanel().setSolverToolActive(state.getTool() == AppState.Tool.SOLVER);
        window.getLeftPanel().setRssiResults(currentOrIdleRssiRows());
        long solverStep = (fdtdSolver == null) ? 0L : fdtdSolver.stepCount();
        double solverTimeNs = (fdtdSolver == null) ? 0.0 : fdtdSolver.timeNs();
        window.getLeftPanel().setSolverStatus(solverRunning, solverStep, solverTimeNs, solverFpsEma);
        window.getBottomBar().setRssiLegendRange(state.legendMinProperty().get(), state.legendMaxProperty().get());
        window.getBottomBar().setCurrentRssi(currentMouseStrongestRssi());

        List<PropagationPath> debugPaths = List.of();
        if (hasMouseProbe && window.getLeftPanel().isShowPathsEnabled()) {
            debugPaths = env.computeBestPathsAt(
                    (int) Math.round(mouseProbeX),
                    (int) Math.round(mouseProbeY)
            );
        }

        WritableImage visibleSolverOverlay =
                (state.getTool() == AppState.Tool.SOLVER && window.getLeftPanel().isSolverOverlayEnabled())
                        ? solverOverlayImage
                        : null;

        window.getCanvasView().render(
                env,
                state,
                heatmapImage,
                visibleSolverOverlay,
                toolsController.getCalibPts(),
                toolsController.getFirstPoint(),
                toolsController.getHoverPoint(),
                toolsController.getHoverAp(),
                selectedAp,
                hoverWall,
                selectedWall,
                debugPaths
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

            if (toolsController.onKeyPressed(e.getCode(), this::scheduleHeatmapRefreshIfVisible)) {
                e.consume();
                return;
            }

            if (e.getCode() == KeyCode.ESCAPE) {
                state.setTool(AppState.Tool.VIEW);
                toolsController.onToolChanged(AppState.Tool.VIEW);
                toolsController.clearApSelection();
                toolsController.clearApInteraction();
                toolsController.clearWallSelection();
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
            solverOverlayImage = null;
            fdtdSolver = null;
            if (fdtdHeatmapTask != null) {
                fdtdHeatmapTask.cancel();
                fdtdHeatmapTask = null;
            }
            fdtdRegeneratePending = false;
            solverDirty = true;
            stopSolver();

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

    private void generateHeatmapNow() {
        if (window.getCanvasView().getBaseImageView().getImage() == null) {
            showInfo("먼저 평면도를 열어주세요.");
            return;
        }

        syncModelParamsFromState();

        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0.0) {
            showError("스케일을 먼저 적용해주세요.");
            return;
        }

        int w = Math.max(1, (int) Math.round(window.getCanvasView().getDrawCanvas().getWidth()));
        int h = Math.max(1, (int) Math.round(window.getCanvasView().getDrawCanvas().getHeight()));

        HeatmapGenerator generator = new HeatmapGenerator(env, state.getHeatmapSolverMode());
        heatmapImage = generator.generate(
                w,
                h,
                8,
                state.legendMinProperty().get(),
                state.legendMaxProperty().get(),
                state.getSmoothRadiusPx()
        );
        if (state.getHeatmapSolverMode() == AppState.HeatmapSolverMode.GPU
                && generator.gpuFallbackLastRun()
                && !gpuFallbackWarned) {
            gpuFallbackWarned = true;
            showInfo("GPU 솔버를 찾지 못해 CPU로 계산했습니다.\n" +
                    "GPU 백엔드를 ServiceLoader로 추가하면 자동으로 사용됩니다.");
        }
        render();
    }

    private void generateHeatmapFdtdAsync(int widthPx, int heightPx) {
        if (fdtdHeatmapTask != null && fdtdHeatmapTask.isRunning()) {
            fdtdRegeneratePending = true;
            return;
        }

        Band band = window.getLeftPanel().getFdtdBand();
        FdtdConfig cfg = new FdtdConfig(
                band,
                window.getLeftPanel().getFdtdFreqGhz() * 1.0e9,
                window.getLeftPanel().getFdtdDxMeters(),
                window.getLeftPanel().getFdtdSteps(),
                window.getLeftPanel().getFdtdPmlCells(),
                window.getLeftPanel().getFdtdRampNs() * 1.0e-9,
                window.getLeftPanel().getFdtdSourceAmplitude(),
                window.getLeftPanel().getFdtdRmsCycles(),
                window.getLeftPanel().getFdtdReferenceMode(),
                window.getLeftPanel().getFdtdCustomReference(),
                window.getLeftPanel().getFdtdWallPreset(),
                window.getLeftPanel().isFdtdShowMaterialGrid(),
                window.getLeftPanel().isFdtdShowPmlGrid()
        );

        FdtdHeatmapGenerator fdtdGen = new FdtdHeatmapGenerator(env, band);
        fdtdRegeneratePending = false;
        window.getLeftPanel().setSolverStatus(true, 0, 0.0, Double.NaN);

        fdtdHeatmapTask = new Task<>() {
            @Override
            protected WritableImage call() {
                return fdtdGen.generate(
                        widthPx,
                        heightPx,
                        state.legendMinProperty().get(),
                        state.legendMaxProperty().get(),
                        state.getSmoothRadiusPx(),
                        cfg,
                        MainController.this::onFdtdProgress
                );
            }
        };

        fdtdHeatmapTask.setOnSucceeded(e -> {
            heatmapImage = fdtdHeatmapTask.getValue();
            window.getLeftPanel().setSolverStatus(false, 0, 0.0, Double.NaN);
            fdtdHeatmapTask = null;
            render();
            if (fdtdRegeneratePending) {
                fdtdRegeneratePending = false;
                generateHeatmapNow();
            }
        });
        fdtdHeatmapTask.setOnFailed(e -> {
            Throwable ex = fdtdHeatmapTask.getException();
            window.getLeftPanel().setSolverStatus(false, 0, 0.0, Double.NaN);
            fdtdHeatmapTask = null;
            showError("FDTD 히트맵 생성 실패: " + (ex == null ? "unknown" : ex.getMessage()));
            if (fdtdRegeneratePending) {
                fdtdRegeneratePending = false;
                generateHeatmapNow();
            }
        });
        fdtdHeatmapTask.setOnCancelled(e -> {
            window.getLeftPanel().setSolverStatus(false, 0, 0.0, Double.NaN);
            fdtdHeatmapTask = null;
        });

        Thread worker = new Thread(fdtdHeatmapTask, "fdtd-heatmap-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void onFdtdProgress(FdtdProgress p) {
        if (p == null) return;
        if (p.currentStep() == 1 || p.currentStep() % 200 == 0 || p.currentStep() == p.totalSteps()) {
            System.out.printf("[FDTD] %s, simTime=%.3fns, total=%d/%d%n",
                    p.message(),
                    p.simTimeNs(),
                    p.totalCompletedSteps(),
                    p.totalPlannedSteps());
        }

        if (p.currentStep() % 50 == 0 || p.currentStep() == p.totalSteps()) {
            Platform.runLater(() -> window.getLeftPanel().setSolverStatus(
                    true,
                    p.totalCompletedSteps(),
                    p.simTimeNs(),
                    Double.NaN
            ));
        }
    }

    private void syncModelParamsFromState() {
        env.setScaleMPerPx(state.getScaleMPerPx());
        env.setPathLossN(3.5);
    }

    private void regenerateHeatmapIfVisible() {
        if (heatmapImage != null) {
            generateHeatmapNow();
            return;
        }
        render();
    }

    private void onApConfigChanged() {
        scheduleHeatmapRefreshIfVisible();
    }

    private void scheduleHeatmapRefreshIfVisible() {
        solverDirty = true;
        if (heatmapImage == null) {
            render();
            return;
        }
        heatmapRefreshDebounce.playFromStart();
    }

    private void startSolver() {
        if (!ensureSolverReady(true)) return;
        if (solverRunning) return;
        solverRunning = true;
        solverLastFrameNs = 0L;
        solverFpsEma = Double.NaN;
        solverTimer.start();
        window.getTopToolbar().setSolverRunning(true);
        render();
    }

    private void stopSolver() {
        if (!solverRunning) {
            window.getTopToolbar().setSolverRunning(false);
            return;
        }
        solverRunning = false;
        solverTimer.stop();
        window.getTopToolbar().setSolverRunning(false);
        solverFpsEma = Double.NaN;
    }

    private void resetSolver() {
        stopSolver();
        if (!ensureSolverReady(false)) {
            solverOverlayImage = null;
            render();
            return;
        }
        fdtdSolver.reset();
        solverFpsEma = Double.NaN;
        solverOverlayImage = fdtdSolver.renderFrame();
        render();
    }

    private boolean ensureSolverReady(boolean showErrorIfMissingMap) {
        if (window.getCanvasView().getBaseImageView().getImage() == null) {
            if (showErrorIfMissingMap) showInfo("먼저 평면도를 열어주세요.");
            return false;
        }
        int w = Math.max(1, (int) Math.round(window.getCanvasView().getDrawCanvas().getWidth()));
        int h = Math.max(1, (int) Math.round(window.getCanvasView().getDrawCanvas().getHeight()));
        int cellPx = Math.max(2, window.getLeftPanel().getSolverCellPx());
        Band solverBand = window.getLeftPanel().getSolverDisplayBand();
        solverRenderFrameMod = Math.max(1, window.getLeftPanel().getSolverRenderSkip());

        boolean needRebuild = (fdtdSolver == null)
                || (fdtdSolver.widthPx() != w)
                || (fdtdSolver.heightPx() != h)
                || (fdtdSolver.cellPx() != cellPx)
                || solverDirty;

        if (needRebuild) {
            fdtdSolver = new FdtdWaveSimulator(env, w, h, cellPx, solverBand);
            solverDirty = false;
            solverOverlayImage = fdtdSolver.renderFrame();
            solverFpsEma = Double.NaN;
        }
        return true;
    }

    private void onSolverFrame(long now) {
        if (!solverRunning) return;

        if (solverDirty && !ensureSolverReady(false)) {
            stopSolver();
            return;
        }

        if (solverLastFrameNs == 0L) {
            solverLastFrameNs = now;
            return;
        }
        long dt = now - solverLastFrameNs;
        if (dt < 16_000_000L) return; // ~60fps 상한
        solverLastFrameNs = now;
        double fps = 1_000_000_000.0 / dt;
        solverFpsEma = Double.isFinite(solverFpsEma) ? (solverFpsEma * 0.85 + fps * 0.15) : fps;

        if (fdtdSolver == null) return;
        int subSteps = Math.max(1, window.getLeftPanel().getSolverSubSteps());
        fdtdSolver.step(subSteps);
        if (fdtdSolver.stepCount() % Math.max(1, solverRenderFrameMod) == 0L) {
            solverOverlayImage = fdtdSolver.renderFrame();
            render();
        }
    }

    private void installCanvasHandlers() {
        var canvas = window.getCanvasView().getDrawCanvas();
        var sp = window.getCanvasView().getCanvasSP();

        canvas.setOnMousePressed(e -> {
            boolean startPan =
                    (e.getButton() == MouseButton.SECONDARY) ||
                            (spaceDown && e.getButton() == MouseButton.PRIMARY);

            if (startPan) {
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
                return;
            }

            if (state.getTool() == AppState.Tool.AP) {
                toolsController.onMousePressed(e.getX(), e.getY(), e.getButton(), this::render);
                e.consume();
            }
        });

        canvas.setOnMouseDragged(e -> {
            if (panning) {
                viewportController.panBy(
                        panStartH, panStartV,
                        panStartTx, panStartTy,
                        panStartSceneX, panStartSceneY,
                        e.getSceneX(), e.getSceneY()
                );

                panDragged = true;
                e.consume();
                return;
            }

            if (state.getTool() == AppState.Tool.AP) {
                toolsController.onMouseDragged(e.getX(), e.getY(), this::render);
                e.consume();
                return;
            }

            if (!panning) return;
        });

        canvas.setOnMouseReleased(e -> {
            if (panning) {
                panning = false;
                updateCursorByMode();
                e.consume();
                return;
            }

            if (state.getTool() == AppState.Tool.AP) {
                toolsController.onMouseReleased(this::render);
                scheduleHeatmapRefreshIfVisible();
                e.consume();
            }
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

            if (state.getTool() == AppState.Tool.VIEW || state.getTool() == AppState.Tool.SOLVER) {
                return;
            }

            toolsController.onMouseClicked(
                    e.getX(), e.getY(),
                    e.getButton(),
                    this::render,
                    () -> {
                        state.setTool(AppState.Tool.VIEW);
                        toolsController.onToolChanged(AppState.Tool.VIEW);
                        try { window.getTopToolbar().clearToolSelection(); } catch (Exception ignored) {}
                        updateCursorByMode();
                    },
                    this::openWallMaterialDialog
            );

            if (state.getTool() == AppState.Tool.AP) {
                scheduleHeatmapRefreshIfVisible();
            }
        });

        canvas.setOnMouseMoved(e -> {
            toolsController.onMouseMoved(e.getX(), e.getY(), null);
            hasMouseProbe = true;
            mouseProbeX = e.getX();
            mouseProbeY = e.getY();
            render();
        });

        canvas.setOnMouseExited(e -> {
            hasMouseProbe = false;
            render();
        });

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
            toolsController.clearApInteraction();
            regenerateHeatmapIfVisible();
        } else if (r == ApEditorDialog.Result.OK) {
            onApConfigChanged();
        }
    }

    private void updateCursorByMode() {
        var canvas = window.getCanvasView().getDrawCanvas();

        if (panning) {
            canvas.setCursor(Cursor.CLOSED_HAND);
            return;
        }

        if (state.getTool() == AppState.Tool.VIEW || state.getTool() == AppState.Tool.SOLVER) {
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

    private List<RssiResult> currentMouseRssiRows() {
        if (!hasMouseProbe) return List.of();
        if (window.getCanvasView().getBaseImageView().getImage() == null) return List.of();
        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0.0) return List.of();
        return env.sampleRssiAllAt((int) Math.round(mouseProbeX), (int) Math.round(mouseProbeY));
    }

    private double currentMouseStrongestRssi() {
        List<RssiResult> rows = currentMouseRssiRows();
        if (rows.isEmpty()) return Double.NaN;
        return rows.get(0).rssiDbm;
    }

    private List<RssiResult> currentOrIdleRssiRows() {
        List<RssiResult> current = currentMouseRssiRows();
        if (!current.isEmpty()) return current;
        return idleRssiRows();
    }

    private List<RssiResult> idleRssiRows() {
        List<RssiResult> out = new java.util.ArrayList<>();
        for (AP ap : env.getAps()) {
            if (ap == null || !ap.enabled) continue;
            for (Band band : Band.values()) {
                RadioConfig rc = ap.radios.get(band);
                if (rc == null || !rc.enabled) continue;
                out.add(new RssiResult(ap.name, rc.ssid, band, Double.NaN));
            }
        }
        return out;
    }
}
