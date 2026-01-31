package app.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

public class Styles {

    public static final String BG_APP = "#FFFFFF";
    public static final String BG_PANEL = "#F7F8FA";
    public static final String BORDER_SOFT = "#E6E9EF";
    public static final String TEXT_MAIN = "#1F2937";
    public static final String TEXT_SUB = "#6B7280";
    public static final String ACCENT = "#3B82F6";

    public static void styleFlatButton(Button b) {
        b.setStyle("-fx-background-color: " + BG_PANEL + ";" +
                "-fx-text-fill: " + TEXT_MAIN + ";" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: " + BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-padding: 6 10;" +
                "-fx-font-size: 12px;");
    }

    public static void styleAccentButton(Button b) {
        b.setStyle("-fx-background-color: " + ACCENT + ";" +
                "-fx-text-fill: white;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 6 12;" +
                "-fx-font-size: 12px;");
    }

    public static void styleTextField(TextField tf) {
        tf.setStyle("-fx-background-color: " + BG_APP + ";" +
                "-fx-text-fill: " + TEXT_MAIN + ";" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: " + BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-padding: 6 8;" +
                "-fx-font-size: 12px;");
    }

    public static void styleSpinner(Spinner<?> sp) {
        sp.setStyle("-fx-background-color: " + BG_APP + ";" +
                "-fx-border-color: " + BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;");
        if (sp.getEditor() != null) styleTextField(sp.getEditor());
    }

    public static void styleToggle(ToggleButton t) {
        applyToggleStyle(t, t.isSelected());
        t.selectedProperty().addListener((o, ov, nv) -> applyToggleStyle(t, nv));
    }

    private static void applyToggleStyle(ToggleButton t, boolean selected) {
        if (selected) {
            t.setStyle("-fx-background-color: " + ACCENT + ";" +
                    "-fx-text-fill: white;" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: transparent;" +
                    "-fx-border-radius: 8;" +
                    "-fx-padding: 6 10;" +
                    "-fx-font-size: 12px;");
        } else {
            t.setStyle("-fx-background-color: " + BG_PANEL + ";" +
                    "-fx-text-fill: " + TEXT_MAIN + ";" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: " + BORDER_SOFT + ";" +
                    "-fx-border-radius: 8;" +
                    "-fx-padding: 6 10;" +   // ✅ 여기 중요 (찌꺼기 제거)
                    "-fx-font-size: 12px;");
        }
    }

    /** Left-panel “card” container */
    public static VBox card(String title, Node... content) {
        Label t = new Label(title);
        t.setStyle("-fx-text-fill: " + TEXT_MAIN + "; -fx-font-size: 13px; -fx-font-weight: 600;");
        VBox box = new VBox(10);
        box.getChildren().add(t);
        box.getChildren().addAll(content);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: " + BG_APP + ";" +
                "-fx-background-radius: 12;" +
                "-fx-border-color: " + BORDER_SOFT + ";" +
                "-fx-border-radius: 12;");
        return box;
    }

    /** ComboBox popup style */
    public static void installComboPopupStyle(ComboBox<?> cb) {
        cb.showingProperty().addListener((o, ov, showing) -> {
            if (!showing) return;
            Platform.runLater(() -> {
                try {
                    Node popup = cb.lookup(".combo-box-popup");
                    if (popup != null) popup.setStyle(
                            "-fx-background-color: " + BG_APP + ";" +
                                    "-fx-border-color: " + BORDER_SOFT + ";" +
                                    "-fx-border-radius: 10;" +
                                    "-fx-background-radius: 10;" +
                                    "-fx-padding: 6;"
                    );
                    Node lv = cb.lookup(".combo-box-popup .list-view");
                    if (lv != null) lv.setStyle(
                            "-fx-background-color: " + BG_APP + ";" +
                                    "-fx-control-inner-background: " + BG_APP + ";" +
                                    "-fx-border-color: transparent;" +
                                    "-fx-background-radius: 10;"
                    );
                } catch (Exception ignored) {}
            });
        });
    }
}