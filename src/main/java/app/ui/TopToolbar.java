package app.ui;

import app.model.AppState;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;

import java.util.function.Consumer;

public class TopToolbar {

    private final ToolBar bar = new ToolBar();

    private Runnable onOpenFloorplan;
    private Runnable onGenerateHeatmap;
    private Runnable onClearHeatmap;
    private Consumer<AppState.Tool> onToolChanged;

    private final ToggleGroup toolGroup = new ToggleGroup();
    private final ToggleButton tScale = new ToggleButton("스케일");
    private final ToggleButton tAP = new ToggleButton("AP배치");
    private final ToggleButton tWall = new ToggleButton("벽그리기");

    public TopToolbar() {
        Button open = new Button("평면도 열기");
        Styles.styleFlatButton(open);
        open.setOnAction(e -> { if (onOpenFloorplan != null) onOpenFloorplan.run(); });

        // Toggle group wiring
        tScale.setToggleGroup(toolGroup);
        tAP.setToggleGroup(toolGroup);
        tWall.setToggleGroup(toolGroup);

        Styles.styleToggle(tScale);
        Styles.styleToggle(tAP);
        Styles.styleToggle(tWall);

        // ✅ 기본: 아무것도 선택 안 함(VIEW)
        clearToolSelection();

        // ✅ 재클릭(이미 선택된 버튼 다시 클릭) = 해제(VIEW)
        installDeselectOnPress(tScale);
        installDeselectOnPress(tAP);
        installDeselectOnPress(tWall);

        // ✅ 일반 선택 변경은 여기서 처리
        toolGroup.selectedToggleProperty().addListener((obs, ov, nv) -> {
            if (onToolChanged == null) return;

            if (nv == null) {
                onToolChanged.accept(AppState.Tool.VIEW);
                return;
            }
            if (nv == tScale) onToolChanged.accept(AppState.Tool.SCALE);
            else if (nv == tAP) onToolChanged.accept(AppState.Tool.AP);
            else if (nv == tWall) onToolChanged.accept(AppState.Tool.WALL);
        });

        Button gen = new Button("히트맵 생성");
        Styles.styleAccentButton(gen);
        gen.setOnAction(e -> { if (onGenerateHeatmap != null) onGenerateHeatmap.run(); });

        Button clear = new Button("히트맵 클리어");
        Styles.styleFlatButton(clear);
        clear.setOnAction(e -> { if (onClearHeatmap != null) onClearHeatmap.run(); });

        bar.getItems().addAll(
                open,
                new Separator(),
                tScale, tAP, tWall,
                new Separator(),
                gen, clear
        );

        bar.setStyle("-fx-background-color: " + Styles.BG_APP + ";" +
                "-fx-border-color: " + Styles.BORDER_SOFT + ";" +
                "-fx-border-width: 0 0 1 0;" +
                "-fx-padding: 8 10;");
    }

    /**
     * 핵심: 이미 선택된 토글을 다시 누르는 순간(MOUSE_PRESSED)에 선택을 풀어버리면
     * ToggleGroup 기본 동작(선택 유지)을 100% 안정적으로 우회 가능.
     */
    private void installDeselectOnPress(ToggleButton btn) {
        btn.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (toolGroup.getSelectedToggle() == btn) {
                // 이미 선택된 걸 다시 누름 -> 즉시 해제
                clearToolSelection();
                if (onToolChanged != null) onToolChanged.accept(AppState.Tool.VIEW);
                e.consume();
            }
        });
    }

    public Node getNode() { return bar; }

    public void setOnOpenFloorplan(Runnable r) { this.onOpenFloorplan = r; }
    public void setOnGenerateHeatmap(Runnable r) { this.onGenerateHeatmap = r; }
    public void setOnClearHeatmap(Runnable r) { this.onClearHeatmap = r; }
    public void setOnToolChanged(Consumer<AppState.Tool> c) { this.onToolChanged = c; }

    /** Controller가 강제로 VIEW로 돌릴 때: 토글 선택만 해제 */
    public void clearToolSelection() {
        toolGroup.selectToggle(null);
        tScale.setSelected(false);
        tAP.setSelected(false);
        tWall.setSelected(false);
    }
}