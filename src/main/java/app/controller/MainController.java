package app.controller;

import app.engine.DpmPathGrid;
import app.engine.HeatmapGenerator;
import app.io.ProjectFile;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
        window.setMenuBar(buildMenuBar());
    }

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    /**
     * 메뉴 막대 — macOS 에서는 화면 맨 위 시스템 메뉴 막대에 들어간다.
     *
     * <p>단축키(⌘S·⌘O 등)를 macOS 네이티브 앱과 같은 방식으로 메뉴가 처리하고, 단축키를 모르는
     * 사용자도 기능을 메뉴에서 찾을 수 있다. 다른 OS 에서는 창 안 맨 위에 보통 메뉴 막대로 나온다.</p>
     */
    private javafx.scene.control.MenuBar buildMenuBar() {
        javafx.scene.control.Menu file = new javafx.scene.control.Menu("파일");
        file.getItems().addAll(
                menuItem("평면도 열기…", "Shortcut+Shift+O", this::openFloorplan),
                menuItem("작업 불러오기…", "Shortcut+O", this::openSettingsSnapshot),
                new javafx.scene.control.SeparatorMenuItem(),
                menuItem("저장", "Shortcut+S", this::saveSettingsSnapshot),
                menuItem("다른 이름으로 저장…", "Shortcut+Shift+S", this::saveSettingsSnapshotAs));

        javafx.scene.control.Menu view = new javafx.scene.control.Menu("보기");
        view.getItems().addAll(
                menuItem("신호 지도 만들기", "Shortcut+R", this::generateHeatmapNow),
                new javafx.scene.control.SeparatorMenuItem(),
                menuItem("화면에 맞춤", "Shortcut+0", this::zoomToFit),
                menuItem("확대", "Shortcut+Equals", () -> zoomAtViewportCenter(1.10)),
                menuItem("축소", "Shortcut+Minus", () -> zoomAtViewportCenter(1.0 / 1.10)),
                new javafx.scene.control.SeparatorMenuItem(),
                menuItem("다크 모드 전환", "Shortcut+Shift+D", () -> Styles.setDark(!Styles.isDark())));

        javafx.scene.control.Menu tools = new javafx.scene.control.Menu("도구");
        tools.getItems().addAll(
                menuItem("축척 맞추기", "Shortcut+1", () -> activateTool(AppState.Tool.SCALE, true)),
                menuItem("벽 그리기", "Shortcut+2", () -> activateTool(AppState.Tool.WALL, true)),
                menuItem("공유기 배치", "Shortcut+3", () -> activateTool(AppState.Tool.AP, true)),
                new javafx.scene.control.SeparatorMenuItem(),
                menuItem("공유기 자리 추천…", "Shortcut+Shift+R", this::openApRecommender));

        javafx.scene.control.MenuBar bar = new javafx.scene.control.MenuBar(file, view, tools);
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    private static javafx.scene.control.MenuItem menuItem(String text, String accelerator, Runnable action) {
        javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(text);
        item.setAccelerator(javafx.scene.input.KeyCombination.keyCombination(accelerator));
        item.setOnAction(e -> action.run());
        return item;
    }

    private void zoomToFit() {
        if (window.getCanvasView().getBaseImageView().getImage() == null) {
            showToast("먼저 평면도를 불러와 주세요.");
            return;
        }
        double w = window.getCanvasView().getDrawCanvas().getWidth();
        double h = window.getCanvasView().getDrawCanvas().getHeight();
        viewportController.fitToViewport(20, w, h);
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
        // macOS 메뉴 막대는 포커스를 가진 창의 것으로 바뀐다 — 환영 창이 닫히면 주 창으로 확실히 돌린다
        stage.requestFocus();

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

        // (수신 단말 높이 입력은 제거됨 — DPM·FDTD 모두 2차원이라 높이가 결과에 반영되지 않았다.
        //  env.clientHeightM 은 설정 파일 호환과 Legacy 기준선용으로 불러오기만 한다.)
        window.getBottomBar().setCurrentRssi(Double.NaN);

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

        // 히트맵은 DPM 전용 — 엔진 선택 UI 는 없다. Legacy 는 발표 수치 재현(MeasureHarness)과
        // 회귀 테스트용 기준선으로 엔진에만 남아 있고, FDTD 는 "전파 흐름 보기"로 제공한다.
        // (예전 모델 콤보의 "FDTD" 항목은 FDTD 히트맵 경로가 끊겨 실제로는 Legacy 를 그렸다.)

        // 밴드 필터 — AppState 가 단일 진실 원천. 툴바·설정 불러오기는 state 만 바꾸고,
        // 리스너가 툴바 표시 동기화와 히트맵 무효화·재생성을 한곳에서 처리한다.
        window.getTopToolbar().setBandFilter(state.getBandFilter());
        window.getTopToolbar().setOnBandFilterChanged(filter -> {
            if (filter != null) state.setBandFilter(filter);
        });
        state.bandFilterProperty().addListener((obs, oldV, newV) -> {
            window.getTopToolbar().setBandFilter(newV);
            invalidateHeatmapForSettingChange();
        });

        // 고급 모드 ↔ TopToolbar 가시성 양방향 바인딩
        window.getTopToolbar().bindAdvancedMode(state.advancedModeProperty());
        window.getLeftPanel().setOnNotify(this::showToast);
        // 진행 단계를 누르면 그 단계의 도구로
        window.getLeftPanel().getStepIndicator().setOnStepClicked(step -> {
            switch (step) {
                case 0 -> openFloorplan();
                case 1 -> activateTool(AppState.Tool.SCALE, true);
                case 2 -> activateTool(AppState.Tool.WALL, true);
                case 3 -> activateTool(AppState.Tool.AP, true);
                case 4 -> generateHeatmapNow();
                default -> { }
            }
        });
        // 솔버 카드의 스텝·fps·디버그 수치도 고급 모드에서만
        window.getLeftPanel().setAdvancedDetailsVisible(state.isAdvancedMode());
        state.advancedModeProperty().addListener((obs, oldV, newV) -> {
            window.getLeftPanel().setAdvancedDetailsVisible(newV);
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

            window.getTopToolbar().setOnZoomFit(this::zoomToFit);
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
                        showToast("도면에서 길이를 아는 두 점을 먼저 찍어 주세요.");
                        stopPan();
                        updateCursorByMode();
                        render();
                        return;
                    }

                    double mPerPx = state.getScaleMPerPx();
                    env.setScaleMPerPx(mPerPx);
                    solverConfigDirty = true;
                    // (예전엔 "1px = 0.023729 m (0.934205 inch)" 를 모달로 띄웠다 — 사용자에겐 의미 없는 수치)
                    String scaleAppliedMsg = String.format("도면 축척을 맞췄습니다 (기준 길이 %.1f m).",
                            state.getCalibRealMeters());

                    if (onboardingStep == OnboardingStep.SCALE) {
                        onboardingStep = OnboardingStep.WALL;
                        showToast(scaleAppliedMsg + " 이제 벽을 그려 주세요.");
                        activateTool(AppState.Tool.WALL, true);
                        return;
                    }

                    showToast(scaleAppliedMsg);

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

        WallMaterial current = (wall.getMaterial() == null) ? WallMaterial.CONCRETE_WALL : wall.getMaterial();
        WallMaterial picked = app.ui.Sheet.choose(stage,
                "벽 재질을 골라 주세요",
                "재질마다 신호가 벽을 지날 때 약해지는 정도가 달라요.",
                java.util.Arrays.asList(WallMaterial.values()),
                WallMaterial::labelKo,
                m -> String.format("2.4 GHz −%.0f dB · 5 GHz −%.0f dB",
                        m.defaultAttenuation24Db(), m.defaultAttenuation5Db()),
                current);
        if (picked != null) {
            wall.setMaterial(picked);
            scheduleHeatmapRefreshIfVisible();
            render();
        }
    }


    private void openApRecommender() {
        Image img = window.getCanvasView().getBaseImageView().getImage();
        if (img == null) {
            showToast("먼저 평면도를 불러와 주세요.");
            return;
        }
        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0) {
            showToast("먼저 도면 축척을 맞춰 주세요.");
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

        app.dialog.ApRecommendDialog.show(stage, img, w, h, env, state, preset, positions ->
                // onApply 는 추천 창이 닫히기 전에 호출된다 — 확인 창이 모달끼리 겹치지 않도록 뒤로 미룬다.
                Platform.runLater(() -> applyRecommendedAps(positions, preset)));
    }

    /**
     * 추천 위치에 AP 를 배치한다.
     *
     * <p>추천은 "이 공간을 AP N대로 덮는 배치"로, 기존 AP 는 계산에 넣지 않았다.
     * 그래서 기존 AP 가 있으면 교체할지 추가할지 묻는다 — 예전엔 무조건 추가해서
     * AP 2대를 추천받아도 기존 AP 와 합쳐 4대가 되었고, 사용자가 놓은 AP 를
     * 말없이 지우는 것도 곤란하기 때문이다.</p>
     */
    private void applyRecommendedAps(List<javafx.geometry.Point2D> positions, app.model.ApPreset preset) {
        if (positions == null || positions.isEmpty()) return;

        if (!env.getAps().isEmpty()) {
            String choice = app.ui.Sheet.show(stage,
                    "지금 있는 공유기는 어떻게 할까요?",
                    String.format("추천은 공유기 %d대만으로 계산한 배치예요.%n교체하면 지금 놓인 %d대는 지워져요.",
                            positions.size(), env.getAps().size()),
                    null, 400, true,
                    app.ui.Sheet.Action.secondary("add", "함께 두기"),
                    app.ui.Sheet.Action.primary("replace", "교체하기"));
            if (choice == null) return;
            if (choice.equals("replace")) {
                toolsController.clearApSelection();
                env.getAps().clear();
            }
        }

        for (javafx.geometry.Point2D pos : positions) {
            AP ap = new AP();
            ap.name = nextApName();  // 직접 놓은 공유기와 같은 "공유기 N" (예전엔 "추천-N")
            ap.resetSsidsFromName();  // 생성자는 "AP-1" 기준 SSID — 이름에 맞춰 중복 방지
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
    }

    /** 아직 쓰이지 않은 가장 작은 "공유기 N" 이름. */
    private String nextApName() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (AP a : env.getAps()) if (a != null && a.name != null) used.add(a.name.trim());
        int n = 1;
        while (used.contains("공유기 " + n)) n++;
        return "공유기 " + n;
    }

    /**
     * 사용 중인 공유기(통신사 프리셋)를 묻는다. 선택한 프리셋은 추천 AP의 RadioConfig에 적용된다.
     * @return 선택한 프리셋, 취소 시 null
     */
    private app.model.ApPreset chooseRouterPreset() {
        return app.ui.Sheet.choose(stage,
                "어떤 공유기를 쓰고 있나요?",
                "고른 공유기의 출력과 안테나로 자리를 계산해요.",
                java.util.Arrays.asList(app.model.ApPreset.values()),
                p -> p.label,
                MainController::presetSpec,
                lastRecommendPreset);
    }

    /** 프리셋 한 줄 요약 — 예: "2.4 GHz 20 dBm · 5 dBi / 5 GHz 20 dBm · 6 dBi". */
    private static String presetSpec(app.model.ApPreset p) {
        AP probe = new AP();
        p.applyTo(probe);
        StringBuilder sb = new StringBuilder();
        for (Band b : new Band[]{Band.GHZ_24, Band.GHZ_5}) {
            RadioConfig rc = probe.radios.get(b);
            if (rc == null || !rc.enabled) continue;
            if (sb.length() > 0) sb.append("  /  ");
            sb.append(String.format("%s %.0f dBm · %.0f dBi", b.label, rc.txPowerDbm, rc.antennaGain));
        }
        if (p == app.model.ApPreset.CUSTOM) sb.append("  (기본값)");
        return sb.toString();
    }

    /**
     * '벽 그리기 완료' 버튼 핸들러 — 벽이 충분한지 확인 후 AP 추천 흐름으로 진입.
     */
    private void finishWallsAndRecommend() {
        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0) {
            showToast("먼저 도면 축척을 맞춰 주세요.");
            return;
        }
        if (env.getWalls().isEmpty()) {
            showToast("먼저 벽을 1개 이상 그려 주세요.");
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
                // FDTD 디버그 수치(max/outMax/vis …)는 고급 모드에서만 캔버스에 표시
                (state.getTool() == AppState.Tool.SOLVER && state.isAdvancedMode()) ? solverDebugText : null
        );

        // 단계별 가이드 오버레이 갱신
        boolean hasFloorplan = window.getCanvasView().getBaseImageView().getImage() != null;
        // 진행 단계: ① 도면 ② 축척 ③ 벽 ④ 공유기 ⑤ 신호 지도
        window.getLeftPanel().getStepIndicator().setDone(
                hasFloorplan,
                Double.isFinite(env.getScaleMPerPx()) && env.getScaleMPerPx() > 0,
                !env.getWalls().isEmpty(),
                !env.getAps().isEmpty(),
                heatmapImage != null);
        // 도면이 없으면 확대할 대상이 없으므로 줌 컨트롤도 숨긴다
        javafx.scene.Node zoom = window.getTopToolbar().getZoomControls();
        if (zoom != null) zoom.setVisible(hasFloorplan);
        boolean hasAps = !env.getAps().isEmpty();
        boolean hasHeatmap = (heatmapImage != null) || (visibleSolverOverlay != null);
        window.getCanvasView().updateGuideOverlay(hasFloorplan, hasAps, hasHeatmap);
    }

    private void installSceneShortcuts(Scene scene) {
        scene.setOnKeyPressed(e -> {
            // ── 파일 단축키 (Ctrl+O / Ctrl+S / Ctrl+Shift+S) — macOS 외 ─────────
            // macOS 에서는 시스템 메뉴 막대(buildMenuBar)의 단축키가 처리한다 — 여기서도 처리하면 두 번 실행된다.
            if (e.isShortcutDown() && !IS_MAC) {
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

            // (예전엔 여기서 "경로 손실 지수 n" 을 물었는데, n 은 Legacy 모델에만 쓰이고
            //  DPM 은 자유공간 n=2 고정이라 답이 결과에 아무 영향도 없었다 — 질문 제거.)
            if (promptPathLossPreset) {
                maybeLoadPresetForOnboarding(f);
            }

            if (promptPathLossPreset) {
                startGuidedOnboardingFlow();
            } else {
                stopPan();
                updateCursorByMode();
                render();
            }

        } catch (Exception ex) {
            showError("평면도를 열지 못했어요", "이미지 파일이 손상됐거나 지원하지 않는 형식이에요.\n" + ex.getMessage());
        }
    }

    private boolean maybeLoadPresetForOnboarding(File floorplanFile) {
        File preset = findOnboardingPresetFile(floorplanFile);
        if (preset == null || !preset.exists()) {
            return false;
        }

        boolean load = app.ui.Sheet.confirm(stage,
                "저장해 둔 작업을 불러올까요?",
                "이 평면도와 같은 폴더에서 작업 파일을 찾았어요.\n" + preset.getName(),
                "불러오기", "건너뛰기");
        if (!load) {
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
            showToast("1단계 · 도면에서 길이를 아는 두 점을 찍고, 왼쪽에 실제 길이(m)를 입력한 뒤 [적용]을 누르세요.");
            activateTool(AppState.Tool.SCALE, true);
            return;
        }

        if (env.getWalls().isEmpty()) {
            onboardingStep = OnboardingStep.WALL;
            showToast("2단계 · 벽을 따라 클릭해 그려 주세요. 다 그리면 왼쪽 [벽 그리기 완료]를 눌러 공유기 위치를 추천받으세요.");
            activateTool(AppState.Tool.WALL, true);
            return;
        }

        onboardingStep = OnboardingStep.NONE;
        activateTool(AppState.Tool.VIEW, true);
    }

    /**
     * 저장 — currentSettingsFile이 있으면 같은 위치에 덮어쓰기, 없으면 Save As 다이얼로그.
     * 단축키: Cmd+S
     */
    private void saveSettingsSnapshot() {
        if (window.getCanvasView().getBaseImageView().getImage() == null) {
            showToast("먼저 평면도를 불러와 주세요.");
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
            showToast("먼저 평면도를 불러와 주세요.");
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

    /** 작업 파일 쓰기 — 두 저장 경로(빠른/다른 이름)의 공통 본체. 형식은 {@link ProjectFile}. */
    private void writeSettingsTo(File out, boolean verbose) {
        ProjectFile.Snapshot snapshot = new ProjectFile.Snapshot(
                currentFloorplanFile != null ? currentFloorplanFile.getAbsolutePath() : null,
                floorplanBI,
                env.getScaleMPerPx(),
                state.getCalibRealMeters(),
                env.getClientHeightM(),
                state.getPathLossN(),
                env.getAps(), env.getWalls());
        try {
            ProjectFile.write(out, snapshot);
            currentSettingsFile = out;
            rememberRecentFile(out);
            showToast("저장했습니다 · " + out.getName()
                    + (verbose && floorplanBI != null ? " (평면도 포함)" : ""));
        } catch (Exception ex) {
            showError("저장하지 못했어요", ex.getMessage());
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

        ProjectFile.Snapshot snapshot;
        try {
            snapshot = ProjectFile.read(file);
        } catch (ProjectFile.FormatException ex) {
            showError("작업 파일을 열 수 없어요", ex.getMessage());
            return false;
        } catch (Exception ex) {
            showError("작업 파일을 열지 못했어요", ex.getMessage());
            return false;
        }

        // 파일에 평면도가 들어 있으면 외부 경로보다 그것을 우선 사용
        if (snapshot.floorplan() != null) {
            floorplanBI = snapshot.floorplan();
            currentFloorplanFile = file;  // 작업 파일 자체를 출처로 표시
            javafx.scene.image.Image fx = javafx.embed.swing.SwingFXUtils.toFXImage(floorplanBI, null);
            window.getCanvasView().getBaseImageView().setImage(fx);
            window.getCanvasView().getDrawCanvas().setWidth(fx.getWidth());
            window.getCanvasView().getDrawCanvas().setHeight(fx.getHeight());
            viewportController.setBaseContentSize(fx.getWidth(), fx.getHeight());
            viewportController.setZoom(1.0);
            viewportController.updateViewportSize();
            viewportController.centerViewport();
        } else if (snapshot.floorplanPath() != null) {
            File floor = new File(snapshot.floorplanPath());
            if (floor.exists()) {
                loadFloorplanFromFile(floor, false);
            } else {
                showInfo("평면도 파일을 찾지 못했어요",
                        "작업은 불러왔지만 평면도 이미지가 원래 자리에 없어요. 평면도를 다시 열어 주세요.\n" + snapshot.floorplanPath());
            }
        }

        applySnapshot(snapshot);
        rememberRecentFile(file);
        currentSettingsFile = file;  // 이후 Cmd+S 빠른 저장에 사용
        if (showSuccessInfo) {
            showToast(String.format("불러왔습니다 · 공유기 %d대 · 벽 %d개",
                    snapshot.aps().size(), snapshot.walls().size()));
        }
        return true;
    }

    /** 불러온 내용을 상태·환경에 반영하고, 이전 작업의 계산 결과·선택을 모두 비운다. */
    private void applySnapshot(ProjectFile.Snapshot s) {
        state.setScaleMPerPx(s.scaleMPerPx());
        env.setScaleMPerPx(s.scaleMPerPx());

        double calibMeters = Double.isNaN(s.calibRealMeters()) ? state.getCalibRealMeters() : s.calibRealMeters();
        state.setCalibRealMeters(calibMeters);
        window.getLeftPanel().getRealMetersField().setText(Double.toString(calibMeters));

        if (!Double.isNaN(s.clientHeightM())) env.setClientHeightM(s.clientHeightM());
        double pathLossN = Double.isNaN(s.pathLossN()) ? env.getPathLossN() : s.pathLossN();
        env.setPathLossN(pathLossN);
        state.setPathLossN(pathLossN);

        env.getAps().setAll(s.aps());
        env.getWalls().setAll(s.walls());
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

        state.clearCoverageMask();  // 다른 환경 로드 → 사용 공간 마스크 무효화
        window.getLeftPanel().clearMeasurementResult();
    }

    private void generateHeatmapNow() {
        if (window.getCanvasView().getBaseImageView().getImage() == null) {
            showToast("먼저 평면도를 불러와 주세요.");
            return;
        }

        syncModelParamsFromState();

        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0.0) {
            showToast("먼저 도면 축척을 맞춰 주세요.");
            return;
        }

        // 이미 실행 중이면 취소 후 재시작
        if (legacyHeatmapTask != null && legacyHeatmapTask.isRunning()) {
            legacyHeatmapTask.cancel(false);
        }

        int w = Math.max(1, (int) Math.round(window.getCanvasView().getDrawCanvas().getWidth()));
        int h = Math.max(1, (int) Math.round(window.getCanvasView().getDrawCanvas().getHeight()));

        HeatmapGenerator generator = new HeatmapGenerator(env, state.getBandFilter().band);
        int gridStep = computeAdaptiveGridStep(env);
        int smoothRadius = computeAdaptiveSmoothRadius(gridStep);

        long startMs = System.currentTimeMillis();
        window.getBottomBar().setHeatmapProgress(0);

        // 핸들러에서는 필드(legacyHeatmapTask)가 아니라 이 지역 변수(task)를 기준으로 판단한다.
        // 설정을 연달아 바꾸면 옛 작업의 onCancelled 가 나중에 도착하는데, 필드를 무조건
        // null 로 만들면 새 작업의 참조가 사라져 다음 변경 때 취소하지 못하고, 옛 작업이
        // 새 작업의 진행률 표시까지 지워 버린다.
        final Task<HeatmapGenerator.HeatmapResult> task = new Task<>() {
            @Override
            protected HeatmapGenerator.HeatmapResult call() {
                return generator.generate(w, h, gridStep, smoothRadius,
                        progress -> {
                            // 생성기는 취소를 확인하지 않고 끝까지 돈다 — 교체된 작업의
                            // 진행률이 새 작업의 진행률 막대를 덮어쓰지 않게 한다.
                            if (isCancelled()) return;
                            Platform.runLater(() ->
                                    window.getBottomBar().setHeatmapProgress(progress * 100.0));
                        });
            }
        };
        legacyHeatmapTask = task;

        task.setOnSucceeded(e -> {
            if (legacyHeatmapTask != task) return;  // 이미 새 작업으로 교체됨
            HeatmapGenerator.HeatmapResult result = task.getValue();
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
            // 흐림이 외벽 바깥으로 ~15px 번지므로, 경계를 벽선에 맞춰 다시 자른다
            if (result.buildingMask != null) {
                int iw = (int) img.getWidth(), ih = (int) img.getHeight();
                int[] px = new int[iw * ih];
                img.getPixelReader().getPixels(0, 0, iw, ih, PixelFormat.getIntArgbInstance(), px, 0, iw);
                result.buildingMask.clip(px, iw, ih);
                img.getPixelWriter().setPixels(0, 0, iw, ih, PixelFormat.getIntArgbInstance(), px, 0, iw);
            }

            heatmapImage = img;

            double elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0;
            // DPM 모드면 측정값을 BottomBar에 표시 (발표 시연용), 아니면 일반 완료 메시지
            // DPM 탐색 측정값(발표용)은 고급 모드에서만 — 일반 사용자에겐 완료 메시지
            if (result.dpmExpandedNodes > 0 && state.isAdvancedMode()) {
                window.getBottomBar().setDpmStats(
                        result.dpmExpandedNodes, result.dpmPrunedNodes, result.dpmElapsedMs);
            } else {
                window.getBottomBar().setHeatmapComplete(elapsedSec);
            }

            // 결과 카드 갱신 — 도면 전역 통계
            updateResultCardFromCurrentHeatmap(result.width, result.height);
            render();
        });
        task.setOnFailed(e -> {
            if (legacyHeatmapTask != task) return;
            legacyHeatmapTask = null;
            window.getBottomBar().clearHeatmapStatus();
            // 에러 발생 시 히트맵은 이전 상태 유지 (null로 초기화하지 않음)
        });
        task.setOnCancelled(e -> {
            if (legacyHeatmapTask != task) return;  // 새 작업으로 교체되며 취소된 경우 — 건드리지 않음
            legacyHeatmapTask = null;
            window.getBottomBar().clearHeatmapStatus();
        });

        Thread worker = new Thread(task, "legacy-heatmap-worker");
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
                    "측정할 공간이 지정되지 않았습니다. 공유기 자리 추천에서 와이파이를 쓸 방을 선택하면 그 영역 기준으로 결과가 표시됩니다.");
            return;
        }

        // ── 측정 영역 = 사용 공간 마스크 안의 격자점만 ──
        final int step = 40;

        int total = 0;
        int strong = 0, good = 0, weak = 0, dead = 0;
        for (int y = step / 2; y < height; y += step) {
            for (int x = step / 2; x < width; x += step) {
                if (!state.coverageContains(x, y)) continue;  // 사용 공간 밖 제외
                // 히트맵 생성에 쓴 DPM 그리드로 계산 — 색과 통계가 같은 모델에서 나온다.
                // (예전엔 그리드가 없으면 Legacy 로 몰래 대체 계산했다.)
                List<RssiResult> dpmRows = sampleRssiFromDpmGrids(x, y);
                double rssi = dpmRows.isEmpty() ? Double.NaN : dpmRows.get(0).rssiDbm;
                if (!Double.isFinite(rssi)) continue;
                // 등급 기준은 RssiLevel 한 곳 — 히트맵 색·범례와 같은 의미.
                // (예전엔 -85 dBm 미만을 "매우 약한 신호"로 분모에서 빼서, 지정한 공간 안의
                //  진짜 사각지대가 통계에서 사라지고 커버율이 실제보다 높게 나왔다.)
                total++;
                switch (app.model.RssiLevel.of(rssi)) {
                    case STRONG -> strong++;
                    case GOOD   -> good++;
                    case WEAK   -> weak++;
                    case DEAD   -> dead++;
                }
            }
        }
        if (total == 0) {
            window.getLeftPanel().clearMeasurementResult();
            return;
        }
        // "충분한 곳" = 자리 추천 목표와 같은 등급(RssiLevel.TARGET) 이상
        double cover = 100.0 * strong / total;
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
        double scaleMPerPx = state.getScaleMPerPx();
        if (Double.isFinite(scaleMPerPx) && scaleMPerPx > 0) {
            double cellPx = DpmPathGrid.GRID_CELL_M / scaleMPerPx;
            return Math.min(50, Math.max(4, (int)(cellPx * 1.2)));
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

    /**
     * 모델·솔버·밴드처럼 계산 결과 자체를 바꾸는 설정이 변경됐을 때 호출.
     *
     * <p>이전 설정으로 만든 히트맵과 DPM 캐시는 즉시 버린다 — 새 밴드를 골랐는데 옛 밴드
     * 색을 계속 보여주면 안 되기 때문. 그리고 히트맵이 보이던 중이었거나 생성 중이었다면
     * 새 설정으로 바로 다시 생성한다.</p>
     *
     * <p>"보이던 중인지"는 반드시 지우기 <b>전에</b> 판정해야 한다. 예전 코드는
     * {@code heatmapImage = null} 을 먼저 한 뒤 {@link #scheduleHeatmapRefreshIfVisible()}
     * 로 판정해서 항상 "안 보임"이 되어, 설정을 바꾸면 히트맵이 사라지기만 했다.</p>
     */
    private void invalidateHeatmapForSettingChange() {
        boolean wasVisible = heatmapImage != null
                || (legacyHeatmapTask != null && legacyHeatmapTask.isRunning());
        heatmapImage = null;
        cachedDpmGrids = null;
        solverConfigDirty = true;
        render();  // 옛 설정의 색을 즉시 지운다 (재생성 중에도 잘못된 색을 보여주지 않도록)
        if (wasVisible) generateHeatmapNow();
    }

    private void invalidateSolverAll() {
        closeSolver();
        solverSourceSignature = Long.MIN_VALUE;
        solverMaterialSignature = Long.MIN_VALUE;
        solverScaleSignature = Double.NaN;
        solverBandSignature = null;
        solverStartWallNs = 0L;
        solverLastTelemetryNs = 0L;
        solverDebugText = "debug: -";
    }

    /** 이전 솔버의 스레드 풀·GPU 커널을 놓는다 (그냥 null 로 버리면 자원이 남는다). */
    private void closeSolver() {
        if (fdtdSolver != null) {
            fdtdSolver.close();
            fdtdSolver = null;
        }
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
            if (showErrorIfMissingMap) showToast("먼저 평면도를 불러와 주세요.");
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
            closeSolver();
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
                showToast("선택한 밴드를 켠 공유기가 없습니다. 밴드를 [전체]로 바꾸거나 공유기의 밴드를 켜 주세요.");
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

    private void showError(String title, String body) {
        app.ui.Notify.error(stage, title, body);
    }

    private void showInfo(String title, String body) {
        app.ui.Notify.info(stage, title, body);
    }

    /** 흐름을 막지 않는 짧은 알림 (성공·안내용). 확인이 필요한 경고·오류는 showInfo/showError. */
    private void showToast(String msg) {
        window.getCanvasView().showToast(msg);
    }

    private List<RssiResult> currentMouseRssiRows() {
        if (!hasMouseProbe) return List.of();
        if (window.getCanvasView().getBaseImageView().getImage() == null) return List.of();
        if (!Double.isFinite(env.getScaleMPerPx()) || env.getScaleMPerPx() <= 0.0) return List.of();

        // 히트맵(DPM)을 만든 그리드에서만 값을 읽는다 — 호버 숫자와 히트맵 색이 항상 같은 모델.
        // 분석 전이면 값 없이(AP 목록만) 표시한다. 예전엔 이때 Legacy 로 몰래 계산해서,
        // 같은 지점 값이 분석 전후로 모델을 바꿔 가며 달라졌다(실측: -107 → DPM 값).
        if (cachedDpmGrids == null) return List.of();
        return sampleRssiFromDpmGrids((int) Math.round(mouseProbeX), (int) Math.round(mouseProbeY));
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
                double rssi = app.engine.LinkBudget.rssiDbm(rc, grid.getPathLossDb(px, py));
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
