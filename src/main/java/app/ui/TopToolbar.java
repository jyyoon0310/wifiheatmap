package app.ui;

import app.model.AppState;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;

import java.util.function.Consumer;

public class TopToolbar {

    private final ToolBar bar = new ToolBar();

    private Runnable onOpenFloorplan;
    private Runnable onGenerateHeatmap;
    private Runnable onClearHeatmap;
    private Runnable onStartSolver;
    private Runnable onStopSolver;
    private Runnable onResetSolver;
    private Consumer<AppState.Tool> onToolChanged;
    private Consumer<AppState.HeatmapSolverMode> onHeatmapSolverModeChanged;
    private Consumer<AppState.HeatmapModel> onHeatmapModelChanged;

    private Runnable onZoomFit;
    private Runnable onZoom100;
    private Runnable onZoomIn;
    private Runnable onZoomOut;

    private final ToggleGroup toolGroup = new ToggleGroup();
    private final ToggleButton tScale = new ToggleButton("스케일");
    private final ToggleButton tAP = new ToggleButton("AP배치");
    private final ToggleButton tWall = new ToggleButton("벽그리기");
    private final ToggleButton tSolver = new ToggleButton("Solver");
    private final ComboBox<AppState.HeatmapModel> heatmapModelCombo = new ComboBox<>();
    private final ComboBox<AppState.HeatmapSolverMode> solverModeCombo = new ComboBox<>();
    private final Button solverStartBtn = new Button("Solver 시작");
    private final Button solverStopBtn = new Button("Solver 정지");
    private final Button solverResetBtn = new Button("Solver 리셋");
    private boolean suppressHeatmapModelNotify = false;
    private boolean suppressSolverNotify = false;

    private final Label zoomLabel = new Label("100%");

    public TopToolbar() {
        Button open = new Button("평면도 열기");
        Styles.styleFlatButton(open);
        open.setOnAction(e -> { if (onOpenFloorplan != null) onOpenFloorplan.run(); });

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

        Button gen = new Button("히트맵 생성");
        Styles.styleAccentButton(gen);
        gen.setOnAction(e -> { if (onGenerateHeatmap != null) onGenerateHeatmap.run(); });

        Button clear = new Button("히트맵 클리어");
        Styles.styleFlatButton(clear);
        clear.setOnAction(e -> { if (onClearHeatmap != null) onClearHeatmap.run(); });

        // 히트맵 모델 콤보
        heatmapModelCombo.getItems().setAll(AppState.HeatmapModel.values());
        heatmapModelCombo.setConverter(new StringConverter<>() {
            @Override public String toString(AppState.HeatmapModel mode) {
                if (mode == null) return "";
                return mode == AppState.HeatmapModel.FDTD_TEZ ? "모델: FDTD 정밀" : "모델: Legacy(경험적)";
            }
            @Override public AppState.HeatmapModel fromString(String string) { return null; }
        });
        heatmapModelCombo.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(AppState.HeatmapModel item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item == AppState.HeatmapModel.FDTD_TEZ ? "FDTD 정밀(물리 시뮬레이션)" : "Legacy(거리/벽감쇠)");
            }
        });
        heatmapModelCombo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(AppState.HeatmapModel item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : heatmapModelCombo.getConverter().toString(item));
            }
        });
        heatmapModelCombo.getSelectionModel().select(AppState.HeatmapModel.LEGACY);
        heatmapModelCombo.setDisable(false);
        heatmapModelCombo.setOnAction(e -> {
            if (suppressHeatmapModelNotify) return;
            if (onHeatmapModelChanged != null) onHeatmapModelChanged.accept(heatmapModelCombo.getValue());
        });
        Runnable applyModelCombo = () -> heatmapModelCombo.setStyle(Styles.comboBase());
        applyModelCombo.run();
        Styles.addThemeListener(applyModelCombo);
        Styles.installComboPopupStyle(heatmapModelCombo);

        // 솔버 모드 콤보
        solverModeCombo.getItems().setAll(AppState.HeatmapSolverMode.values());
        solverModeCombo.setConverter(new StringConverter<>() {
            @Override public String toString(AppState.HeatmapSolverMode mode) {
                if (mode == null) return "";
                return mode == AppState.HeatmapSolverMode.GPU ? "Solver: GPU(실험)" : "Solver: CPU";
            }
            @Override public AppState.HeatmapSolverMode fromString(String string) { return null; }
        });
        solverModeCombo.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(AppState.HeatmapSolverMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : (item == AppState.HeatmapSolverMode.GPU ? "GPU (실험)" : "CPU"));
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
        Runnable applySolverCombo = () -> solverModeCombo.setStyle(Styles.comboBase());
        applySolverCombo.run();
        Styles.addThemeListener(applySolverCombo);
        Styles.installComboPopupStyle(solverModeCombo);

        Styles.styleAccentButton(solverStartBtn);
        Styles.styleFlatButton(solverStopBtn);
        Styles.styleFlatButton(solverResetBtn);
        solverStopBtn.setDisable(true);

        solverStartBtn.setOnAction(e -> { if (onStartSolver != null) onStartSolver.run(); });
        solverStopBtn.setOnAction(e -> { if (onStopSolver != null) onStopSolver.run(); });
        solverResetBtn.setOnAction(e -> { if (onResetSolver != null) onResetSolver.run(); });
        setSolverToolActive(false);

        // ── 다크 모드 토글 ─────────────────────────────────────────────────────
        ToggleButton darkBtn = new ToggleButton("◑");
        darkBtn.setTooltip(new Tooltip("다크/라이트 모드 전환"));
        darkBtn.setSelected(Styles.isDark());
        Styles.styleToggle(darkBtn);
        darkBtn.setOnAction(e -> Styles.setDark(darkBtn.isSelected()));

        // ── 줌 박스 ──────────────────────────────────────────────────────────
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox zoomBox = buildZoomBox();

        bar.getItems().addAll(
                open,
                new Separator(),
                tScale, tAP, tWall, tSolver,
                new Separator(),
                heatmapModelCombo, gen, clear, solverModeCombo,
                solverStartBtn, solverStopBtn, solverResetBtn,
                spacer,
                darkBtn,
                new Separator(),
                zoomBox
        );

        Runnable applyBar = () -> bar.setStyle(
                "-fx-background-color:" + Styles.bgApp() + ";" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-width:0 0 0.5 0;" +
                "-fx-padding:8 10;");
        applyBar.run();
        Styles.addThemeListener(applyBar);
    }

    private HBox buildZoomBox() {
        Button fit = new Button("맞춤");
        Styles.styleFlatButton(fit);
        fit.setOnAction(e -> { if (onZoomFit != null) onZoomFit.run(); });

        Button b100 = new Button("100%");
        Styles.styleFlatButton(b100);
        b100.setOnAction(e -> { if (onZoom100 != null) onZoom100.run(); });

        Button minus = new Button("−");
        Styles.styleFlatButton(minus);
        minus.setOnAction(e -> { if (onZoomOut != null) onZoomOut.run(); });

        Button plus = new Button("+");
        Styles.styleFlatButton(plus);
        plus.setOnAction(e -> { if (onZoomIn != null) onZoomIn.run(); });

        zoomLabel.setMinWidth(64);
        Runnable applyZoomLabel = () -> zoomLabel.setStyle(
                "-fx-alignment:center;" +
                "-fx-padding:6 10;" +
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:12px;" +
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:10;" +
                "-fx-background-radius:10;");
        applyZoomLabel.run();
        Styles.addThemeListener(applyZoomLabel);

        HBox box = new HBox(8, fit, b100, new Separator(), minus, zoomLabel, plus);
        box.setPadding(new Insets(2, 0, 2, 0));
        box.setStyle("-fx-alignment:center-right;");
        return box;
    }

    public Node getNode() { return bar; }

    public void setOnOpenFloorplan(Runnable r) { this.onOpenFloorplan = r; }
    public void setOnGenerateHeatmap(Runnable r) { this.onGenerateHeatmap = r; }
    public void setOnClearHeatmap(Runnable r) { this.onClearHeatmap = r; }
    public void setOnStartSolver(Runnable r) { this.onStartSolver = r; }
    public void setOnStopSolver(Runnable r) { this.onStopSolver = r; }
    public void setOnResetSolver(Runnable r) { this.onResetSolver = r; }
    public void setOnToolChanged(Consumer<AppState.Tool> c) { this.onToolChanged = c; }
    public void setOnHeatmapModelChanged(Consumer<AppState.HeatmapModel> c) { this.onHeatmapModelChanged = c; }
    public void setOnHeatmapSolverModeChanged(Consumer<AppState.HeatmapSolverMode> c) { this.onHeatmapSolverModeChanged = c; }

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

    public void setSolverRunning(boolean running) {
        solverStartBtn.setDisable(running);
        solverStopBtn.setDisable(!running);
    }

    public void setSolverToolActive(boolean active) {
        solverModeCombo.setManaged(active);
        solverModeCombo.setVisible(active);
        solverStartBtn.setManaged(active);
        solverStartBtn.setVisible(active);
        solverStopBtn.setManaged(active);
        solverStopBtn.setVisible(active);
        solverResetBtn.setManaged(active);
        solverResetBtn.setVisible(active);
    }

    public void setOnZoomFit(Runnable r) { this.onZoomFit = r; }
    public void setOnZoom100(Runnable r) { this.onZoom100 = r; }
    public void setOnZoomIn(Runnable r) { this.onZoomIn = r; }
    public void setOnZoomOut(Runnable r) { this.onZoomOut = r; }

    /** MainController에서 zoomScaleProperty 넘겨주면 라벨이 자동 갱신됨 */
    public void bindZoomLabel(DoubleProperty zoomScaleProperty) {
        zoomLabel.textProperty().bind(
                Bindings.createStringBinding(() -> {
                    double pct = zoomScaleProperty.get() * 100.0;
                    return (int) Math.round(pct) + "%";
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
            case SCALE -> tScale.setSelected(true);
            case AP    -> tAP.setSelected(true);
            case WALL  -> tWall.setSelected(true);
            case SOLVER -> tSolver.setSelected(true);
            default -> {}
        }
    }
}
