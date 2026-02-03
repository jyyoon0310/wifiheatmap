package app.ui;

import app.model.AppState;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.function.Consumer;

public class TopToolbar {

    private final ToolBar bar = new ToolBar();

    private Runnable onOpenFloorplan;
    private Runnable onGenerateHeatmap;
    private Runnable onClearHeatmap;
    private Consumer<AppState.Tool> onToolChanged;

    // ✅ 줌 액션 콜백
    private Runnable onZoomFit;
    private Runnable onZoom100;
    private Runnable onZoomIn;
    private Runnable onZoomOut;

    private final ToggleGroup toolGroup = new ToggleGroup();
    private final ToggleButton tScale = new ToggleButton("스케일");
    private final ToggleButton tAP = new ToggleButton("AP배치");
    private final ToggleButton tWall = new ToggleButton("벽그리기");

    // ✅ 줌 UI
    private final Label zoomLabel = new Label("100%");

    public TopToolbar() {
        Button open = new Button("평면도 열기");
        Styles.styleFlatButton(open);
        open.setOnAction(e -> { if (onOpenFloorplan != null) onOpenFloorplan.run(); });

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

        Button gen = new Button("히트맵 생성");
        Styles.styleAccentButton(gen);
        gen.setOnAction(e -> { if (onGenerateHeatmap != null) onGenerateHeatmap.run(); });

        Button clear = new Button("히트맵 클리어");
        Styles.styleFlatButton(clear);
        clear.setOnAction(e -> { if (onClearHeatmap != null) onClearHeatmap.run(); });

        // ===== ✅ Zoom box (오른쪽 정렬) =====
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox zoomBox = buildZoomBox();

        bar.getItems().addAll(
                open,
                new Separator(),
                tScale, tAP, tWall,
                new Separator(),
                gen, clear,
                spacer,
                zoomBox
        );

        bar.setStyle("-fx-background-color: " + Styles.BG_APP + ";" +
                "-fx-border-color: " + Styles.BORDER_SOFT + ";" +
                "-fx-border-width: 0 0 1 0;" +
                "-fx-padding: 8 10;");
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
        zoomLabel.setStyle(
                "-fx-alignment: center;" +
                        "-fx-padding: 6 10;" +
                        "-fx-background-color: " + Styles.BG_PANEL + ";" +
                        "-fx-border-color: " + Styles.BORDER_SOFT + ";" +
                        "-fx-border-radius: 10;" +
                        "-fx-background-radius: 10;"
        );

        HBox box = new HBox(8, fit, b100, new Separator(), minus, zoomLabel, plus);
        box.setPadding(new Insets(2, 0, 2, 0));
        box.setStyle("-fx-alignment: center-right;");
        return box;
    }

    public Node getNode() { return bar; }

    public void setOnOpenFloorplan(Runnable r) { this.onOpenFloorplan = r; }
    public void setOnGenerateHeatmap(Runnable r) { this.onGenerateHeatmap = r; }
    public void setOnClearHeatmap(Runnable r) { this.onClearHeatmap = r; }
    public void setOnToolChanged(Consumer<AppState.Tool> c) { this.onToolChanged = c; }

    // ✅ 줌 콜백 setter
    public void setOnZoomFit(Runnable r) { this.onZoomFit = r; }
    public void setOnZoom100(Runnable r) { this.onZoom100 = r; }
    public void setOnZoomIn(Runnable r) { this.onZoomIn = r; }
    public void setOnZoomOut(Runnable r) { this.onZoomOut = r; }

    /** MainController에서 zoomScaleProperty 넘겨주면 라벨이 자동 갱신됨 */
    public void bindZoomLabel(DoubleProperty zoomScaleProperty) {
        zoomLabel.textProperty().bind(
                Bindings.createStringBinding(() -> {
                    double pct = zoomScaleProperty.get() * 100.0;
                    int ip = (int) Math.round(pct);
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
}