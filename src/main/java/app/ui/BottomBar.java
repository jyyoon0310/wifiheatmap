package app.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.util.function.DoubleConsumer;

public class BottomBar {
    private static final String FONT_STACK = "'Pretendard', 'Apple SD Gothic Neo', 'Malgun Gothic', 'Segoe UI', sans-serif";
    private static final String RSSI_LABEL_BASE_STYLE =
            "-fx-font-family: " + FONT_STACK + ";" +
            "-fx-font-size: 15px;" +
            "-fx-font-weight: 700;";


    private final HBox root = new HBox(10);
    private final Spinner<Double> clientHeightSpinner = new Spinner<>(0.1, 5.0, 1.0, 0.1);
    private final Button applyBtn = new Button("적용");
    private final Label currentRssiLabel = new Label("- dBm");
    private final Label statusLabel = new Label();
    private final Label legendMinLabel = new Label("-96 dBm");
    private final Label legendMaxLabel = new Label("-10 dBm");
    private final StackPane legendPane = new StackPane();
    private final Region legendBg = new Region();
    private final Pane markerLayer = new Pane();
    private final Region markerLine = new Region();

    private double legendMinDbm = -96.0;
    private double legendMaxDbm = -10.0;
    private double currentRssiDbm = Double.NaN;

    private DoubleConsumer onApplyClientHeight;

    public BottomBar() {
        Label label = new Label("Client Height (m)");
        Runnable applyLabel = () -> label.setStyle(
                "-fx-text-fill: " + Styles.textSub() + ";" +
                "-fx-font-size: 12px;" +
                "-fx-font-family: " + FONT_STACK + ";");
        applyLabel.run();
        Styles.addThemeListener(applyLabel);

        clientHeightSpinner.setEditable(true);
        Styles.styleSpinner(clientHeightSpinner);
        Styles.styleAccentButton(applyBtn);
        currentRssiLabel.setStyle("-fx-text-fill: #2FD44A;" + RSSI_LABEL_BASE_STYLE);

        HBox legendLabels = new HBox();
        legendLabels.setAlignment(Pos.CENTER);
        legendLabels.setPadding(new Insets(5, 6, 1, 6));
        legendMinLabel.setStyle(
                "-fx-text-fill: white;" +
                        "-fx-font-size: 12px;" +
                        "-fx-font-weight: 700;" +
                        "-fx-font-family: " + FONT_STACK + ";" +
                        "-fx-padding: 0 2;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.55), 3, 0.2, 0, 1);"
        );
        legendMaxLabel.setStyle(
                "-fx-text-fill: white;" +
                        "-fx-font-size: 12px;" +
                        "-fx-font-weight: 700;" +
                        "-fx-font-family: " + FONT_STACK + ";" +
                        "-fx-padding: 0 2;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.55), 3, 0.2, 0, 1);"
        );
        Region legendSpacer = new Region();
        HBox.setHgrow(legendSpacer, Priority.ALWAYS);
        legendLabels.getChildren().addAll(legendMinLabel, legendSpacer, legendMaxLabel);

        legendBg.setStyle(
                "-fx-background-radius: 4;" +
                        "-fx-background-color: linear-gradient(to right, #2D6CF6, #34D1FF, #2FD44A, #F6D32D, #F0632D);" +
                        "-fx-border-color: rgba(0,0,0,0.18);" +
                        "-fx-border-radius: 4;"
        );
        markerLine.setManaged(false);
        markerLine.setVisible(false);
        markerLine.setStyle(
                "-fx-background-color: rgba(255,255,255,0.95);" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 4, 0.4, 0, 0);"
        );
        markerLine.setPrefWidth(2);
        markerLayer.getChildren().add(markerLine);
        markerLayer.setMouseTransparent(true);

        legendPane.getChildren().addAll(legendBg, markerLayer, legendLabels);
        legendPane.setPrefWidth(226);
        legendPane.setMinHeight(28);
        legendPane.widthProperty().addListener((o, ov, nv) -> updateLegendMarker());
        legendPane.heightProperty().addListener((o, ov, nv) -> updateLegendMarker());

        applyBtn.setOnAction(e -> {
            if (onApplyClientHeight == null) return;
            onApplyClientHeight.accept(clientHeightSpinner.getValue());
        });

        Runnable applyStatus = () -> statusLabel.setStyle(
                "-fx-text-fill: " + Styles.textSub() + ";" +
                "-fx-font-size: 12px;" +
                "-fx-font-family: " + FONT_STACK + ";");
        applyStatus.run();
        Styles.addThemeListener(applyStatus);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        root.getChildren().addAll(label, clientHeightSpinner, applyBtn, spacer, statusLabel, currentRssiLabel, legendPane);
        root.setPadding(new Insets(8, 10, 8, 10));
        Runnable applyRoot = () -> root.setStyle(
                "-fx-alignment:center-left;" +
                "-fx-background-color:" + Styles.bgApp() + ";" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-width:1 0 0 0;");
        applyRoot.run();
        Styles.addThemeListener(applyRoot);
    }

    public Node getNode() {
        return root;
    }

    public void setStatus(String message) {
        statusLabel.setText(message == null ? "" : message);
    }

    public void setOnApplyClientHeight(DoubleConsumer onApplyClientHeight) {
        this.onApplyClientHeight = onApplyClientHeight;
    }

    public void setClientHeight(double value) {
        clientHeightSpinner.getValueFactory().setValue(value);
    }

    public void setRssiLegendRange(double minDbm, double maxDbm) {
        legendMinDbm = minDbm;
        legendMaxDbm = maxDbm;
        legendMinLabel.setText(String.format("%.0f dBm", minDbm));
        legendMaxLabel.setText(String.format("%.0f dBm", maxDbm));
        refreshCurrentRssiLabel();
        updateLegendMarker();
    }

    public void setCurrentRssi(double rssiDbm) {
        currentRssiDbm = rssiDbm;
        refreshCurrentRssiLabel();
        updateLegendMarker();
    }

    private void refreshCurrentRssiLabel() {
        if (Double.isFinite(currentRssiDbm)) {
            currentRssiLabel.setText(String.format("%.0f dBm", currentRssiDbm));
            String colorHex = toHex(rssiColor(currentRssiDbm));
            currentRssiLabel.setStyle("-fx-text-fill: " + colorHex + ";" + RSSI_LABEL_BASE_STYLE);
        } else {
            currentRssiLabel.setText("- dBm");
            currentRssiLabel.setStyle("-fx-text-fill: " + Styles.textSub() + ";" + RSSI_LABEL_BASE_STYLE);
        }
    }

    private Color rssiColor(double rssiDbm) {
        if (legendMaxDbm <= legendMinDbm) {
            return Color.web("#2D6CF6");
        }
        double t = (rssiDbm - legendMinDbm) / (legendMaxDbm - legendMinDbm);
        t = Math.max(0.0, Math.min(1.0, t));

        Color c0 = Color.web("#2D6CF6");
        Color c1 = Color.web("#34D1FF");
        Color c2 = Color.web("#2FD44A");
        Color c3 = Color.web("#F6D32D");
        Color c4 = Color.web("#F0632D");

        if (t < 0.25) return lerp(c0, c1, t / 0.25);
        if (t < 0.50) return lerp(c1, c2, (t - 0.25) / 0.25);
        if (t < 0.75) return lerp(c2, c3, (t - 0.50) / 0.25);
        return lerp(c3, c4, (t - 0.75) / 0.25);
    }

    private static Color lerp(Color a, Color b, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return new Color(
                a.getRed() + (b.getRed() - a.getRed()) * t,
                a.getGreen() + (b.getGreen() - a.getGreen()) * t,
                a.getBlue() + (b.getBlue() - a.getBlue()) * t,
                1.0
        );
    }

    private static String toHex(Color c) {
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private void updateLegendMarker() {
        if (!Double.isFinite(currentRssiDbm) || legendPane.getWidth() <= 0 || legendMaxDbm <= legendMinDbm) {
            markerLine.setVisible(false);
            return;
        }

        double t = (currentRssiDbm - legendMinDbm) / (legendMaxDbm - legendMinDbm);
        t = Math.max(0.0, Math.min(1.0, t));

        double w = legendPane.getWidth();
        double h = legendPane.getHeight();
        double x = t * w;

        markerLine.setVisible(true);
        markerLine.setPrefHeight(Math.max(18, h - 4));
        markerLine.resizeRelocate(x - 1, 2, 2, Math.max(18, h - 4));
    }
}
