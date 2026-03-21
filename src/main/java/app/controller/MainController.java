package app.controller;

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
    private OnboardingStep onboardingStep = OnboardingStep.NONE;

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
                        onboardingStep = OnboardingStep.AP;
                        showInfo(scaleAppliedMsg + "\n\n다음 단계\nAP배치 툴에서 AP를 1개 찍어보세요.");
                        activateTool(AppState.Tool.AP, true);
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
        loadFloorplanFromFile(f, true);
    }

    private void loadFloorplanFromFile(File f, boolean promptPathLossPreset) {
        if (f == null) return;
        try {
            floorplanBI = ImageIO.read(f);
            if (floorplanBI == null) throw new IOException("이미지 로드 실패");
            currentFloorplanFile = f;

            Image fx = SwingFXUtils.toFXImage(floorplanBI, null);
            window.getCanvasView().getBaseImageView().setImage(fx);

            window.getCanvasView().getDrawCanvas().setWidth(fx.getWidth());
            window.getCanvasView().getDrawCanvas().setHeight(fx.getHeight());

            heatmapImage = null;
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

        if (env.getAps().isEmpty()) {
            onboardingStep = OnboardingStep.AP;
            showInfo("""
                    시작 가이드
                    AP배치 툴에서 AP를 1개 배치해보세요.
                    """);
            activateTool(AppState.Tool.AP, true);
            return;
        }

        if (env.getWalls().isEmpty()) {
            onboardingStep = OnboardingStep.WALL;
            showInfo("""
                    시작 가이드
                    벽그리기 툴에서 벽을 1개 그려보세요.
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

    private void saveSettingsSnapshot() {
        if (window.getCanvasView().getBaseImageView().getImage() == null) {
            showInfo("먼저 평면도를 열어주세요.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("설정값 저장");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Wi-Fi Settings", "*.wifisettings"));
        fc.setInitialFileName("wifi-settings.wifisettings");
        File out = fc.showSaveDialog(stage);
        if (out == null) return;

        if (!out.getName().toLowerCase().endsWith(".wifisettings")) {
            out = new File(out.getParentFile(), out.getName() + ".wifisettings");
        }

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

        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            p.store(writer, "Wi-Fi Heatmap settings snapshot");
            showInfo("설정값 저장 완료:\n" + out.getAbsolutePath());
        } catch (Exception ex) {
            showError("설정값 저장 실패: " + ex.getMessage());
        }
    }

    private void openSettingsSnapshot() {
        FileChooser fc = new FileChooser();
        fc.setTitle("설정값 열기");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Wi-Fi Settings", "*.wifisettings"));
        File file = fc.showOpenDialog(stage);
        if (file == null) return;
        loadSettingsSnapshotFromFile(file, true);
    }

    private boolean loadSettingsSnapshotFromFile(File file, boolean showSuccessInfo) {
        if (file == null) return false;

        Properties p = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (Exception ex) {
            showError("설정값 열기 실패: " + ex.getMessage());
            return false;
        }

        return applySettingsSnapshot(p, showSuccessInfo);
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
        solverOverlayImage = null;
        invalidateSolverAll();
        if (legacyHeatmapTask != null) {
            legacyHeatmapTask.cancel(false);
            legacyHeatmapTask = null;
        }
        solverConfigDirty = true;
        stopSolver();
        activateTool(AppState.Tool.VIEW, true);

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

        HeatmapGenerator generator = new HeatmapGenerator(env);
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

            // JavaFX Application Thread에서 WritableImage 생성 (안전)
            WritableImage img = new WritableImage(result.width, result.height);
            img.getPixelWriter().setPixels(0, 0, result.width, result.height,
                    PixelFormat.getIntArgbInstance(), result.argbPixels, 0, result.width);

            if (result.smoothRadiusPx > 0) {
                img = app.engine.WifiMath.boxBlur(img, result.smoothRadiusPx);
            }

            heatmapImage = img;

            double elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0;
            window.getBottomBar().setHeatmapComplete(elapsedSec);

            if (result.gpuFallback && !gpuFallbackWarned) {
                gpuFallbackWarned = true;
                showInfo("GPU 솔버를 찾지 못해 CPU로 계산했습니다.\n" +
                        "GPU 백엔드를 ServiceLoader로 추가하면 자동으로 사용됩니다.");
            }
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

    /** gridStep에 맞게 smoothRadius도 자동 연동 */
    private int computeAdaptiveSmoothRadius(int gridStep) {
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
        Band solverBand = window.getLeftPanel().getSolverDisplayBand();
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

            if (state.getTool() == AppState.Tool.VIEW || state.getTool() == AppState.Tool.SOLVER) {
                return;
            }

            AppState.Tool toolBeforeClick = state.getTool();
            int apCountBefore = env.getAps().size();
            int wallCountBefore = env.getWalls().size();

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

            boolean apCreated = (toolBeforeClick == AppState.Tool.AP) && (env.getAps().size() > apCountBefore);
            if (apCreated && onboardingStep == OnboardingStep.AP) {
                onboardingStep = OnboardingStep.WALL;
                showInfo("AP 배치 완료\n\n다음 단계\n벽그리기 툴에서 벽을 1개 그려보세요.");
                activateTool(AppState.Tool.WALL, true);
                return;
            }

            boolean wallCreated = (toolBeforeClick == AppState.Tool.WALL) && (env.getWalls().size() > wallCountBefore);
            if (wallCreated && onboardingStep == OnboardingStep.WALL) {
                onboardingStep = OnboardingStep.NONE;
                showInfo("""
                        벽 그리기 완료

                        기본 설정이 끝났습니다.
                        이제 Solver 툴로 이동해 'Solver 시작'을 눌러
                        전파 오버레이를 바로 확인해보세요.
                        """);
                activateTool(AppState.Tool.SOLVER, true);
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
        var alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR, msg,
                javafx.scene.control.ButtonType.OK);
        alert.initOwner(stage);
        Styles.styleAlert(alert);
        alert.showAndWait();
    }

    private void showInfo(String msg) {
        var alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION, msg,
                javafx.scene.control.ButtonType.OK);
        alert.initOwner(stage);
        Styles.styleAlert(alert);
        alert.showAndWait();
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
