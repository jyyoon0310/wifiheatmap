package app.ui;

import app.model.AppState;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.function.Consumer;

public class TopToolbar {

    private final ToolBar bar = new ToolBar();

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

    private Runnable onZoomFit;
    private Runnable onZoom100;
    private Runnable onZoomIn;
    private Runnable onZoomOut;

    private final ToggleGroup toolGroup = new ToggleGroup();
    private final ToggleButton tScale  = new ToggleButton("스케일");
    private final ToggleButton tAP    = new ToggleButton("AP배치");
    private final ToggleButton tWall  = new ToggleButton("벽그리기");
    private final ToggleButton tSolver = new ToggleButton("Solver");

    private final Button solverStartBtn = new Button("▶ 시작");
    private final Button solverStopBtn  = new Button("■ 정지");
    private final Button solverResetBtn = new Button("↺ 리셋");

    private final Label zoomLabel = new Label("100%");

    // pill container for tool toggles — needs theme update
    private HBox toolPill;

    public TopToolbar() {
        // ── 파일 버튼 그룹 ──────────────────────────────────────────────────
        Button open = new Button("열기");
        Styles.styleFlatButton(open);
        open.setTooltip(new Tooltip("평면도 열기"));
        open.setOnAction(e -> { if (onOpenFloorplan != null) onOpenFloorplan.run(); });

        Button openSettings = new Button("불러오기");
        Styles.styleFlatButton(openSettings);
        openSettings.setTooltip(new Tooltip("설정값 열기"));
        openSettings.setOnAction(e -> { if (onOpenSettings != null) onOpenSettings.run(); });

        Button saveSettings = new Button("저장");
        Styles.styleFlatButton(saveSettings);
        saveSettings.setTooltip(new Tooltip("설정값 저장"));
        saveSettings.setOnAction(e -> { if (onSaveSettings != null) onSaveSettings.run(); });

        // ── 도구 선택 pill ──────────────────────────────────────────────────
        tScale.setToggleGroup(toolGroup);
        tAP.setToggleGroup(toolGroup);
        tWall.setToggleGroup(toolGroup);
        tSolver.setToggleGroup(toolGroup);

        Styles.styleToggle(tScale);
        Styles.styleToggle(tAP);
        Styles.styleToggle(tWall);
        Styles.styleToggle(tSolver);
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
        tSolver.setOnAction(e -> {
            if (onToolChanged == null) return;
            onToolChanged.accept(tSolver.isSelected() ? AppState.Tool.SOLVER : AppState.Tool.VIEW);
        });

        toolPill = new HBox(2, tScale, tAP, tWall, tSolver);
        toolPill.setAlignment(Pos.CENTER);
        toolPill.setPadding(new Insets(3, 6, 3, 6));
        Runnable applyPill = () -> toolPill.setStyle(
                "-fx-background-color: " + Styles.bgRow() + ";" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: " + Styles.borderSoft() + ";" +
                "-fx-border-radius: 10;" +
                "-fx-border-width: 0.5;"
        );
        applyPill.run();
        Styles.addThemeListener(applyPill);

        // ── AP 추천 버튼 ──────────────────────────────────────────────────
        Button recommendBtn = new Button("AP 추천");
        Styles.styleFlatButton(recommendBtn);
        recommendBtn.setTooltip(new Tooltip("최적 AP 위치 추천 (Ray-cast + FDTD)"));
        recommendBtn.setOnAction(e -> { if (onRecommendAp != null) onRecommendAp.run(); });

        // ── 히트맵 버튼 ────────────────────────────────────────────────────
        Button gen = new Button("히트맵 생성");
        Styles.styleAccentButton(gen);
        gen.setOnAction(e -> { if (onGenerateHeatmap != null) onGenerateHeatmap.run(); });

        Button clear = new Button("클리어");
        Styles.styleFlatButton(clear);
        clear.setTooltip(new Tooltip("히트맵 클리어"));
        clear.setOnAction(e -> { if (onClearHeatmap != null) onClearHeatmap.run(); });

        // ── 솔버 컨트롤 ─────────────────────────────────────────────────────
        Styles.styleAccentButton(solverStartBtn);
        Styles.styleFlatButton(solverStopBtn);
        Styles.styleFlatButton(solverResetBtn);
        solverStopBtn.setDisable(true);

        solverStartBtn.setOnAction(e -> { if (onStartSolver != null) onStartSolver.run(); });
        solverStopBtn.setOnAction(e -> { if (onStopSolver != null) onStopSolver.run(); });
        solverResetBtn.setOnAction(e -> { if (onResetSolver != null) onResetSolver.run(); });
        setSolverToolActive(false);

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

        // ── bar 구성 ───────────────────────────────────────────────────────
        bar.getItems().addAll(
                open, openSettings, saveSettings,
                new Separator(),
                toolPill,
                new Separator(),
                recommendBtn,
                gen, clear,
                solverStartBtn, solverStopBtn, solverResetBtn,
                spacer,
                zoomBox,
                new Separator(),
                darkBtn
        );

        Runnable applyBar = () -> bar.setStyle(
                "-fx-background-color:" + Styles.bgApp() + ";" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-width:0 0 0.5 0;" +
                "-fx-padding:6 12;");
        applyBar.run();
        Styles.addThemeListener(applyBar);
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

        Runnable applyZoomLabel = () -> zoomLabel.setStyle(
                "-fx-alignment:center;" +
                "-fx-padding:5 8;" +
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:8;" +
                "-fx-background-radius:8;" +
                "-fx-font-size:12px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";"
        );
        applyZoomLabel.run();
        Styles.addThemeListener(applyZoomLabel);

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

    public void setOnZoomFit(Runnable r)  { this.onZoomFit  = r; }
    public void setOnZoom100(Runnable r)  { this.onZoom100  = r; }
    public void setOnZoomIn(Runnable r)   { this.onZoomIn   = r; }
    public void setOnZoomOut(Runnable r)  { this.onZoomOut  = r; }

    public void setSolverRunning(boolean running) {
        solverStartBtn.setDisable(running);
        solverStopBtn.setDisable(!running);
    }

    public void setSolverToolActive(boolean active) {
        solverStartBtn.setManaged(active);
        solverStartBtn.setVisible(active);
        solverStopBtn.setManaged(active);
        solverStopBtn.setVisible(active);
        solverResetBtn.setManaged(active);
        solverResetBtn.setVisible(active);
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
        tSolver.setSelected(false);
    }

    public void setToolSelection(AppState.Tool tool) {
        clearToolSelection();
        if (tool == null || tool == AppState.Tool.VIEW) return;
        switch (tool) {
            case SCALE  -> tScale.setSelected(true);
            case AP     -> tAP.setSelected(true);
            case WALL   -> tWall.setSelected(true);
            case SOLVER -> tSolver.setSelected(true);
            default     -> {}
        }
    }
}
