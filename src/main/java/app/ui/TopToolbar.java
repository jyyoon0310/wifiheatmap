package app.ui;

import app.model.AppState;
import javafx.scene.Node;
import javafx.scene.control.*;
import java.util.function.Consumer;

public class TopToolbar {

    private final ToolBar bar = new ToolBar();

    private Runnable onOpenFloorplan;
    private Runnable onGenerateHeatmap;
    private Runnable onClearHeatmap;
    private Consumer<AppState.Tool> onToolChanged;

    public TopToolbar() {
        Button open = new Button("평면도 열기");
        Styles.styleFlatButton(open);
        open.setOnAction(e -> { if (onOpenFloorplan != null) onOpenFloorplan.run(); });

        ToggleGroup tg = new ToggleGroup();
        ToggleButton tScale = new ToggleButton("스케일");
        ToggleButton tAP = new ToggleButton("AP배치");
        ToggleButton tWall = new ToggleButton("벽그리기");
        tScale.setToggleGroup(tg);
        tAP.setToggleGroup(tg);
        tWall.setToggleGroup(tg);

        Styles.styleToggle(tScale);
        Styles.styleToggle(tAP);
        Styles.styleToggle(tWall);

        // 기본 VIEW(선택 없음)
        tg.selectToggle(null);

        tg.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (onToolChanged == null) return;
            if (nv == null) onToolChanged.accept(AppState.Tool.VIEW);
            else if (nv == tScale) onToolChanged.accept(AppState.Tool.SCALE);
            else if (nv == tAP) onToolChanged.accept(AppState.Tool.AP);
            else onToolChanged.accept(AppState.Tool.WALL);
        });

        Button gen = new Button("히트맵 생성");
        Styles.styleAccentButton(gen);
        gen.setOnAction(e -> { if (onGenerateHeatmap != null) onGenerateHeatmap.run(); });

        Button clear = new Button("히트맵 클리어");
        Styles.styleFlatButton(clear);
        clear.setOnAction(e -> { if (onClearHeatmap != null) onClearHeatmap.run(); });

        bar.getItems().addAll(open, new Separator(), tScale, tAP, tWall, new Separator(), gen, clear);
        bar.setStyle("-fx-background-color: " + Styles.BG_APP + "; -fx-border-color: " + Styles.BORDER_SOFT + "; -fx-border-width: 0 0 1 0; -fx-padding: 8 10;");
    }

    public Node getNode() { return bar; }

    public void setOnOpenFloorplan(Runnable r) { this.onOpenFloorplan = r; }
    public void setOnGenerateHeatmap(Runnable r) { this.onGenerateHeatmap = r; }
    public void setOnClearHeatmap(Runnable r) { this.onClearHeatmap = r; }
    public void setOnToolChanged(Consumer<AppState.Tool> c) { this.onToolChanged = c; }
}