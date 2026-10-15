package app.ui;

import app.model.RssiLevel;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * 하단 바 — 왼쪽: 작업 상태(히트맵 생성 진행률·완료·알림), 오른쪽: 마우스 위치 신호 + 등급 범례.
 *
 * <p>범례 색·등급 이름·기준값은 {@link RssiLevel} 한 곳의 정의를 따른다 — 히트맵 색, 결과 카드와
 * 같은 의미가 되도록. (예전엔 범례가 -96~-10 dBm 연속 그라데이션이라 결과 카드의 등급 색과 달랐고,
 * 왼쪽에는 DPM 히트맵에 아무 영향이 없는 "수신 단말 높이" 입력이 있었다.)</p>
 */
public class BottomBar {
    private static final String RSSI_LABEL_BASE_STYLE =
            "-fx-font-family:" + Styles.FONT_STACK + ";" +
            "-fx-font-size:15px;" +
            "-fx-font-weight:700;";

    private static final double LEGEND_WIDTH = 240;

    private final HBox root = new HBox(10);
    private final Label heatmapStatusLabel = new Label();
    private final Label currentRssiLabel = new Label("신호 측정 없음");
    private final StackPane legendBar = new StackPane();
    private final Region legendBg = new Region();
    private final Pane markerLayer = new Pane();
    private final Region markerLine = new Region();
    private final HBox legendNames = new HBox();
    private final PauseTransition statusClearTimer = new PauseTransition(Duration.seconds(3));

    private double currentRssiDbm = Double.NaN;

    public BottomBar() {
        currentRssiLabel.setStyle("-fx-text-fill: " + Styles.textSub() + ";" + RSSI_LABEL_BASE_STYLE);

        heatmapStatusLabel.setVisible(false);
        heatmapStatusLabel.setManaged(false);
        statusClearTimer.setOnFinished(e -> clearHeatmapStatus());

        // ── 범례: 그라데이션 바 + 현재 위치 마커 ─────────────────────────────
        legendBg.setStyle(
                "-fx-background-radius: 5;" +
                "-fx-background-color: " + RssiLevel.legendGradientCss() + ";" +
                "-fx-border-color: rgba(0,0,0,0.14);" +
                "-fx-border-radius: 5;");
        markerLine.setManaged(false);
        markerLine.setVisible(false);
        markerLine.setStyle(
                "-fx-background-color: rgba(255,255,255,0.95);" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 4, 0.4, 0, 0);");
        markerLayer.getChildren().add(markerLine);
        markerLayer.setMouseTransparent(true);
        legendBar.getChildren().addAll(legendBg, markerLayer);
        legendBar.setPrefSize(LEGEND_WIDTH, 12);
        legendBar.setMinHeight(12);
        legendBar.setMaxHeight(12);
        legendBar.widthProperty().addListener((o, ov, nv) -> updateLegendMarker());
        legendBar.heightProperty().addListener((o, ov, nv) -> updateLegendMarker());

        // ── 범례 아래 등급 이름 — 각 등급이 바에서 차지하는 비율만큼 칸을 나눈다 ──
        RssiLevel[] weakToStrong = { RssiLevel.DEAD, RssiLevel.WEAK, RssiLevel.GOOD, RssiLevel.STRONG };
        for (RssiLevel lv : weakToStrong) {
            Label name = new Label(lv.label);
            name.setAlignment(Pos.CENTER);
            name.setMaxWidth(Double.MAX_VALUE);
            name.prefWidthProperty().bind(legendBar.widthProperty().multiply(lv.legendShare()));
            name.setMinWidth(0);
            Themed.bind(name, () -> "-fx-text-fill:" + Styles.textSub() + ";"
                    + "-fx-font-size:" + Styles.FONT_SIZE_TINY + "px;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
            legendNames.getChildren().add(name);
        }
        VBox legend = new VBox(3, legendBar, legendNames);
        legend.setAlignment(Pos.CENTER);
        legend.setPrefWidth(LEGEND_WIDTH);
        legend.setMaxWidth(LEGEND_WIDTH);
        Tooltip.install(legend, new Tooltip(String.format(
                "%s %.0f dBm 이상 — %s%n%s %.0f ~ %.0f — %s%n%s %.0f ~ %.0f — %s%n%s %.0f dBm 미만 — %s%n(기준: MetaGeek 무선랜 신호 세기 가이드)",
                RssiLevel.STRONG.label, RssiLevel.STRONG.minDbm, RssiLevel.STRONG.meaning,
                RssiLevel.GOOD.label, RssiLevel.GOOD.minDbm, RssiLevel.STRONG.minDbm, RssiLevel.GOOD.meaning,
                RssiLevel.WEAK.label, RssiLevel.WEAK.minDbm, RssiLevel.GOOD.minDbm, RssiLevel.WEAK.meaning,
                RssiLevel.DEAD.label, RssiLevel.WEAK.minDbm, RssiLevel.DEAD.meaning)));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        root.getChildren().addAll(heatmapStatusLabel, spacer, currentRssiLabel, legend);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(6, 14, 6, 14));
        root.setMinHeight(40);
        Runnable applyRoot = () -> {
            root.setStyle(
                    "-fx-background-color:" + Styles.bgApp() + ";" +
                    "-fx-border-color:" + Styles.borderSoft() + ";" +
                    "-fx-border-width:0.5 0 0 0;");
            refreshCurrentRssiLabel();
        };
        applyRoot.run();
        Styles.addThemeListener(applyRoot);
    }

    public Node getNode() {
        return root;
    }

    public void setCurrentRssi(double rssiDbm) {
        currentRssiDbm = rssiDbm;
        refreshCurrentRssiLabel();
        updateLegendMarker();
    }

    private void refreshCurrentRssiLabel() {
        if (Double.isFinite(currentRssiDbm)) {
            currentRssiLabel.setText(String.format("%s · %.0f dBm",
                    RssiLevel.of(currentRssiDbm).label, currentRssiDbm));
            currentRssiLabel.setStyle("-fx-text-fill: " + toHex(RssiLevel.gradientColor(currentRssiDbm)) + ";"
                    + RSSI_LABEL_BASE_STYLE);
        } else {
            currentRssiLabel.setText("신호 측정 없음");
            currentRssiLabel.setStyle("-fx-text-fill: " + Styles.textSub() + ";" + RSSI_LABEL_BASE_STYLE);
        }
    }

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    private void updateLegendMarker() {
        if (!Double.isFinite(currentRssiDbm) || legendBar.getWidth() <= 0) {
            markerLine.setVisible(false);
            return;
        }
        double t = (currentRssiDbm - RssiLevel.LEGEND_MIN_DBM)
                / (RssiLevel.LEGEND_MAX_DBM - RssiLevel.LEGEND_MIN_DBM);
        t = Math.max(0.0, Math.min(1.0, t));
        double x = t * legendBar.getWidth();
        double h = legendBar.getHeight() + 6;
        markerLine.setVisible(true);
        markerLine.resizeRelocate(x - 1, -3, 2, h);
    }

    // ── 왼쪽 상태 메시지 ────────────────────────────────────────────────────

    private void showStatus(String text, String colorHex, boolean autoHide) {
        statusClearTimer.stop();
        heatmapStatusLabel.setText(text);
        heatmapStatusLabel.setStyle(
                "-fx-text-fill:" + colorHex + ";" +
                "-fx-font-size:13px;" +
                "-fx-font-weight:600;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        heatmapStatusLabel.setVisible(true);
        heatmapStatusLabel.setManaged(true);
        if (autoHide) statusClearTimer.playFromStart();
    }

    /** 히트맵 생성 진행률 표시 (0~100) */
    public void setHeatmapProgress(double percent) {
        showStatus(String.format("신호 지도를 만드는 중… %.0f%%", Math.min(100.0, percent)),
                Styles.accent(), false);
    }

    /** 일반 알림 — 짧은 메시지를 잠시 표시 (3초 뒤 자동 숨김). */
    public void showToast(String message) {
        showStatus(message, Styles.STATUS_SUCCESS, true);
    }

    /** 히트맵 생성 완료 메시지 표시 → 3초 뒤 자동 숨김 */
    public void setHeatmapComplete(double elapsedSec) {
        showStatus(String.format("신호 지도 완성 (%.1f초)", elapsedSec), Styles.STATUS_SUCCESS, true);
    }

    /**
     * DPM 탐색 측정값 표시 (고급 모드 전용 — 자동 숨김 없음, 다음 갱신까지 유지).
     * 발표에서 가지치기 효과를 보여줄 때 쓴다.
     */
    public void setDpmStats(long expanded, long pruned, long elapsedMs) {
        long total = expanded + pruned;
        double pruneRatio = total > 0 ? 100.0 * pruned / total : 0.0;
        statusClearTimer.stop();
        heatmapStatusLabel.setText(String.format(
                "[DPM A*]  expanded=%,d   pruned=%,d (%.1f%%)   elapsed=%,d ms",
                expanded, pruned, pruneRatio, elapsedMs));
        heatmapStatusLabel.setStyle(
                "-fx-text-fill:" + Styles.accent() + ";" +
                "-fx-font-size:12px;" +
                "-fx-font-weight:600;" +
                "-fx-font-family:" + Styles.FONT_MONO + ";");
        heatmapStatusLabel.setVisible(true);
        heatmapStatusLabel.setManaged(true);
    }

    /** 상태 메시지 숨김 */
    public void clearHeatmapStatus() {
        statusClearTimer.stop();
        heatmapStatusLabel.setText("");
        heatmapStatusLabel.setVisible(false);
        heatmapStatusLabel.setManaged(false);
    }
}
