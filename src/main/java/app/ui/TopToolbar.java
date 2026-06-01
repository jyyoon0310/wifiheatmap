package app.ui;

import app.model.AppState;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class TopToolbar {

    private final ToolBar bar = new ToolBar();
    /** 고급 모드에서만 보이는 노드들 (recommendBtn·gen·clear·콤보 등) */
    private final List<Node> advancedOnlyNodes = new ArrayList<>();

    private Runnable onOpenFloorplan;
    private Runnable onOpenSettings;
    private Runnable onSaveSettings;
    private Runnable onGenerateHeatmap;
    private Runnable onClearHeatmap;
    private Runnable onStartSolver;
    private Runnable onStopSolver;
    private Runnable onResetSolver;
    private Consumer<AppState.Tool> onToolChanged;
    private Runnable onRecommendAp;
    private Runnable onShowAdvanced;
    /** "🌊 전파 흐름 보기" 토글 — true=시작, false=정지. MainController가 tool 전환 + solver 제어. */
    private Consumer<Boolean> onWaveToggle;
    private Consumer<AppState.HeatmapModel> onHeatmapModelChanged;
    private Consumer<AppState.HeatmapSolverMode> onHeatmapSolverModeChanged;
    private Consumer<AppState.BandFilter> onBandFilterChanged;

    private final ComboBox<AppState.HeatmapModel> heatmapModelCombo = new ComboBox<>();
    private final ComboBox<AppState.HeatmapSolverMode> solverModeCombo = new ComboBox<>();
    private final ToggleGroup bandGroup = new ToggleGroup();
    private final ToggleButton bandAllBtn  = new ToggleButton("전체");
    private final ToggleButton band24Btn   = new ToggleButton("2.4 GHz");
    private final ToggleButton band5Btn    = new ToggleButton("5 GHz");
    private final ToggleButton band6Btn    = new ToggleButton("6 GHz");
    private boolean suppressHeatmapModelNotify = false;
    private boolean suppressSolverNotify = false;
    private boolean suppressBandNotify = false;

    private Runnable onZoomFit;
    private Runnable onZoom100;
    private Runnable onZoomIn;
    private Runnable onZoomOut;

    private final ToggleGroup toolGroup = new ToggleGroup();
    private final ToggleButton tScale  = new ToggleButton("📐 스케일");
    private final ToggleButton tAP    = new ToggleButton("🛜 공유기 배치");
    private final ToggleButton tWall  = new ToggleButton("🧱 벽 그리기");

    private final Button solverStartBtn = new Button("▶ 시작");
    private final Button solverStopBtn  = new Button("■ 정지");
    private final Button solverResetBtn = new Button("↺ 리셋");

    /** 일반 사용자용 3-액션 버튼 */
    private final Button heatmapActionBtn  = new Button("📡  신호 세기 보기");
    private final ToggleButton waveToggleBtn = new ToggleButton("🌊  전파 흐름 보기");
    private final Button recommendActionBtn  = new Button("📍  공유기 자리 추천");
    private final Button advancedBtn         = new Button("⚙");

    /** 고급(연구) 모드 — true일 때만 Legacy/CPU·GPU 콤보, solver 컨트롤, 클리어 버튼 노출 */
    private boolean advancedMode = false;
    private boolean solverToolActive = false;

    private final Label zoomLabel = new Label("100%");

    // pill container for tool toggles — needs theme update
    private HBox toolPill;

    public TopToolbar() {
        // ── 파일 버튼 그룹 ──────────────────────────────────────────────────
        Button open = new Button("열기");
        Styles.styleFlatButton(open);
        open.setTooltip(new Tooltip("평면도 이미지(PNG/JPG) 불러오기"));
        open.setOnAction(e -> { if (onOpenFloorplan != null) onOpenFloorplan.run(); });

        Button openSettings = new Button("불러오기");
        Styles.styleFlatButton(openSettings);
        openSettings.setTooltip(new Tooltip("저장된 작업 불러오기  (⌘O)"));
        openSettings.setOnAction(e -> { if (onOpenSettings != null) onOpenSettings.run(); });

        Button saveSettings = new Button("저장");
        Styles.styleFlatButton(saveSettings);
        saveSettings.setTooltip(new Tooltip("작업 저장  (⌘S, 다른 이름: ⇧⌘S)"));
        saveSettings.setOnAction(e -> { if (onSaveSettings != null) onSaveSettings.run(); });

        // ── 도구 선택 pill ──────────────────────────────────────────────────
        // (📊 측정 모드/SOLVER는 일반 사용자에게 노출하지 않음 — "🌊 전파 흐름 보기" 토글이 대체)
        tScale.setToggleGroup(toolGroup);
        tAP.setToggleGroup(toolGroup);
        tWall.setToggleGroup(toolGroup);

        Styles.styleToggle(tScale);
        Styles.styleToggle(tAP);
        Styles.styleToggle(tWall);
        clearToolSelection();

        tScale.setOnAction(e -> {
            if (onToolChanged == null) return;
            onToolChanged.accept(tScale.isSelected() ? AppState.Tool.SCALE : AppState.Tool.VIEW);
        });
        tAP.setOnAction(e -> {
            if (onToolChanged == null) return;
            onToolChanged.accept(tAP.isSelected() ? AppState.Tool.AP : AppState.Tool.VIEW);
        });
        tWall.setOnAction(e -> {
            if (onToolChanged == null) return;
            onToolChanged.accept(tWall.isSelected() ? AppState.Tool.WALL : AppState.Tool.VIEW);
        });

        toolPill = new HBox(2, tScale, tAP, tWall);
        toolPill.setAlignment(Pos.CENTER);
        toolPill.setPadding(new Insets(3, 6, 3, 6));
        Themed.bind(toolPill, () ->
                "-fx-background-color: " + Styles.bgRow() + ";" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: " + Styles.borderSoft() + ";" +
                "-fx-border-radius: 10;" +
                "-fx-border-width: 0.5;");

        // ── 히트맵 엔진 모델 콤보 ──────────────────────────────────────────
        heatmapModelCombo.getItems().setAll(AppState.HeatmapModel.values());
        heatmapModelCombo.setConverter(new StringConverter<>() {
            @Override public String toString(AppState.HeatmapModel m) {
                if (m == null) return "Legacy";
                return switch (m) {
                    case LEGACY   -> "Legacy";
                    case DPM      -> "DPM";
                    case FDTD_TEZ -> "FDTD";
                };
            }
            @Override public AppState.HeatmapModel fromString(String s) { return null; }
        });
        heatmapModelCombo.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(AppState.HeatmapModel item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : heatmapModelCombo.getConverter().toString(item));
            }
        });
        heatmapModelCombo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(AppState.HeatmapModel item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : heatmapModelCombo.getConverter().toString(item));
            }
        });
        heatmapModelCombo.getSelectionModel().select(AppState.HeatmapModel.LEGACY);
        heatmapModelCombo.setOnAction(e -> {
            if (suppressHeatmapModelNotify) return;
            if (onHeatmapModelChanged != null) onHeatmapModelChanged.accept(heatmapModelCombo.getValue());
        });
        Themed.bind(heatmapModelCombo, Styles::comboBase);
        Styles.installComboPopupStyle(heatmapModelCombo);

        // ── 솔버 모드 콤보 ─────────────────────────────────────────────────
        solverModeCombo.getItems().setAll(AppState.HeatmapSolverMode.values());
        solverModeCombo.setConverter(new StringConverter<>() {
            @Override public String toString(AppState.HeatmapSolverMode m) {
                return m == AppState.HeatmapSolverMode.GPU ? "Solver: GPU" : "Solver: CPU";
            }
            @Override public AppState.HeatmapSolverMode fromString(String s) { return null; }
        });
        solverModeCombo.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(AppState.HeatmapSolverMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" :
                        (item == AppState.HeatmapSolverMode.GPU ? "GPU (실험)" : "CPU"));
            }
        });
        solverModeCombo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(AppState.HeatmapSolverMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : solverModeCombo.getConverter().toString(item));
            }
        });
        solverModeCombo.getSelectionModel().select(AppState.HeatmapSolverMode.CPU);
        solverModeCombo.setOnAction(e -> {
            if (suppressSolverNotify) return;
            if (onHeatmapSolverModeChanged != null) onHeatmapSolverModeChanged.accept(solverModeCombo.getValue());
        });
        Themed.bind(solverModeCombo, Styles::comboBase);
        Styles.installComboPopupStyle(solverModeCombo);

        // ── 밴드 필터 (히트맵 표시 밴드 선택) ──────────────────────────
        bandAllBtn.setToggleGroup(bandGroup);
        band24Btn.setToggleGroup(bandGroup);
        band5Btn.setToggleGroup(bandGroup);
        band6Btn.setToggleGroup(bandGroup);
        bandAllBtn.setSelected(true);
        Styles.styleToggle(bandAllBtn);
        Styles.styleToggle(band24Btn);
        Styles.styleToggle(band5Btn);
        Styles.styleToggle(band6Btn);

        bandAllBtn.setTooltip(new Tooltip("모든 활성 밴드의 RSSI 중 최댓값 (기존 동작)"));
        band24Btn .setTooltip(new Tooltip("2.4 GHz 밴드만 사용해 히트맵 계산"));
        band5Btn  .setTooltip(new Tooltip("5 GHz 밴드만 사용해 히트맵 계산"));
        band6Btn  .setTooltip(new Tooltip("6 GHz 밴드만 사용해 히트맵 계산"));

        // 클릭 시 동일 버튼 재토글로 해제되는 것 방지: 항상 1개는 선택 유지
        bandGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null && oldT != null) oldT.setSelected(true);
        });
        java.util.function.BiConsumer<ToggleButton, AppState.BandFilter> bind = (btn, f) ->
            btn.setOnAction(e -> {
                if (suppressBandNotify) return;
                if (!btn.isSelected()) return;
                if (onBandFilterChanged != null) onBandFilterChanged.accept(f);
            });
        bind.accept(bandAllBtn, AppState.BandFilter.ALL_MAX);
        bind.accept(band24Btn,  AppState.BandFilter.GHZ_24);
        bind.accept(band5Btn,   AppState.BandFilter.GHZ_5);
        bind.accept(band6Btn,   AppState.BandFilter.GHZ_6);

        HBox bandPill = new HBox(2, bandAllBtn, band24Btn, band5Btn, band6Btn);
        bandPill.setAlignment(Pos.CENTER);
        bandPill.setPadding(new Insets(3, 6, 3, 6));
        Themed.bind(bandPill, () ->
                "-fx-background-color: " + Styles.bgRow() + ";" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: " + Styles.borderSoft() + ";" +
                "-fx-border-radius: 10;" +
                "-fx-border-width: 0.5;");

        Label bandLabel = new Label("밴드:");
        Runnable applyBandLabel = () -> bandLabel.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;"
        );
        applyBandLabel.run();
        Styles.addThemeListener(applyBandLabel);

        // ── [고급 모드 전용] AP 추천 / 히트맵 생성 / 클리어 (일반 모드에선 숨김) ──
        Button recommendBtn = new Button("AP 추천");
        Styles.styleFlatButton(recommendBtn);
        recommendBtn.setTooltip(new Tooltip("최적 AP 위치 추천 (Ray-cast + FDTD)"));
        recommendBtn.setOnAction(e -> { if (onRecommendAp != null) onRecommendAp.run(); });

        Button gen = new Button("히트맵 생성");
        Styles.styleAccentButton(gen);
        gen.setOnAction(e -> { if (onGenerateHeatmap != null) onGenerateHeatmap.run(); });

        Button clear = new Button("클리어");
        Styles.styleFlatButton(clear);
        clear.setTooltip(new Tooltip("히트맵 클리어"));
        clear.setOnAction(e -> { if (onClearHeatmap != null) onClearHeatmap.run(); });

        // ── 솔버 컨트롤 (고급 + Solver tool 활성 시에만 노출) ──────────────
        Styles.styleAccentButton(solverStartBtn);
        Styles.styleFlatButton(solverStopBtn);
        Styles.styleFlatButton(solverResetBtn);
        solverStopBtn.setDisable(true);
        solverStartBtn.setOnAction(e -> { if (onStartSolver != null) onStartSolver.run(); });
        solverStopBtn.setOnAction(e -> { if (onStopSolver != null) onStopSolver.run(); });
        solverResetBtn.setOnAction(e -> { if (onResetSolver != null) onResetSolver.run(); });

        // ── [일반 모드] 3-액션 버튼 ────────────────────────────────────────
        Styles.styleAccentButton(heatmapActionBtn);
        heatmapActionBtn.setTooltip(new Tooltip("도면 전체의 신호 세기 지도를 만들어 보여줍니다"));
        heatmapActionBtn.setOnAction(e -> { if (onGenerateHeatmap != null) onGenerateHeatmap.run(); });

        Styles.styleToggle(waveToggleBtn);
        waveToggleBtn.setTooltip(new Tooltip("AP에서 퍼지는 전파의 움직임을 실시간으로 보여줍니다"));
        waveToggleBtn.setOnAction(e -> {
            if (onWaveToggle != null) onWaveToggle.accept(waveToggleBtn.isSelected());
        });

        Styles.styleFlatButton(recommendActionBtn);
        recommendActionBtn.setTooltip(new Tooltip("선택한 영역을 가장 잘 커버하는 공유기 위치를 찾아줍니다"));
        recommendActionBtn.setOnAction(e -> { if (onRecommendAp != null) onRecommendAp.run(); });

        // ── 고급 모드 진입 버튼 (⚙) ──────────────────────────────────────
        Styles.styleFlatButton(advancedBtn);
        advancedBtn.setTooltip(new Tooltip("연구·실험용 고급 설정"));
        advancedBtn.setOnAction(e -> { if (onShowAdvanced != null) onShowAdvanced.run(); });

        // ── 오른쪽 spacer ──────────────────────────────────────────────────
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ── 줌 컨트롤 ─────────────────────────────────────────────────────
        HBox zoomBox = buildZoomBox();

        // ── 다크 모드 토글 ─────────────────────────────────────────────────
        ToggleButton darkBtn = new ToggleButton("◑");
        darkBtn.setTooltip(new Tooltip("다크 / 라이트 모드"));
        darkBtn.setSelected(Styles.isDark());
        darkBtn.setOnAction(e -> Styles.setDark(darkBtn.isSelected()));
        Styles.styleToggle(darkBtn);
        Styles.addThemeListener(() -> darkBtn.setSelected(Styles.isDark()));

        // ── 액션 그룹 pill (3-액션 버튼 묶음) ───────────────────────────────
        HBox actionGroup = new HBox(6, heatmapActionBtn, waveToggleBtn, recommendActionBtn);
        actionGroup.setAlignment(Pos.CENTER);
        actionGroup.setPadding(new Insets(2, 4, 2, 4));

        // ── bar 구성 ───────────────────────────────────────────────────────
        bar.getItems().addAll(
                open, openSettings, saveSettings,
                new Separator(),
                toolPill,
                new Separator(),
                actionGroup,
                // ── 아래는 고급 모드에서만 노출 ──
                recommendBtn,
                heatmapModelCombo, gen, clear, solverModeCombo,
                solverStartBtn, solverStopBtn, solverResetBtn,
                new Separator(),
                bandLabel, bandPill,
                spacer,
                advancedBtn,
                zoomBox,
                new Separator(),
                darkBtn
        );

        // 고급 모드 전용 노드 등록
        advancedOnlyNodes.add(recommendBtn);
        advancedOnlyNodes.add(heatmapModelCombo);
        advancedOnlyNodes.add(gen);
        advancedOnlyNodes.add(clear);
        advancedOnlyNodes.add(solverModeCombo);

        // 초기 가시성: 일반 모드 + 솔버 비활성
        applyAdvancedVisibility();
        applySolverButtonVisibility();

        Themed.bind(bar, () ->
                "-fx-background-color:" + Styles.bgApp() + ";" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-width:0 0 0.5 0;" +
                "-fx-padding:6 12;");
    }

    private HBox buildZoomBox() {
        // 맞춤 버튼
        Button fit = new Button("맞춤");
        Styles.styleFlatButton(fit);
        fit.setTooltip(new Tooltip("화면에 맞춤"));
        fit.setOnAction(e -> { if (onZoomFit != null) onZoomFit.run(); });

        // − 버튼
        Button minus = new Button("−");
        Styles.styleFlatButton(minus);
        minus.setPrefWidth(30);
        minus.setOnAction(e -> { if (onZoomOut != null) onZoomOut.run(); });

        // 줌 레이블 (클릭 시 100% 복귀)
        zoomLabel.setMinWidth(52);
        zoomLabel.setAlignment(Pos.CENTER);
        zoomLabel.setCursor(Cursor.HAND);
        zoomLabel.setTooltip(new Tooltip("클릭 시 100%로 초기화"));
        zoomLabel.setOnMouseClicked(e -> { if (onZoom100 != null) onZoom100.run(); });

        Themed.bind(zoomLabel, () ->
                "-fx-alignment:center;" +
                "-fx-padding:5 8;" +
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:8;" +
                "-fx-background-radius:8;" +
                "-fx-font-size:12px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");

        // + 버튼
        Button plus = new Button("+");
        Styles.styleFlatButton(plus);
        plus.setPrefWidth(30);
        plus.setOnAction(e -> { if (onZoomIn != null) onZoomIn.run(); });

        HBox box = new HBox(4, fit, minus, zoomLabel, plus);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(1, 0, 1, 0));
        return box;
    }

    // ── getters / setters ───────────────────────────────────────────────────
    public Node getNode() { return bar; }

    public void setOnOpenFloorplan(Runnable r)          { this.onOpenFloorplan = r; }
    public void setOnOpenSettings(Runnable r)           { this.onOpenSettings = r; }
    public void setOnSaveSettings(Runnable r)           { this.onSaveSettings = r; }
    public void setOnGenerateHeatmap(Runnable r)        { this.onGenerateHeatmap = r; }
    public void setOnClearHeatmap(Runnable r)           { this.onClearHeatmap = r; }
    public void setOnStartSolver(Runnable r)            { this.onStartSolver = r; }
    public void setOnStopSolver(Runnable r)             { this.onStopSolver = r; }
    public void setOnResetSolver(Runnable r)            { this.onResetSolver = r; }
    public void setOnToolChanged(Consumer<AppState.Tool> c) { this.onToolChanged = c; }
    public void setOnRecommendAp(Runnable r)             { this.onRecommendAp = r; }
    public void setOnHeatmapModelChanged(Consumer<AppState.HeatmapModel> c) { this.onHeatmapModelChanged = c; }
    public void setOnHeatmapSolverModeChanged(Consumer<AppState.HeatmapSolverMode> c) { this.onHeatmapSolverModeChanged = c; }
    public void setOnBandFilterChanged(Consumer<AppState.BandFilter> c) { this.onBandFilterChanged = c; }

    public void setBandFilter(AppState.BandFilter f) {
        suppressBandNotify = true;
        AppState.BandFilter target = (f == null) ? AppState.BandFilter.ALL_MAX : f;
        switch (target) {
            case ALL_MAX -> bandAllBtn.setSelected(true);
            case GHZ_24  -> band24Btn .setSelected(true);
            case GHZ_5   -> band5Btn  .setSelected(true);
            case GHZ_6   -> band6Btn  .setSelected(true);
        }
        suppressBandNotify = false;
    }

    public void setHeatmapModel(AppState.HeatmapModel mode) {
        suppressHeatmapModelNotify = true;
        heatmapModelCombo.getSelectionModel().select(mode == null ? AppState.HeatmapModel.LEGACY : mode);
        suppressHeatmapModelNotify = false;
    }

    public void setHeatmapSolverMode(AppState.HeatmapSolverMode mode) {
        suppressSolverNotify = true;
        solverModeCombo.getSelectionModel().select(mode == null ? AppState.HeatmapSolverMode.CPU : mode);
        suppressSolverNotify = false;
    }

    public void setOnZoomFit(Runnable r)  { this.onZoomFit  = r; }
    public void setOnZoom100(Runnable r)  { this.onZoom100  = r; }
    public void setOnZoomIn(Runnable r)   { this.onZoomIn   = r; }
    public void setOnZoomOut(Runnable r)  { this.onZoomOut  = r; }

    public void setOnShowAdvanced(Runnable r) { this.onShowAdvanced = r; }

    public void setSolverRunning(boolean running) {
        solverStartBtn.setDisable(running);
        solverStopBtn.setDisable(!running);
        // 일반 모드의 "🌊 전파 흐름 보기" 토글 상태 동기화
        if (waveToggleBtn.isSelected() != running) waveToggleBtn.setSelected(running);
    }

    public void setSolverToolActive(boolean active) {
        this.solverToolActive = active;
        applySolverButtonVisibility();
    }

    /** 고급 모드 ON/OFF — 외부에서 호출. visible/managed 일괄 토글. */
    public void setAdvancedMode(boolean advanced) {
        this.advancedMode = advanced;
        applyAdvancedVisibility();
        applySolverButtonVisibility();
    }

    /** AppState.advancedModeProperty()와 양방향 동기화 */
    public void bindAdvancedMode(BooleanProperty advancedModeProperty) {
        setAdvancedMode(advancedModeProperty.get());
        advancedModeProperty.addListener((obs, oldV, newV) -> setAdvancedMode(newV));
    }

    private void applyAdvancedVisibility() {
        for (Node n : advancedOnlyNodes) {
            n.setVisible(advancedMode);
            n.setManaged(advancedMode);
        }
    }

    private void applySolverButtonVisibility() {
        boolean show = advancedMode && solverToolActive;
        solverStartBtn.setManaged(show);
        solverStartBtn.setVisible(show);
        solverStopBtn.setManaged(show);
        solverStopBtn.setVisible(show);
        solverResetBtn.setManaged(show);
        solverResetBtn.setVisible(show);
    }

    /** MainController에서 zoomScaleProperty를 넘겨주면 라벨이 자동 갱신됨 */
    public void bindZoomLabel(DoubleProperty zoomScaleProperty) {
        zoomLabel.textProperty().bind(
                Bindings.createStringBinding(() -> {
                    int ip = (int) Math.round(zoomScaleProperty.get() * 100.0);
                    return ip + "%";
                }, zoomScaleProperty)
        );
    }

    public void clearToolSelection() {
        toolGroup.selectToggle(null);
        tScale.setSelected(false);
        tAP.setSelected(false);
        tWall.setSelected(false);
    }

    public void setToolSelection(AppState.Tool tool) {
        clearToolSelection();
        if (tool == null || tool == AppState.Tool.VIEW) return;
        switch (tool) {
            case SCALE  -> tScale.setSelected(true);
            case AP     -> tAP.setSelected(true);
            case WALL   -> tWall.setSelected(true);
            // SOLVER는 일반 사용자에게 노출되지 않음 — toolbar 토글 없음
            default     -> {}
        }
    }

    public void setOnWaveToggle(Consumer<Boolean> c) { this.onWaveToggle = c; }

    /** "🌊 전파 흐름 보기" 토글 상태 외부 동기화 (예: 다른 경로로 솔버가 정지될 때) */
    public void setWaveToggleSelected(boolean selected) {
        if (waveToggleBtn.isSelected() != selected) waveToggleBtn.setSelected(selected);
    }
}
