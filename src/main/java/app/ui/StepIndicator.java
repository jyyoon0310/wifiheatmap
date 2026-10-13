package app.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.IntConsumer;

/**
 * 작업 진행 단계 표시 — ① 도면 → ② 축척 → ③ 벽 → ④ 공유기 → ⑤ 신호 지도.
 *
 * <p>끝난 단계는 체크, 지금 할 단계는 강조, 남은 단계는 흐리게. 단계를 누르면 그 단계의 도구로
 * 이동한다(콜백). 예전엔 이 흐름을 모달 "시작 가이드"로만 알려 줘서, 닫고 나면 지금 어디쯤인지
 * 화면에서 알 수 없었다. (가정용 공유기 앱의 설치 단계 표시와 같은 역할)</p>
 */
public final class StepIndicator {

    public static final String[] STEP_NAMES = { "도면", "축척", "벽", "공유기", "신호 지도" };
    private static final String[] STEP_TIPS = {
            "평면도 이미지를 불러옵니다",
            "길이를 아는 두 점으로 도면 축척을 맞춥니다",
            "집의 벽을 그립니다",
            "공유기를 놓거나 자리를 추천받습니다",
            "신호 세기 지도를 만듭니다"
    };

    private final VBox root = new VBox();
    private final StackPane[] dots = new StackPane[STEP_NAMES.length];
    private final Label[] dotLabels = new Label[STEP_NAMES.length];
    private final Label[] names = new Label[STEP_NAMES.length];
    private final Region[] links = new Region[STEP_NAMES.length - 1];
    private boolean[] done = new boolean[STEP_NAMES.length];
    private IntConsumer onStepClicked = i -> {};

    public StepIndicator() {
        HBox row = new HBox();
        row.setAlignment(Pos.TOP_CENTER);
        for (int i = 0; i < STEP_NAMES.length; i++) {
            final int idx = i;
            dotLabels[i] = new Label(String.valueOf(i + 1));
            dots[i] = new StackPane(dotLabels[i]);
            dots[i].setMinSize(24, 24);
            dots[i].setMaxSize(24, 24);
            names[i] = new Label(STEP_NAMES[i]);
            names[i].setWrapText(false);
            VBox step = new VBox(4, dots[i], names[i]);
            step.setAlignment(Pos.TOP_CENTER);
            step.setMinWidth(48);
            step.setCursor(Cursor.HAND);
            step.setOnMouseClicked(e -> onStepClicked.accept(idx));
            Tooltip.install(step, new Tooltip(STEP_TIPS[i]));
            row.getChildren().add(step);
            if (i < links.length) {
                links[i] = new Region();
                links[i].setPrefHeight(2);
                links[i].setMinHeight(2);
                links[i].setMaxHeight(2);
                HBox.setHgrow(links[i], Priority.ALWAYS);
                HBox.setMargin(links[i], new Insets(11, -10, 0, -10));  // 점의 세로 중앙
                row.getChildren().add(links[i]);
            }
        }
        root.getChildren().add(row);
        root.setPadding(new Insets(14, 12, 12, 12));
        Styles.addThemeListener(this::refresh);
        refresh();
    }

    public Node getNode() { return root; }

    public void setOnStepClicked(IntConsumer c) {
        this.onStepClicked = (c == null) ? i -> {} : c;
    }

    /** 각 단계 완료 여부 (길이 5). 처음으로 끝나지 않은 단계가 "지금 할 단계". */
    public void setDone(boolean... stepDone) {
        boolean changed = stepDone.length != done.length;
        for (int i = 0; !changed && i < done.length; i++) changed = done[i] != stepDone[i];
        if (!changed) return;
        done = stepDone.clone();
        refresh();
    }

    private void refresh() {
        int current = -1;
        for (int i = 0; i < done.length; i++) { if (!done[i]) { current = i; break; } }

        String accent = Styles.accent();
        String sub = Styles.textSub();
        String trackOff = Styles.isDark() ? "rgba(255,255,255,0.14)" : "rgba(0,0,0,0.10)";

        root.setStyle(
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-background-radius:18;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:18;" +
                "-fx-border-width:0.5;" +
                "-fx-effect:dropshadow(gaussian,rgba(0,0,0," + (Styles.isDark() ? "0.6" : "0.10") + "),20,0.08,0,4);");

        for (int i = 0; i < done.length; i++) {
            boolean isDone = done[i], isCur = (i == current);
            String dotBg, dotBorder, dotFg, nameFg, weight;
            if (isDone) {
                dotBg = accent; dotBorder = accent; dotFg = "white"; nameFg = Styles.textMain(); weight = "600";
                dotLabels[i].setText("✓");
            } else if (isCur) {
                dotBg = "transparent"; dotBorder = accent; dotFg = accent; nameFg = accent; weight = "700";
                dotLabels[i].setText(String.valueOf(i + 1));
            } else {
                dotBg = "transparent"; dotBorder = trackOff; dotFg = sub; nameFg = sub; weight = "400";
                dotLabels[i].setText(String.valueOf(i + 1));
            }
            dots[i].setStyle(
                    "-fx-background-color:" + dotBg + ";" +
                    "-fx-background-radius:12;" +
                    "-fx-border-color:" + dotBorder + ";" +
                    "-fx-border-radius:12;" +
                    "-fx-border-width:" + (isCur ? "2" : "1.5") + ";");
            dotLabels[i].setStyle(
                    "-fx-text-fill:" + dotFg + ";" +
                    "-fx-font-size:11px;" +
                    "-fx-font-weight:700;" +
                    "-fx-font-family:" + Styles.FONT_STACK + ";");
            names[i].setStyle(
                    "-fx-text-fill:" + nameFg + ";" +
                    "-fx-font-size:11px;" +
                    "-fx-font-weight:" + weight + ";" +
                    "-fx-font-family:" + Styles.FONT_STACK + ";");
            if (i < links.length) {
                links[i].setStyle("-fx-background-color:" + (isDone ? accent : trackOff) + ";");
            }
        }
    }
}
