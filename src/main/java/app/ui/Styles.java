package app.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class Styles {

    // ── Theme state ───────────────────────────────────────────────────────────
    private static boolean darkMode = false;
    private static final ArrayList<Runnable> THEME_LISTENERS = new ArrayList<>();
    private static final Set<Object> REGISTERED =
            Collections.newSetFromMap(new WeakHashMap<>());

    public static void setDark(boolean dark) {
        if (darkMode == dark) return;
        darkMode = dark;
        new ArrayList<>(THEME_LISTENERS).forEach(Runnable::run);
    }

    public static boolean isDark() { return darkMode; }

    public static void addThemeListener(Runnable r) { THEME_LISTENERS.add(r); }

    /** Returns true the first time this object is registered; false if already registered. */
    static boolean registerOnce(Object node) { return REGISTERED.add(node); }

    // ── Theme-aware color methods ─────────────────────────────────────────────
    public static String bgApp()      { return darkMode ? "#111113" : "#F2F2F7"; }
    public static String bgPanel()    { return darkMode ? "#1C1C1E" : "#FFFFFF"; }
    public static String bgRow()      { return darkMode ? "rgba(255,255,255,0.07)" : "#EBEBEE"; }
    public static String bgRowHdr()   { return darkMode ? "rgba(255,255,255,0.12)" : "#D8D8DC"; }
    public static String bgTabBar()   { return darkMode ? "rgba(255,255,255,0.08)" : "#E8EBF0"; }
    public static String borderSoft() { return darkMode ? "rgba(255,255,255,0.10)" : "rgba(0,0,0,0.09)"; }
    public static String textMain()   { return darkMode ? "#F2F2F5" : "#1C1C1E"; }
    public static String textSub()    { return darkMode ? "#98989E" : "#6B7280"; }
    public static String accent()     { return darkMode ? "#0A84FF" : "#3B82F6"; }

    // ── Static hex constants (must stay hex for Color.web() compatibility) ───
    public static final String RSSI_LOW   = "#2D6CF6";
    public static final String RSSI_MID1  = "#22C55E";
    public static final String RSSI_MID2  = "#EAB308";
    public static final String RSSI_MID3  = "#F97316";
    public static final String RSSI_HIGH  = "#EF4444";

    public static final String STATUS_INFO    = "#3B82F6";
    public static final String STATUS_SUCCESS = "#22C55E";
    public static final String STATUS_ERROR   = "#EF4444";

    public static final String FONT_STACK =
            "'-Apple SD Gothic Neo','Apple SD 산돌고딕 Neo','Noto Sans KR','Malgun Gothic',sans-serif";
    public static final String FONT_MONO  = "'SF Mono','Menlo','Monaco',monospace";

    // ── Liquid Glass internals ────────────────────────────────────────────────
    private static String glassSpecular() {
        return darkMode ? "rgba(255,255,255,0.05)" : "rgba(255,255,255,0.55)";
    }
    private static String glassBody() {
        // 다크: 반투명 카드 (앱 배경 #111113 위에서 약간 밝게)
        return darkMode ? "rgba(38,38,40,0.96)" : "rgba(255,255,255,0.82)";
    }
    private static String glassShadow() {
        return darkMode
                ? "dropshadow(gaussian,rgba(0,0,0,0.70),16,0.10,0,3)"
                : "dropshadow(gaussian,rgba(0,0,0,0.08),14,0.06,0,2)";
    }
    private static String inputBg() {
        return darkMode ? "#2C2C2E" : "rgba(255,255,255,0.90)";
    }
    private static String btnBg() {
        return darkMode ? "#2C2C2E" : "rgba(255,255,255,0.80)";
    }
    private static String btnHoverBg() {
        return darkMode ? "#3A3A3C" : "rgba(255,255,255,0.96)";
    }
    private static String btnPressedBg() {
        return darkMode ? "#1C1C1E" : "rgba(210,210,218,0.95)";
    }

    // ── Shared style strings ──────────────────────────────────────────────────
    static String flatBase() {
        return "-fx-background-color:" + btnBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-background-radius:8;" +
               "-fx-border-color:" + borderSoft() + ";" +
               "-fx-border-radius:8;" +
               "-fx-padding:6 10;" +
               "-fx-font-size:12px;";
    }
    private static String flatHover() {
        return "-fx-background-color:" + btnHoverBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-background-radius:8;" +
               "-fx-border-color:" + accent() + ";" +
               "-fx-border-radius:8;" +
               "-fx-padding:6 10;" +
               "-fx-font-size:12px;";
    }
    private static String flatPressed() {
        return "-fx-background-color:" + btnPressedBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-background-radius:8;" +
               "-fx-border-color:" + accent() + ";" +
               "-fx-border-radius:8;" +
               "-fx-padding:6 10;" +
               "-fx-font-size:12px;";
    }
    private static String accentBase() {
        return "-fx-background-color:" + accent() + ";" +
               "-fx-text-fill:white;" +
               "-fx-background-radius:8;" +
               "-fx-padding:6 12;" +
               "-fx-font-size:12px;";
    }
    static String textFieldBase() {
        return "-fx-background-color:" + inputBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-prompt-text-fill:" + textSub() + ";" +
               "-fx-background-radius:8;" +
               "-fx-border-color:" + borderSoft() + ";" +
               "-fx-border-radius:8;" +
               "-fx-padding:6 8;" +
               "-fx-font-size:12px;";
    }
    static String comboBase() {
        return "-fx-background-color:" + inputBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-border-color:" + borderSoft() + ";" +
               "-fx-border-radius:8;" +
               "-fx-background-radius:8;" +
               "-fx-padding:1 2;" +
               "-fx-font-size:12px;";
    }

    // ── Public style helpers ──────────────────────────────────────────────────

    public static void styleFlatButton(Button b) {
        Runnable apply = () -> {
            b.setStyle(flatBase());
            b.setOnMouseEntered(e -> { if (!b.isDisabled()) b.setStyle(flatHover()); });
            b.setOnMouseExited(e -> b.setStyle(flatBase()));
            b.setOnMousePressed(e -> { if (!b.isDisabled()) b.setStyle(flatPressed()); });
            b.setOnMouseReleased(e -> { if (!b.isDisabled()) b.setStyle(flatHover()); });
        };
        apply.run();
        if (registerOnce(b)) addThemeListener(apply);
    }

    public static void styleAccentButton(Button b) {
        Runnable apply = () -> {
            b.setStyle(accentBase());
            b.setOnMouseEntered(e -> { if (!b.isDisabled()) b.setStyle(
                    "-fx-background-color:derive(" + accent() + ",-15%);-fx-text-fill:white;" +
                    "-fx-background-radius:8;-fx-padding:6 12;-fx-font-size:12px;"); });
            b.setOnMouseExited(e -> b.setStyle(accentBase()));
            b.setOnMousePressed(e -> { if (!b.isDisabled()) b.setStyle(
                    "-fx-background-color:derive(" + accent() + ",-25%);-fx-text-fill:white;" +
                    "-fx-background-radius:8;-fx-padding:6 12;-fx-font-size:12px;"); });
            b.setOnMouseReleased(e -> { if (!b.isDisabled()) b.setStyle(
                    "-fx-background-color:derive(" + accent() + ",-15%);-fx-text-fill:white;" +
                    "-fx-background-radius:8;-fx-padding:6 12;-fx-font-size:12px;"); });
        };
        apply.run();
        if (registerOnce(b)) addThemeListener(apply);
    }

    public static void styleTextField(TextField tf) {
        Runnable apply = () -> tf.setStyle(textFieldBase());
        apply.run();
        if (registerOnce(tf)) addThemeListener(apply);
    }

    public static void styleSpinner(Spinner<?> sp) {
        Runnable apply = () -> {
            sp.setStyle(
                    "-fx-background-color:" + inputBg() + ";" +
                    "-fx-border-color:" + borderSoft() + ";" +
                    "-fx-border-radius:8;" +
                    "-fx-background-radius:8;");
            if (sp.getEditor() != null) {
                sp.getEditor().setStyle(textFieldBase());
            }
        };
        apply.run();
        if (registerOnce(sp)) addThemeListener(apply);
    }

    public static void styleToggle(ToggleButton t) {
        if (registerOnce(t)) {
            t.selectedProperty().addListener((o, ov, nv) -> applyToggleStyle(t, nv));
            addThemeListener(() -> applyToggleStyle(t, t.isSelected()));
        }
        applyToggleStyle(t, t.isSelected());
    }

    private static void applyToggleStyle(ToggleButton t, boolean selected) {
        if (selected) {
            t.setStyle("-fx-background-color:" + accent() + ";" +
                    "-fx-text-fill:white;" +
                    "-fx-background-radius:8;" +
                    "-fx-border-color:transparent;" +
                    "-fx-border-radius:8;" +
                    "-fx-padding:6 10;" +
                    "-fx-font-size:12px;");
        } else {
            t.setStyle("-fx-background-color:" + btnBg() + ";" +
                    "-fx-text-fill:" + textMain() + ";" +
                    "-fx-background-radius:8;" +
                    "-fx-border-color:" + borderSoft() + ";" +
                    "-fx-border-radius:8;" +
                    "-fx-padding:6 10;" +
                    "-fx-font-size:12px;");
        }
    }

    /** Left-panel "card" container (Liquid Glass, auto-updates on theme change). */
    public static VBox card(String title, Node... content) {
        Label t = new Label(title);
        Runnable applyTitle = () -> t.setStyle(
                "-fx-text-fill:" + textMain() + ";" +
                "-fx-font-size:13px;" +
                "-fx-font-weight:600;" +
                "-fx-font-family:" + FONT_STACK + ";");
        applyTitle.run();

        VBox box = new VBox(10);
        box.getChildren().add(t);
        box.getChildren().addAll(content);
        box.setPadding(new Insets(14));
        Runnable applyCard = () -> box.setStyle(
                "-fx-background-color:" + glassSpecular() + "," + glassBody() + ";" +
                "-fx-background-insets:0,0.5;" +
                "-fx-background-radius:18;" +
                "-fx-border-color:" + borderSoft() + ";" +
                "-fx-border-radius:18;" +
                "-fx-border-width:0.5;" +
                "-fx-effect:" + glassShadow() + ";");
        applyCard.run();
        addThemeListener(applyTitle);
        addThemeListener(applyCard);
        return box;
    }

    /**
     * Registers a ComboBox to style its popup with the current theme each time it opens.
     * No additional theme listener needed — the popup is freshly looked up on each open.
     */
    public static void installComboPopupStyle(ComboBox<?> cb) {
        cb.showingProperty().addListener((o, ov, showing) -> {
            if (!showing) return;
            Platform.runLater(() -> {
                try {
                    String popupBg = darkMode ? "#2C2C2E" : "#FFFFFF";
                    Node popup = cb.lookup(".combo-box-popup");
                    if (popup != null) popup.setStyle(
                            "-fx-background-color:" + popupBg + ";" +
                            "-fx-border-color:" + borderSoft() + ";" +
                            "-fx-border-radius:10;" +
                            "-fx-background-radius:10;" +
                            "-fx-padding:6;");
                    Node lv = cb.lookup(".combo-box-popup .list-view");
                    if (lv != null) lv.setStyle(
                            "-fx-background-color:" + popupBg + ";" +
                            "-fx-control-inner-background:" + popupBg + ";" +
                            "-fx-text-fill:" + textMain() + ";" +
                            "-fx-border-color:transparent;" +
                            "-fx-background-radius:10;");
                } catch (Exception ignored) {}
            });
        });
    }
}
