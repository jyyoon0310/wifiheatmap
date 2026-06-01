package app.controller;

import app.engine.DpmPathGrid;
import app.engine.HeatmapGenerator;
import app.model.AP;
import app.model.AppState;
import app.model.Band;
import app.model.RadioConfig;
import app.model.RssiResult;
import app.model.Wall;
import app.model.WallMaterial;
import app.model.WifiEnvironment;
import app.solver.v2.SolverV2Engine;
import app.ui.MainWindow;

import app.ui.Styles;
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
import javafx.scene.image.PixelFormat;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

public class MainController {
    private enum OnboardingStep {
        NONE,
        SCALE,
        AP,
        WALL
    }

    private final Stage stage;

    private final AppState state = new AppState();
    private final WifiEnvironment env = new WifiEnvironment();

    private final MainWindow window;

    private final ViewportController viewportController;
    private final ToolsController toolsController;

    private BufferedImage floorplanBI;
    private File currentFloorplanFile;
    /** 마지막으로 저장/불러온 .wifisettings 파일 — Cmd+S 빠른 저장 시 사용 */
    private File currentSettingsFile;
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
    private SolverV2Engine fdtdSolver;
    private final AnimationTimer solverTimer;
    private boolean solverRunning = false;
    private boolean solverConfigDirty = true;
    private long solverLastFrameNs = 0L;
    private int solverRenderFrameMod = 2;
    private double solverFpsEma = Double.NaN;
    private long solverStartWallNs = 0L;
    private long solverLastTelemetryNs = 0L;
    private long solverSourceSignature = Long.MIN_VALUE;
    private long solverMaterialSignature = Long.MIN_VALUE;
    private double solverScaleSignature = Double.NaN;
    private Band solverBandSignature = null;
    private String solverDebugText = "debug: -";
    private Task<HeatmapGenerator.HeatmapResult> legacyHeatmapTask;
    private volatile java.util.Map<AP, java.util.Map<Band, DpmPathGrid>> cachedDpmGrids;
    private OnboardingStep onboardingStep = OnboardingStep.NONE;
    /** AP 추천 시 추천 AP에 적용할 공유기 프리셋 — 마지막 선택을 기억. */
    private app.model.ApPreset lastRecommendPreset = app.model.ApPreset.CUSTOM;

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
        this.env.setPathLossN(state.getPathLossN());

        state.setTool(AppState.Tool.VIEW);
        wireUi();
    }

    public Parent getRoot() { return window.getRoot(); }

    public void bindScene(Scene scene) {
        installSceneShortcuts(scene);
    }

    public void afterShown() {
        viewportController.centerViewport();
        maybeShowWelcome();
    }

    /** 사용자 prefs 노드 — recent file, "다시 보지 않기" 등 저장 */
    private static final java.util.prefs.Preferences PREFS =
            java.util.prefs.Preferences.userNodeForPackage(MainController.class);
    private static final String PREFS_KEY_SKIP_WELCOME = "skipWelcome";
    private static final String PREFS_KEY_RECENT_FILE  = "recentFile";

    private void maybeShowWelcome() {
        if (PREFS.getBoolean(PREFS_KEY_SKIP_WELCOME, false)) return;

        String recentPath = PREFS.get(PREFS_KEY_RECENT_FILE, null);
        File recent = (recentPath == null) ? null : new File(recentPath);
        if (recent != null && !recent.exists()) recent = null;

        app.dialog.WelcomeDialog.Result r = app.dialog.WelcomeDialog.show(stage, recent);
        if (r.dontShowAgain) PREFS.putBoolean(PREFS_KEY_SKIP_WELCOME, true);

        switch (r.choice) {
            case NEW_FLOORPLAN -> openFloorplan();
            case OPEN_FILE     -> openSettingsSnapshot();
            case EMPTY_START   -> { /* 빈 화면 그대로 */ }
        }
    }

    /** 최근 사용 파일 경로 갱신 — 평면도 로드 / 설정 저장 후 호출. */
    private void rememberRecentFile(File f) {
        if (f != null) PREFS.put(PREFS_KEY_RECENT_FILE, f.getAbsolutePath());
    }

    private void wireUi() {
        window.getTopToolbar().setOnOpenFloorplan(this::openFloorplan);
        window.getTopToolbar().setOnOpenSettings(this::openSettingsSnapshot);
        window.getTopToolbar().setOnSaveSettings(this::saveSettingsSnapshot);

        window.getTopToolbar().setOnGenerateHeatmap(this::generateHeatmapNow);
        window.getTopToolbar().setOnStartSolver(this::startSolver);
        window.getTopToolbar().setOnStopSolver(this::stopSolver);
        window.getTopToolbar().setOnResetSolver(this::resetSolver);
        window.getTopToolbar().setSolverRunning(false);

        window.getTopToolbar().setOnClearHeatmap(() -> {
            heatmapImage = null;
            window.getLeftPanel().clearMeasurementResult();
            render();
        });

        window.getBottomBar().setClientHeight(env.getClientHeightM());
        window.getBottomBar().setRssiLegendRange(state.legendMinProperty().get(), state.legendMaxProperty().get());
        window.getBottomBar().setCurrentRssi(Double.NaN);
        window.getBottomBar().setOnApplyClientHeight(h -> {
            env.setClientHeightM(Math.max(0.1, h));
            solverConfigDirty = true;
            scheduleHeatmapRefreshIfVisible();
        });

        window.getTopToolbar().setOnToolChanged(tool -> activateTool(tool, true));
        window.getTopToolbar().setOnRecommendAp(this::openApRecommender);
        window.getLeftPanel().setOnWallDone(this::finishWallsAndRecommend);
        window.getTopToolbar().setOnShowAdvanced(() ->
                app.dialog.AdvancedSettingsDialog.show(stage, state));

        // "🌊 전파 흐름 보기" 토글 — 누르면 SOLVER tool로 전환 + 솔버 시작, 끄면 정지 + VIEW로 복귀
        window.getTopToolbar().setOnWaveToggle(active -> {
            if (active) {
                activateTool(AppState.Tool.SOLVER, true);
                startSolver();
            } else {
                stopSolver();
                activateTool(AppState.Tool.VIEW, true);
            }
        });

        // 히트맵 엔진 모델 선택 — 고급 모드에서만 노출. 일반 모드에선 DPM 고정.
        window.getTopToolbar().setHeatmapModel(state.getHeatmapModel());
        window.getTopToolbar().setHeatmapSolverMode(state.getHeatmapSolverMode());
        window.getTopToolbar().setOnHeatmapModelChanged(mode -> {
            if (mode != null) {
                state.setHeatmapModel(mode);
                heatmapImage = null;
                scheduleHeatmapRefreshIfVisible();
            }
        });
        window.getTopToolbar().setOnHeatmapSolverModeChanged(mode -> {
            if (mode != null) {
                state.setHeatmapSolverMode(mode);
                heatmapImage = null;
                scheduleHeatmapRefreshIfVisible();
            }
        });

        // 고급 모드 ↔ TopToolbar 가시성 양방향 바인딩
        window.getTopToolbar().bindAdvancedMode(state.advancedModeProperty());

        // 밴드 필터 — TopToolbar의 pill에서만 관리 (LeftPanel 콤보는 제거됨)
        window.getTopToolbar().setBandFilter(state.getBandFilter());
        window.getTopToolbar().setOnBandFilterChanged(filter -> {
            if (filter != null) {
                state.setBandFilter(filter);
                heatmapImage = null;
                scheduleHeatmapRefreshIfVisible();
                // 솔버가 밴드별 작동한다면 재구성 트리거
                solverConfigDirty = true;
            }
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
                        activateTool(AppState.Tool.VIEW, true);
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
                    solverConfigDirty = true;
                    double inchPerPx = mPerPx * 39.37007874015748;
                    String scaleAppliedMsg = String.format(
                            "스케일 적용됨\n1px = %.6f m (%.6f inch)",
                            mPerPx, inchPerPx
                    );

                    if (onboardingStep == OnboardingStep.SCALE) {
                        onboardingStep = OnboardingStep.WALL;
                        showInfo(scaleAppliedMsg + "\n\n다음 단계\n벽그리기 툴에서 집의 벽을 그린 뒤, 왼쪽 '벽 그리기 완료' 버튼을 누르세요.");
                        activateTool(AppState.Tool.WALL, true);
                        return;
                    }

                    showInfo(scaleAppliedMsg);

                    stopPan();
                    updateCursorByMode();
                    render();
                },
                () -> {
                    toolsController.resetScale(() -> {
                        activateTool(AppState.Tool.VIEW, true);
                    });
                    env.setScaleMPerPx(Double.NaN);
                    solverConfigDirty = true;

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
        window.getLeftPanel().setOnApPresetChanged(preset -> {
            AP ap = toolsController.getSelectedAp();
            if (ap == null) return;
            preset.applyTo(ap);
            window.getLeftPanel().refreshApFields();
            render();
        });
        window.getLeftPanel().setOnSolverConfigChanged(() -> {
            solverConfigDirty = true;
            render();
        });
        window.getLeftPanel().setOnSolverOverlayChanged(this::render);

        window.getTopToolbar().setSolverToolActive(false);
        window.getLeftPanel().setSolverToolActive(false);

        installCanvasHandlers();

        updateCursorByMode();
        render();
    }

    private void activateTool(AppState.Tool tool, boolean syncToolbarSelection) {
        AppState.Tool next = (tool == null) ? AppState.Tool.VIEW : tool;
        state.setTool(next);

        stopPan();
        toolsController.onToolChanged(next);
        window.getTopToolbar().setSolverToolActive(next == AppState.Tool.SOLVER);
        window.getLeftPanel().setSolverToolActive(next == AppState.Tool.SOLVER);
        if (next != AppState.Tool.SOLVER) {
            stopSolver();
        }

        if (syncToolbarSelection) {
            window.getTopToolbar().setToolSelection(next);
        }

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
        dlg.initOwner(stage);
        Styles.styleDialog(dlg);

        Optional<String> selected = dlg.showAndWait();
        selected.ifPresent(key -> {
            WallMaterial material = options.getOrDefault(key, WallMaterial.CONCRETE_WALL);
            wall.setMaterial(material);
            scheduleHeatmapRefreshIfVisible();
            render();
        });
    }


    private void openApRecommender() {
        Image img = window.getCanvasView().getBaseImageView().getImage();
        if (img == null) {
            showInfo("먼저 평면도를 열어주세요.");
            return;
        }
        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0) {
            showInfo("스케일을 먼저 설정해주세요.");
            return;
        }

        // 추천 실행 전 — 사용 중인 공유기를 묻는다. 취소하면 추천 진입 안 함.
        app.model.ApPreset preset = chooseRouterPreset();
        if (preset == null) return;
        lastRecommendPreset = preset;

        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        env.setScaleMPerPx(state.getScaleMPerPx());
        env.setPathLossN(state.getPathLossN());

        app.dialog.ApRecommendDialog.show(stage, img, w, h, env, state, positions -> {
            int idx = env.getAps().size() + 1;
            for (javafx.geometry.Point2D pos : positions) {
                AP ap = new AP();
                ap.name = "추천-" + idx++;
                ap.x = pos.getX();
                ap.y = pos.getY();
                ap.heightM = 2.5;
                ap.enabled = true;
                preset.applyTo(ap);  // 선택한 공유기 스펙을 추천 AP에 적용
                env.getAps().add(ap);
            }
            render();
            // 적용 직후 곧바로 히트맵 생성 → 추천 결과를 바로 확인할 수 있게 한다.
            generateHeatmapNow();
        });
    }

    /**
     * 사용 중인 공유기(통신사 프리셋)를 묻는다. 선택한 프리셋은 추천 AP의 RadioConfig에 적용된다.
     * @return 선택한 프리셋, 취소 시 null
     */
    private app.model.ApPreset chooseRouterPreset() {
        java.util.List<app.model.ApPreset> presets = java.util.Arrays.asList(app.model.ApPreset.values());
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (app.model.ApPreset p : presets) labels.add(p.label);

        javafx.scene.control.ChoiceDialog<String> dlg =
                new javafx.scene.control.ChoiceDialog<>(lastRecommendPreset.label, labels);
        dlg.setTitle("공유기 선택");
        dlg.setHeaderText("사용 중인 공유기를 선택하세요.\n선택한 공유기의 전파 스펙으로 추천 위치를 계산합니다.");
        dlg.setContentText("공유기:");
        if (stage != null) dlg.initOwner(stage);
        Styles.styleDialog(dlg);

        Optional<String> res = dlg.showAndWait();
        if (res.isEmpty()) return null;
        int idx = labels.indexOf(res.get());
        return idx >= 0 ? presets.get(idx) : app.model.ApPreset.CUSTOM;
    }

    /**
     * '벽 그리기 완료' 버튼 핸들러 — 벽이 충분한지 확인 후 AP 추천 흐름으로 진입.
     */
    private void finishWallsAndRecommend() {
        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0) {
            showInfo("스케일을 먼저 설정해주세요.");
            return;
        }
        if (env.getWalls().isEmpty()) {
            showInfo("먼저 벽을 1개 이상 그려주세요.");
            return;
        }
        onboardingStep = OnboardingStep.NONE;
        openApRecommender();
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
        window.getLeftPanel().setWallToolActive(state.getTool() == AppState.Tool.WALL);
        window.getLeftPanel().setSolverToolActive(state.getTool() == AppState.Tool.SOLVER);
        window.getLeftPanel().setRssiResults(currentOrIdleRssiRows());
        long solverStep = (fdtdSolver == null) ? 0L : fdtdSolver.stepCount();
        double solverTimeNs = (fdtdSolver == null) ? 0.0 : fdtdSolver.timeNs();
        window.getLeftPanel().setSolverStatus(solverRunning, solverStep, solverTimeNs, solverFpsEma);
        window.getLeftPanel().setSolverDebug(solverDebugText);
        window.getBottomBar().setRssiLegendRange(state.legendMinProperty().get(), state.legendMaxProperty().get());
        window.getBottomBar().setCurrentRssi(currentMouseStrongestRssi());

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
                List.of(),
                (state.getTool() == AppState.Tool.SOLVER) ? solverDebugText : null
        );

        // 단계별 가이드 오버레이 갱신
        boolean hasFloorplan = window.getCanvasView().getBaseImageView().getImage() != null;
        boolean hasAps = !env.getAps().isEmpty();
        boolean hasHeatmap = (heatmapImage != null) || (visibleSolverOverlay != null);
        window.getCanvasView().updateGuideOverlay(hasFloorplan, hasAps, hasHeatmap);
    }

    private void installSceneShortcuts(Scene scene) {
        scene.setOnKeyPressed(e -> {
            // ── 파일 단축키 (Cmd+O / Cmd+S / Cmd+Shift+S) ─────────────
            // macOS는 META, 그 외는 CTRL — Shortcut 키 처리
            if (e.isShortcutDown()) {
                if (e.getCode() == KeyCode.O) {
                    openSettingsSnapshot();
                    e.consume();
                    return;
                }
                if (e.getCode() == KeyCode.S) {
                    if (e.isShiftDown()) saveSettingsSnapshotAs();
                    else                 saveSettingsSnapshot();
                    e.consume();
                    return;
                }
            }

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
        loadFloorplanFromFile(f, true);
    }

    private void loadFloorplanFromFile(File f, boolean promptPathLossPreset) {
        if (f == null) return;
        try {
            floorplanBI = ImageIO.read(f);
            if (floorplanBI == null) throw new IOException("이미지 로드 실패");
            currentFloorplanFile = f;
            rememberRecentFile(f);

            Image fx = SwingFXUtils.toFXImage(floorplanBI, null);
            window.getCanvasView().getBaseImageView().setImage(fx);

            window.getCanvasView().getDrawCanvas().setWidth(fx.getWidth());
            window.getCanvasView().getDrawCanvas().setHeight(fx.getHeight());

            heatmapImage = null;
            cachedDpmGrids = null;
            state.clearCoverageMask();  // 새 도면 → 좌표계 달라지므로 사용 공간 마스크 무효화
            solverOverlayImage = null;
            invalidateSolverAll();
            if (legacyHeatmapTask != null) {
                legacyHeatmapTask.cancel(false);
                legacyHeatmapTask = null;
            }
            solverConfigDirty = true;
            stopSolver();

            viewportController.setBaseContentSize(fx.getWidth(), fx.getHeight());
            viewportController.setZoom(1.0);
            viewportController.updateViewportSize();
            viewportController.centerViewport();

            state.setTool(AppState.Tool.VIEW);
            window.getTopToolbar().setToolSelection(AppState.Tool.VIEW);
            toolsController.onToolChanged(AppState.Tool.VIEW);

            boolean presetLoaded = false;
            if (promptPathLossPreset) {
                presetLoaded = maybeLoadPresetForOnboarding(f);
                if (!presetLoaded) {
                    Double selectedN = chooseIndoorPathLossPreset();
                    if (selectedN != null) {
                        state.setPathLossN(selectedN);
                        env.setPathLossN(selectedN);
                    }
                }
            }

            if (promptPathLossPreset) {
                startGuidedOnboardingFlow();
            } else {
                stopPan();
                updateCursorByMode();
                render();
            }

        } catch (Exception ex) {
            showError("이미지 로드 실패: " + ex.getMessage());
        }
    }

    private boolean maybeLoadPresetForOnboarding(File floorplanFile) {
        File preset = findOnboardingPresetFile(floorplanFile);
        if (preset == null || !preset.exists()) {
            return false;
        }

        javafx.scene.control.ButtonType loadBtn =
                new javafx.scene.control.ButtonType("프리셋 불러오기", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType skipBtn =
                new javafx.scene.control.ButtonType("건너뛰기", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);

        javafx.scene.control.Alert ask = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "프리셋 파일을 찾았습니다.\n온보딩 전에 불러올까요?\n\n" + preset.getAbsolutePath(),
                loadBtn, skipBtn
        );
        ask.setTitle("프리셋 확인");
        ask.setHeaderText("미리 설정된 프리셋을 사용할 수 있습니다.");
        ask.initOwner(stage);
        Styles.styleAlert(ask);
        Optional<javafx.scene.control.ButtonType> result = ask.showAndWait();
        if (result.isEmpty() || result.get() != loadBtn) {
            return false;
        }

        return loadSettingsSnapshotFromFile(preset, true);
    }

    private File findOnboardingPresetFile(File floorplanFile) {
        if (floorplanFile == null) return null;
        File dir = floorplanFile.getParentFile();
        if (dir == null) return null;

        String name = floorplanFile.getName();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;

        List<File> candidates = List.of(
                new File(dir, base + ".wifisettings"),
                new File(dir, "onboarding-preset.wifisettings")
        );
        for (File c : candidates) {
            if (c.exists() && c.isFile()) return c;
        }
        return null;
    }

    private void startGuidedOnboardingFlow() {
        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0.0) {
            onboardingStep = OnboardingStep.SCALE;
            showInfo("""
                    시작 가이드
                    1) 스케일 툴에서 도면의 실제 길이를 두 점으로 찍으세요.
                    2) 왼쪽 패널에서 실제 거리(m)를 입력하고 '적용'을 누르세요.
                    """);
            activateTool(AppState.Tool.SCALE, true);
            return;
        }

        if (env.getWalls().isEmpty()) {
            onboardingStep = OnboardingStep.WALL;
            showInfo("""
                    시작 가이드
                    벽그리기 툴에서 집의 벽을 그린 뒤,
                    왼쪽 '벽 그리기 완료' 버튼을 누르면 공유기 위치를 추천해 드립니다.
                    """);
            activateTool(AppState.Tool.WALL, true);
            return;
        }

        onboardingStep = OnboardingStep.NONE;
        activateTool(AppState.Tool.VIEW, true);
    }

    private Double chooseIndoorPathLossPreset() {
        List<String> options = List.of(
                "텅 빈 공간 (n=2.5)",
                "기본 실내 (n=3.0, 추천)",
                "가구/차폐 많음 (n=3.5)",
                "사용자 설정..."
        );

        javafx.scene.control.ChoiceDialog<String> dlg =
                new javafx.scene.control.ChoiceDialog<>(options.get(1), options);
        dlg.setTitle("실내 감쇠 설정");
        dlg.setHeaderText("경로 손실 지수(n)를 선택하세요");
        dlg.setContentText("환경");
        dlg.initOwner(stage);
        Styles.styleDialog(dlg);

        Optional<String> selected = dlg.showAndWait();
        if (selected.isEmpty()) return null;

        String choice = selected.get();
        if (choice.startsWith("텅 빈")) return 2.5;
        if (choice.startsWith("기본")) return 3.0;
        if (choice.startsWith("가구")) return 3.5;

        String initial = String.format("%.2f", state.getPathLossN());
        while (true) {
            javafx.scene.control.TextInputDialog td = new javafx.scene.control.TextInputDialog(initial);
            td.setTitle("사용자 경로 손실 지수");
            td.setHeaderText("n 값을 입력하세요 (권장 2.0 ~ 4.5)");
            td.setContentText("n");
            td.initOwner(stage);
            Styles.styleDialog(td);
            Optional<String> raw = td.showAndWait();
            if (raw.isEmpty()) return null;

            double n = parseDouble(raw.get(), Double.NaN);
            if (Double.isFinite(n) && n >= 1.5 && n <= 6.0) {
                return n;
            }
            initial = raw.get();
            showError("유효한 숫자를 입력해주세요. (1.5 ~ 6.0)");
        }
    }

    /**
     * 저장 — currentSettingsFile이 있으면 같은 위치에 덮어쓰기, 없으면 Save As 다이얼로그.
     * 단축키: Cmd+S
     */
    private void saveSettingsSnapshot() {
        if (window.getCanvasView().getBaseImageView().getImage() == null) {
            showInfo("먼저 평면도를 열어주세요.");
            return;
        }
        if (currentSettingsFile != null && currentSettingsFile.getParentFile() != null
                && currentSettingsFile.getParentFile().exists()) {
            // 빠른 덮어쓰기 — 다이얼로그 없이
            writeSettingsTo(currentSettingsFile, /*verbose*/ false);
        } else {
            saveSettingsSnapshotAs();
        }
    }

    /**
     * 다른 이름으로 저장 — 항상 파일 선택 다이얼로그를 띄움.
     * 단축키: Cmd+Shift+S
     */
    private void saveSettingsSnapshotAs() {
        if (window.getCanvasView().getBaseImageView().getImage() == null) {
            showInfo("먼저 평면도를 열어주세요.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("다른 이름으로 저장");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Wi-Fi Settings", "*.wifisettings"));
        fc.setInitialFileName(currentSettingsFile != null
                ? currentSettingsFile.getName()
                : "wifi-settings.wifisettings");
        if (currentSettingsFile != null && currentSettingsFile.getParentFile() != null
                && currentSettingsFile.getParentFile().isDirectory()) {
            fc.setInitialDirectory(currentSettingsFile.getParentFile());
        }
        File out = fc.showSaveDialog(stage);
        if (out == null) return;

        if (!out.getName().toLowerCase().endsWith(".wifisettings")) {
            out = new File(out.getParentFile(), out.getName() + ".wifisettings");
        }
        writeSettingsTo(out, /*verbose*/ true);
    }

    /** 실제 ZIP 쓰기 로직 — 두 저장 경로(빠른/다른이름)의 공통 본체. */
    private void writeSettingsTo(File out, boolean verbose) {

        Properties p = new Properties();
        p.setProperty("version", "1");
        if (currentFloorplanFile != null) {
            p.setProperty("floorplan.path", currentFloorplanFile.getAbsolutePath());
        }
        if (Double.isFinite(env.getScaleMPerPx()) && env.getScaleMPerPx() > 0.0) {
            p.setProperty("scale.m_per_px", Double.toString(env.getScaleMPerPx()));
        }
        p.setProperty("state.calib_real_m", Double.toString(state.getCalibRealMeters()));
        p.setProperty("env.client_height_m", Double.toString(env.getClientHeightM()));
        p.setProperty("env.path_loss_n", Double.toString(state.getPathLossN()));

        p.setProperty("ap.count", Integer.toString(env.getAps().size()));
        for (int i = 0; i < env.getAps().size(); i++) {
            AP ap = env.getAps().get(i);
            if (ap == null) continue;
            String key = "ap." + i + ".";
            p.setProperty(key + "name", nullToEmpty(ap.name));
            p.setProperty(key + "x", Double.toString(ap.x));
            p.setProperty(key + "y", Double.toString(ap.y));
            p.setProperty(key + "height_m", Double.toString(ap.heightM));
            p.setProperty(key + "enabled", Boolean.toString(ap.enabled));

            for (Band band : Band.values()) {
                RadioConfig rc = ap.radios.get(band);
                if (rc == null) continue;
                String rk = key + "radio." + band.name() + ".";
                p.setProperty(rk + "enabled", Boolean.toString(rc.enabled));
                p.setProperty(rk + "ssid", nullToEmpty(rc.ssid));
                p.setProperty(rk + "tx_dbm", Double.toString(rc.txPowerDbm));
                p.setProperty(rk + "gain_dbi", Double.toString(rc.antennaGain));
                p.setProperty(rk + "channel", Integer.toString(rc.channel));
                p.setProperty(rk + "bandwidth_mhz", Integer.toString(rc.channelWidth));
            }
        }

        p.setProperty("wall.count", Integer.toString(env.getWalls().size()));
        for (int i = 0; i < env.getWalls().size(); i++) {
            Wall w = env.getWalls().get(i);
            if (w == null) continue;
            String key = "wall." + i + ".";
            p.setProperty(key + "x1", Double.toString(w.x1));
            p.setProperty(key + "y1", Double.toString(w.y1));
            p.setProperty(key + "x2", Double.toString(w.x2));
            p.setProperty(key + "y2", Double.toString(w.y2));
            WallMaterial material = (w.getMaterial() == null) ? WallMaterial.CUSTOM : w.getMaterial();
            p.setProperty(key + "material", material.name());
            p.setProperty(key + "att_24_db", Double.toString(w.attenuationDb24));
            p.setProperty(key + "att_5_db", Double.toString(w.attenuationDb5));
        }

        // ── ZIP 컨테이너로 저장 (settings.properties + floorplan.png) ──
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(new FileOutputStream(out))) {

            // 1) settings.properties — UTF-8 텍스트
            zos.putNextEntry(new java.util.zip.ZipEntry("settings.properties"));
            java.io.ByteArrayOutputStream propsBuf = new java.io.ByteArrayOutputStream();
            try (OutputStreamWriter writer = new OutputStreamWriter(propsBuf, StandardCharsets.UTF_8)) {
                p.store(writer, "Wi-Fi Heatmap settings snapshot");
            }
            zos.write(propsBuf.toByteArray());
            zos.closeEntry();

            // 2) floorplan.png — 평면도 이미지를 묶음 (있을 때만)
            if (floorplanBI != null) {
                zos.putNextEntry(new java.util.zip.ZipEntry("floorplan.png"));
                javax.imageio.ImageIO.write(floorplanBI, "png", zos);
                zos.closeEntry();
            }

            currentSettingsFile = out;
            rememberRecentFile(out);
            // 빠른 저장(Cmd+S)에선 BottomBar 토스트, 다른 이름으로는 다이얼로그
            if (verbose) {
                showInfo("저장 완료:\n" + out.getAbsolutePath()
                        + (floorplanBI != null ? "\n(평면도 이미지 포함)" : ""));
            } else {
                window.getBottomBar().showToast("저장됨 · " + out.getName());
            }
        } catch (Exception ex) {
            showError("저장 실패: " + ex.getMessage());
        }
    }

    private void openSettingsSnapshot() {
        FileChooser fc = new FileChooser();
        fc.setTitle("저장된 작업 불러오기");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Wi-Fi Settings", "*.wifisettings"));

        // 최근 파일이 있으면 그 폴더를 초기 디렉터리로 — 사용자 편의
        String recentPath = PREFS.get(PREFS_KEY_RECENT_FILE, null);
        if (recentPath != null) {
            File recent = new File(recentPath);
            File dir = recent.getParentFile();
            if (dir != null && dir.isDirectory()) fc.setInitialDirectory(dir);
        }

        File file = fc.showOpenDialog(stage);
        if (file == null) return;
        loadSettingsSnapshotFromFile(file, true);
    }

    private boolean loadSettingsSnapshotFromFile(File file, boolean showSuccessInfo) {
        if (file == null) return false;

        Properties p = new Properties();
        java.awt.image.BufferedImage embeddedFloorplan = null;

        // ZIP인지 plain properties인지 자동 감지 — 첫 4바이트가 PK 시그니처면 ZIP
        boolean isZip = isZipFile(file);

        try {
            if (isZip) {
                // ZIP 컨테이너 — settings.properties + (있으면) floorplan.png 추출
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(file)) {
                    java.util.zip.ZipEntry propsEntry = zf.getEntry("settings.properties");
                    if (propsEntry == null) {
                        showError("설정값 파일 형식이 올바르지 않습니다 (settings.properties 누락).");
                        return false;
                    }
                    try (InputStreamReader r = new InputStreamReader(
                            zf.getInputStream(propsEntry), StandardCharsets.UTF_8)) {
                        p.load(r);
                    }
                    java.util.zip.ZipEntry imgEntry = zf.getEntry("floorplan.png");
                    if (imgEntry != null) {
                        try (java.io.InputStream in = zf.getInputStream(imgEntry)) {
                            embeddedFloorplan = javax.imageio.ImageIO.read(in);
                        }
                    }
                }
            } else {
                // 구버전 — plain properties
                try (InputStreamReader reader = new InputStreamReader(
                        new FileInputStream(file), StandardCharsets.UTF_8)) {
                    p.load(reader);
                }
            }
        } catch (Exception ex) {
            showError("설정값 열기 실패: " + ex.getMessage());
            return false;
        }

        // ZIP에 평면도가 포함돼 있으면 외부 경로보다 그것을 우선 사용
        if (embeddedFloorplan != null) {
            floorplanBI = embeddedFloorplan;
            currentFloorplanFile = file;  // 설정파일 자체를 출처로 표시
            javafx.scene.image.Image fx = javafx.embed.swing.SwingFXUtils.toFXImage(embeddedFloorplan, null);
            window.getCanvasView().getBaseImageView().setImage(fx);
            window.getCanvasView().getDrawCanvas().setWidth(fx.getWidth());
            window.getCanvasView().getDrawCanvas().setHeight(fx.getHeight());
            viewportController.setBaseContentSize(fx.getWidth(), fx.getHeight());
            viewportController.setZoom(1.0);
            viewportController.updateViewportSize();
            viewportController.centerViewport();
            // applySettingsSnapshot 안의 floorplan.path 로드는 건너뛰도록 플래그
            p.remove("floorplan.path");
        }

        boolean ok = applySettingsSnapshot(p, showSuccessInfo);
        if (ok) {
            rememberRecentFile(file);
            currentSettingsFile = file;  // 이후 Cmd+S 빠른 저장에 사용
        }
        return ok;
    }

    /** 파일 시그니처로 ZIP 여부 판별 (PK\003\004) */
    private static boolean isZipFile(File f) {
        try (java.io.InputStream in = new FileInputStream(f)) {
            byte[] sig = new byte[4];
            int n = in.read(sig);
            return n == 4 && sig[0] == 'P' && sig[1] == 'K' && sig[2] == 0x03 && sig[3] == 0x04;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean applySettingsSnapshot(Properties p, boolean showSuccessInfo) {
        if (p == null) return false;

        String floorPath = trimToNull(p.getProperty("floorplan.path"));
        if (floorPath != null) {
            File floor = new File(floorPath);
            if (floor.exists()) {
                loadFloorplanFromFile(floor, false);
            } else {
                showInfo("저장된 평면도 파일을 찾지 못했습니다.\n경로: " + floorPath);
            }
        }

        double loadedScale = parseDouble(p.getProperty("scale.m_per_px"), Double.NaN);
        state.setScaleMPerPx(loadedScale);
        env.setScaleMPerPx(loadedScale);

        double calibMeters = parseDouble(p.getProperty("state.calib_real_m"), state.getCalibRealMeters());
        state.setCalibRealMeters(calibMeters);
        window.getLeftPanel().getRealMetersField().setText(Double.toString(calibMeters));

        double clientHeight = Math.max(0.1, parseDouble(p.getProperty("env.client_height_m"), env.getClientHeightM()));
        env.setClientHeightM(clientHeight);
        window.getBottomBar().setClientHeight(clientHeight);
        double loadedPathLossN = Math.max(1.0, parseDouble(p.getProperty("env.path_loss_n"), env.getPathLossN()));
        env.setPathLossN(loadedPathLossN);
        state.setPathLossN(loadedPathLossN);

        int apCount = Math.max(0, parseInt(p.getProperty("ap.count"), 0));
        List<AP> loadedAps = new ArrayList<>(apCount);
        for (int i = 0; i < apCount; i++) {
            String key = "ap." + i + ".";
            AP ap = new AP();
            ap.name = p.getProperty(key + "name", ap.name);
            ap.x = parseDouble(p.getProperty(key + "x"), ap.x);
            ap.y = parseDouble(p.getProperty(key + "y"), ap.y);
            ap.heightM = Math.max(0.1, parseDouble(p.getProperty(key + "height_m"), ap.heightM));
            ap.enabled = parseBoolean(p.getProperty(key + "enabled"), ap.enabled);
            for (Band band : Band.values()) {
                RadioConfig rc = ap.radios.get(band);
                if (rc == null) continue;
                String rk = key + "radio." + band.name() + ".";
                rc.enabled = parseBoolean(p.getProperty(rk + "enabled"), rc.enabled);
                rc.ssid = p.getProperty(rk + "ssid", rc.ssid);
                rc.txPowerDbm = RadioConfig.FIXED_TX_POWER_DBM;
                rc.antennaGain = parseDouble(p.getProperty(rk + "gain_dbi"), rc.antennaGain);
                rc.channel = parseInt(p.getProperty(rk + "channel"), rc.channel);
                rc.channelWidth = parseInt(p.getProperty(rk + "bandwidth_mhz"), rc.channelWidth);
            }
            loadedAps.add(ap);
        }

        int wallCount = Math.max(0, parseInt(p.getProperty("wall.count"), 0));
        List<Wall> loadedWalls = new ArrayList<>(wallCount);
        for (int i = 0; i < wallCount; i++) {
            String key = "wall." + i + ".";
            Wall w = new Wall();
            w.x1 = parseDouble(p.getProperty(key + "x1"), w.x1);
            w.y1 = parseDouble(p.getProperty(key + "y1"), w.y1);
            w.x2 = parseDouble(p.getProperty(key + "x2"), w.x2);
            w.y2 = parseDouble(p.getProperty(key + "y2"), w.y2);
            w.setMaterial(parseWallMaterial(p.getProperty(key + "material")));
            w.attenuationDb24 = parseDouble(p.getProperty(key + "att_24_db"), w.attenuationDb24);
            w.attenuationDb5 = parseDouble(p.getProperty(key + "att_5_db"), w.attenuationDb5);
            w.attenuationDb = w.attenuationDb24;
            loadedWalls.add(w);
        }

        env.getAps().setAll(loadedAps);
        env.getWalls().setAll(loadedWalls);
        toolsController.clearApSelection();
        toolsController.clearApInteraction();
        toolsController.clearWallSelection();

        heatmapImage = null;
        cachedDpmGrids = null;
        solverOverlayImage = null;
        invalidateSolverAll();
        if (legacyHeatmapTask != null) {
            legacyHeatmapTask.cancel(false);
            legacyHeatmapTask = null;
        }
        solverConfigDirty = true;
        stopSolver();
        activateTool(AppState.Tool.VIEW, true);

        // 일반 사용자 모드면 히트맵 엔진을 항상 DPM으로 복원 (구버전 파일 호환).
        // 고급 모드에서는 사용자가 명시적으로 LEGACY/FDTD_TEZ로 바꾼 상태를 존중.
        if (!state.isAdvancedMode()
                && state.getHeatmapModel() != AppState.HeatmapModel.DPM) {
            state.setHeatmapModel(AppState.HeatmapModel.DPM);
        }
        state.clearCoverageMask();  // 다른 환경 로드 → 사용 공간 마스크 무효화
        window.getLeftPanel().clearMeasurementResult();

        if (showSuccessInfo) {
            showInfo(String.format("설정값 불러오기 완료\nAP %d개, Wall %d개", loadedAps.size(), loadedWalls.size()));
        }
        return true;
    }

    private static String nullToEmpty(String value) {
        return (value == null) ? "" : value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null) return fallback;
        String v = raw.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v)) return true;
        if ("false".equals(v) || "0".equals(v) || "no".equals(v)) return false;
        return fallback;
    }

    private static WallMaterial parseWallMaterial(String raw) {
        if (raw == null || raw.isBlank()) return WallMaterial.CONCRETE_WALL;
        try {
            return WallMaterial.valueOf(raw.trim());
        } catch (Exception ignored) {
            return WallMaterial.CONCRETE_WALL;
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

        // 이미 실행 중이면 취소 후 재시작
        if (legacyHeatmapTask != null && legacyHeatmapTask.isRunning()) {
            legacyHeatmapTask.cancel(false);
        }

        int w = Math.max(1, (int) Math.round(window.getCanvasView().getDrawCanvas().getWidth()));
        int h = Math.max(1, (int) Math.round(window.getCanvasView().getDrawCanvas().getHeight()));

        HeatmapGenerator generator = new HeatmapGenerator(env,
                state.getHeatmapSolverMode(), state.getHeatmapModel(), state.getBandFilter());
        int gridStep = computeAdaptiveGridStep(env);
        int smoothRadius = computeAdaptiveSmoothRadius(gridStep);
        double legendMin = state.legendMinProperty().get();
        double legendMax = state.legendMaxProperty().get();

        long startMs = System.currentTimeMillis();
        window.getBottomBar().setHeatmapProgress(0);

        legacyHeatmapTask = new Task<>() {
            @Override
            protected HeatmapGenerator.HeatmapResult call() {
                return generator.generate(w, h, gridStep, legendMin, legendMax, smoothRadius,
                        progress -> Platform.runLater(() ->
                                window.getBottomBar().setHeatmapProgress(progress * 100.0)));
            }
        };

        legacyHeatmapTask.setOnSucceeded(e -> {
            HeatmapGenerator.HeatmapResult result = legacyHeatmapTask.getValue();
            legacyHeatmapTask = null;

            // DPM 그리드 캐시 저장 (마우스 호버 RSSI에서 사용)
            this.cachedDpmGrids = result.dpmGrids;

            // JavaFX Application Thread에서 WritableImage 생성 (안전)
            WritableImage img = new WritableImage(result.width, result.height);
            img.getPixelWriter().setPixels(0, 0, result.width, result.height,
                    PixelFormat.getIntArgbInstance(), result.argbPixels, 0, result.width);

            if (result.smoothRadiusPx > 0) {
                img = app.engine.WifiMath.boxBlur(img, result.smoothRadiusPx);
            }

            heatmapImage = img;

            double elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0;
            // DPM 모드면 측정값을 BottomBar에 표시 (발표 시연용), 아니면 일반 완료 메시지
            if (result.dpmExpandedNodes > 0) {
                window.getBottomBar().setDpmStats(
                        result.dpmExpandedNodes, result.dpmPrunedNodes, result.dpmElapsedMs);
            } else {
                window.getBottomBar().setHeatmapComplete(elapsedSec);
            }

            if (result.gpuFallback && !gpuFallbackWarned) {
                gpuFallbackWarned = true;
                showInfo("GPU 솔버를 찾지 못해 CPU로 계산했습니다.\n" +
                        "GPU 백엔드를 ServiceLoader로 추가하면 자동으로 사용됩니다.");
            }

            // 결과 카드 갱신 — 도면 전역 통계
            updateResultCardFromCurrentHeatmap(result.width, result.height);
            render();
        });
        legacyHeatmapTask.setOnFailed(e -> {
            legacyHeatmapTask = null;
            window.getBottomBar().clearHeatmapStatus();
            // 에러 발생 시 히트맵은 이전 상태 유지 (null로 초기화하지 않음)
        });
        legacyHeatmapTask.setOnCancelled(e -> {
            legacyHeatmapTask = null;
            window.getBottomBar().clearHeatmapStatus();
        });

        Thread worker = new Thread(legacyHeatmapTask, "legacy-heatmap-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 히트맵 생성 직후 격자 샘플링으로 측정 통계를 계산해 ResultCard에 표시.
     *
     * <p><b>측정 영역 정의</b>: 사용자가 AP 추천 단계에서 flood fill로 지정한
     * "사용 공간 마스크"({@link AppState#coverageContains}) 안의 점만 집계한다.
     * 여러 방을 클릭해 합산한 마스크이므로 와이파이를 실제로 쓰는 공간 전체를 덮는다.</p>
     *
     * <p><b>영역 미지정 시</b>: 어디서 와이파이가 필요한지 모르는 상태에서 비율을
     * 보여주면 오해를 부르므로, 결과 카드를 숨기고 영역 지정 안내만 표시한다.</p>
     */
    private void updateResultCardFromCurrentHeatmap(int width, int height) {
        if (env == null || env.getAps().isEmpty()) {
            window.getLeftPanel().clearMeasurementResult();
            return;
        }
        if (!state.hasCoverageArea()) {
            window.getLeftPanel().showMeasurementGuidance(
                    "측정할 공간이 지정되지 않았습니다. AP 추천에서 와이파이를 쓸 방을 선택하면 그 영역 기준으로 결과가 표시됩니다.");
            return;
        }

        // ── 측정 영역 = 사용 공간 마스크 안의 격자점만 ──
        final int step = 40;
        final double SIGNAL_VALID_THRESHOLD_DBM = -85.0;  // 외부·매우 약한 신호 제외

        int total = 0;
        int strong = 0, good = 0, weak = 0, dead = 0;
        for (int y = step / 2; y < height; y += step) {
            for (int x = step / 2; x < width; x += step) {
                if (!state.coverageContains(x, y)) continue;  // 사용 공간 밖 제외
                // DPM 모드: 실제 히트맵 생성에 사용된 DPM 그리드로 RSSI 계산 (일관성)
                // Legacy 모드 fallback: env.sampleRssiAt (반사·회절 포함)
                double rssi;
                if (state.getHeatmapModel() == AppState.HeatmapModel.DPM && cachedDpmGrids != null) {
                    List<RssiResult> dpmRows = sampleRssiFromDpmGrids(x, y);
                    rssi = dpmRows.isEmpty() ? Double.NaN : dpmRows.get(0).rssiDbm;
                } else {
                    rssi = env.sampleRssiAt(x, y);
                }
                if (!Double.isFinite(rssi)) continue;
                if (rssi < SIGNAL_VALID_THRESHOLD_DBM) continue;  // 매우 약한 신호 제외

                total++;
                if      (rssi >= -55) strong++;
                else if (rssi >= -65) good++;
                else if (rssi >= -75) weak++;
                else                  dead++;
            }
        }
        if (total == 0) {
            window.getLeftPanel().clearMeasurementResult();
            return;
        }
        double cover = 100.0 * (strong + good) / total;
        // LeftPanel.setMeasurementResult 인자: 좌→우 [약함, 주의, 양호, 강함] 순서
        double[] grade = {
                dead   / (double) total,
                weak   / (double) total,
                good   / (double) total,
                strong / (double) total
        };
        window.getLeftPanel().setMeasurementResult(cover, grade);
    }

    /**
     * 활성 AP의 최고 주파수 밴드에 따라 적절한 gridStep을 자동 결정.
     * 파장이 짧은 고주파수일수록 더 세밀한 격자가 필요함.
     *   6GHz: 3px (λ≈50mm, 가장 세밀) — ray 폭 감소, bilateral+gaussian 효과 극대화
     *   5GHz: 4px (λ≈60mm)
     *   2.4GHz: 6px (λ≈125mm) — 8→6px 감소로 ray 폭 축소, adaptive refinement 보완
     */
    private int computeAdaptiveGridStep(WifiEnvironment env) {
        boolean has6 = env.getAps().stream()
                .filter(ap -> ap != null && ap.enabled)
                .anyMatch(ap -> ap.radios.get(app.model.Band.GHZ_6) != null
                        && ap.radios.get(app.model.Band.GHZ_6).enabled);
        boolean has5 = env.getAps().stream()
                .filter(ap -> ap != null && ap.enabled)
                .anyMatch(ap -> ap.radios.get(app.model.Band.GHZ_5) != null
                        && ap.radios.get(app.model.Band.GHZ_5).enabled);
        if (has6) return 3;
        if (has5) return 4;
        return 6;
    }

    /** gridStep에 맞게 smoothRadius도 자동 연동. DPM은 cellPx 기반으로 더 넓게. */
    private int computeAdaptiveSmoothRadius(int gridStep) {
        if (state.getHeatmapModel() == AppState.HeatmapModel.DPM) {
            double scaleMPerPx = state.getScaleMPerPx();
            if (Double.isFinite(scaleMPerPx) && scaleMPerPx > 0) {
                double cellPx = DpmPathGrid.GRID_CELL_M / scaleMPerPx;
                return Math.min(50, Math.max(4, (int)(cellPx * 1.2)));
            }
        }
        return Math.max(4, gridStep);
    }

    private void syncModelParamsFromState() {
        env.setScaleMPerPx(state.getScaleMPerPx());
        env.setPathLossN(state.getPathLossN());
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
        solverConfigDirty = true;
        if (heatmapImage == null) {
            render();
            return;
        }
        heatmapRefreshDebounce.playFromStart();
    }

    private void invalidateSolverAll() {
        fdtdSolver = null;
        solverSourceSignature = Long.MIN_VALUE;
        solverMaterialSignature = Long.MIN_VALUE;
        solverScaleSignature = Double.NaN;
        solverBandSignature = null;
        solverStartWallNs = 0L;
        solverLastTelemetryNs = 0L;
        solverDebugText = "debug: -";
    }

    private void startSolver() {
        if (!ensureSolverReady(true)) return;
        if (solverRunning) return;
        // 시작 즉시 오버레이가 보이도록 짧게 워밍업한다.
        if (fdtdSolver != null && fdtdSolver.sourceCount() > 0) {
            fdtdSolver.step(24);
            solverOverlayImage = fdtdSolver.renderFrame();
            solverDebugText = fdtdSolver.visualDebugSummary();
        }
        solverRunning = true;
        solverStartWallNs = System.nanoTime();
        solverLastTelemetryNs = 0L;
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
        solverLastTelemetryNs = 0L;
    }

    private void resetSolver() {
        stopSolver();
        if (!ensureSolverReady(false)) {
            solverOverlayImage = null;
            solverDebugText = "debug: solver not ready";
            render();
            return;
        }
        fdtdSolver.reset();
        if (fdtdSolver.sourceCount() > 0) {
            fdtdSolver.step(24);
        }
        solverStartWallNs = System.nanoTime();
        solverLastTelemetryNs = 0L;
        solverFpsEma = Double.NaN;
        solverOverlayImage = fdtdSolver.renderFrame();
        solverDebugText = fdtdSolver.visualDebugSummary();
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
        Band solverBand = state.getBandFilter() == null ? null : state.getBandFilter().band;
        solverRenderFrameMod = Math.max(1, window.getLeftPanel().getSolverRenderSkip());
        long materialSig = computeMaterialSignature();
        long sourceSig = computeSourceSignature();
        double scaleSig = env.getScaleMPerPx();

        boolean geometryChanged = (fdtdSolver == null)
                || (fdtdSolver.widthPx() != w)
                || (fdtdSolver.heightPx() != h)
                || (fdtdSolver.cellPx() != cellPx);
        boolean bandChanged = (solverBandSignature != solverBand);
        boolean scaleChanged = Double.compare(scaleSig, solverScaleSignature) != 0;
        boolean materialChanged = (materialSig != solverMaterialSignature);
        boolean sourceChanged = (sourceSig != solverSourceSignature);

        boolean needRebuild = geometryChanged || bandChanged || scaleChanged || materialChanged;

        if (needRebuild) {
            System.out.printf(
                    "[SolverV2] rebuild(material/geometry) geo=%s band=%s scale=%s material=%s%n",
                    geometryChanged, bandChanged, scaleChanged, materialChanged
            );
            fdtdSolver = new SolverV2Engine(env, w, h, cellPx, solverBand);
            System.out.printf("[SolverV2] backend=%s%n", fdtdSolver.backendName());
            System.out.println(fdtdSolver.diagnosticsSummary());
            solverOverlayImage = fdtdSolver.renderFrame();
            solverDebugText = fdtdSolver.visualDebugSummary();
            solverFpsEma = Double.NaN;

            solverBandSignature = solverBand;
            solverScaleSignature = scaleSig;
            solverMaterialSignature = materialSig;
            solverSourceSignature = sourceSig;
            solverConfigDirty = false;
            return true;
        }

        if (sourceChanged) {
            System.out.println("[SolverV2] source-only refresh(AP/Radio changed)");
            fdtdSolver.refreshSources(env);
            System.out.println(fdtdSolver.diagnosticsSummary());
            solverOverlayImage = fdtdSolver.renderFrame();
            solverDebugText = fdtdSolver.visualDebugSummary();
            solverFpsEma = Double.NaN;
            solverSourceSignature = sourceSig;
        }
        if (fdtdSolver.sourceCount() <= 0) {
            solverOverlayImage = null;
            solverDebugText = "debug: no active source";
            if (showErrorIfMissingMap) {
                showInfo("선택된 표시 밴드에 활성 AP 라디오가 없습니다.\n밴드를 'All' 또는 활성 밴드로 바꿔주세요.");
            }
            return false;
        }
        solverConfigDirty = false;
        return true;
    }

    /**
     * 벽/스케일 변경 여부를 빠르게 판단하기 위한 시그니처.
     * 값이 바뀌면 재질 격자를 다시 만들어야 하므로 solver rebuild 대상으로 본다.
     */
    private long computeMaterialSignature() {
        long h = 0xcbf29ce484222325L;
        h = hashMix(h, Double.doubleToLongBits(env.getScaleMPerPx()));
        for (Wall w : env.getWalls()) {
            if (w == null) {
                h = hashMix(h, 0x13579BDF2468ACE0L);
                continue;
            }
            h = hashMix(h, Double.doubleToLongBits(w.x1));
            h = hashMix(h, Double.doubleToLongBits(w.y1));
            h = hashMix(h, Double.doubleToLongBits(w.x2));
            h = hashMix(h, Double.doubleToLongBits(w.y2));
            WallMaterial m = w.getMaterial();
            h = hashMix(h, (m == null ? -1L : m.ordinal()));
            h = hashMix(h, Double.doubleToLongBits(w.attenuationDb24));
            h = hashMix(h, Double.doubleToLongBits(w.attenuationDb5));
        }
        return h;
    }

    /**
     * AP/라디오 변경 여부를 빠르게 판단하기 위한 시그니처.
     * 값이 바뀌면 재질 격자 rebuild 없이 source만 refresh한다.
     */
    private long computeSourceSignature() {
        long h = 0xcbf29ce484222325L;
        for (AP ap : env.getAps()) {
            if (ap == null) {
                h = hashMix(h, 0xCAFEBABEDEADBEEFL);
                continue;
            }
            h = hashMix(h, (ap.enabled ? 1L : 0L));
            h = hashMix(h, Double.doubleToLongBits(ap.x));
            h = hashMix(h, Double.doubleToLongBits(ap.y));
            h = hashMix(h, Double.doubleToLongBits(ap.heightM));
            h = hashMix(h, ap.name == null ? 0L : ap.name.hashCode());
            for (Band band : Band.values()) {
                RadioConfig rc = ap.radios.get(band);
                h = hashMix(h, band.ordinal());
                if (rc == null) {
                    h = hashMix(h, -1L);
                    continue;
                }
                h = hashMix(h, (rc.enabled ? 1L : 0L));
                h = hashMix(h, rc.ssid == null ? 0L : rc.ssid.hashCode());
                h = hashMix(h, Double.doubleToLongBits(rc.txPowerDbm));
                h = hashMix(h, Double.doubleToLongBits(rc.antennaGain));
                h = hashMix(h, rc.channel);
                h = hashMix(h, rc.channelWidth);
            }
        }
        return h;
    }

    private static long hashMix(long hash, long value) {
        long h = hash ^ value;
        return h * 0x100000001b3L;
    }

    private void onSolverFrame(long now) {
        if (!solverRunning) return;

        if (solverConfigDirty && !ensureSolverReady(false)) {
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
            solverDebugText = fdtdSolver.visualDebugSummary();
            render();
        }

        if (solverLastTelemetryNs == 0L || now - solverLastTelemetryNs >= 1_000_000_000L) {
            solverLastTelemetryNs = now;
            var ms = fdtdSolver.materialStats();
            double runtimeSec = (solverStartWallNs <= 0L) ? 0.0 : (now - solverStartWallNs) / 1_000_000_000.0;
            double dtNs = fdtdSolver.dtSeconds() * 1.0e9;
            System.out.printf(
                    "[SolverV2] runtime=%.1fs step=%d fps=%s dx=%.4fm dt=%.4fns CFL=%.4f pml=%d src=%d materials(air=%d wall=%d door=%d window=%d) %s%n",
                    runtimeSec,
                    fdtdSolver.stepCount(),
                    Double.isFinite(solverFpsEma) ? String.format("%.1f", solverFpsEma) : "-",
                    fdtdSolver.dxMeters(),
                    dtNs,
                    fdtdSolver.courantNumber(),
                    fdtdSolver.pmlCells(),
                    fdtdSolver.sourceCount(),
                    ms.airCells(),
                    ms.wallCells(),
                    ms.doorCells(),
                    ms.windowCells(),
                    (solverDebugText == null ? "debug:-" : solverDebugText)
            );
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

            if (state.getTool() == AppState.Tool.SOLVER) {
                return;
            }

            // VIEW 모드: 벽 클릭 선택 (빈 곳 클릭 시 선택 해제)
            if (state.getTool() == AppState.Tool.VIEW) {
                toolsController.selectWallNear(e.getX(), e.getY(), this::render);
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

            // 온보딩 WALL 단계: 벽은 사용자가 원하는 만큼 그리고, 완료는 '벽 그리기 완료'
            // 버튼으로 명시적으로 알린다(→ finishWallsAndRecommend). 자동 전환하지 않는다.
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
        app.ui.Notify.error(stage, msg);
    }

    private void showInfo(String msg) {
        app.ui.Notify.info(stage, msg);
    }

    private List<RssiResult> currentMouseRssiRows() {
        if (!hasMouseProbe) return List.of();
        if (window.getCanvasView().getBaseImageView().getImage() == null) return List.of();
        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0.0) return List.of();

        // DPM 모드: 캐시된 Dijkstra 그리드에서 RSSI 계산
        if (state.getHeatmapModel() == AppState.HeatmapModel.DPM && cachedDpmGrids != null) {
            return sampleRssiFromDpmGrids((int) Math.round(mouseProbeX), (int) Math.round(mouseProbeY));
        }
        return env.sampleRssiAllAt((int) Math.round(mouseProbeX), (int) Math.round(mouseProbeY));
    }

    /**
     * DPM 그리드 캐시에서 특정 픽셀의 RSSI를 계산한다.
     * 히트맵 생성 시 사용한 것과 동일한 경로 손실 값을 사용하므로
     * 마우스 호버 RSSI와 히트맵 색상이 일치한다.
     */
    private List<RssiResult> sampleRssiFromDpmGrids(int px, int py) {
        var grids = this.cachedDpmGrids;
        if (grids == null) return List.of();
        List<RssiResult> results = new java.util.ArrayList<>();
        for (AP ap : env.getAps()) {
            if (ap == null || !ap.enabled) continue;
            var bandGrids = grids.get(ap);
            if (bandGrids == null) continue;
            for (Band b : Band.values()) {
                RadioConfig rc = ap.radios.get(b);
                if (rc == null || !rc.enabled) continue;
                DpmPathGrid grid = bandGrids.get(b);
                if (grid == null) continue;
                double plDb = grid.getPathLossDb(px, py);
                double rssi = rc.txPowerDbm + rc.antennaGain - plDb - rc.bandwidthPenaltyDb();
                results.add(new RssiResult(ap.name, rc.ssid, b, rssi));
            }
        }
        results.sort(java.util.Comparator.comparingDouble((RssiResult r) -> r.rssiDbm).reversed());
        return results;
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
