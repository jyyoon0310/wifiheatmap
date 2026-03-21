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

    /** Returns true the first time this object is registered. */
    static boolean registerOnce(Object node) { return REGISTERED.add(node); }

    // ── Theme-aware colour palette ────────────────────────────────────────────
    public static String bgApp()      { return darkMode ? "#111113" : "#F0F0F5"; }
    public static String bgPanel()    { return darkMode ? "#1C1C1E" : "#FFFFFF"; }
    public static String bgRow()      { return darkMode ? "rgba(255,255,255,0.07)" : "rgba(0,0,0,0.04)"; }
    public static String bgRowHdr()   { return darkMode ? "rgba(255,255,255,0.13)" : "rgba(0,0,0,0.07)"; }
    public static String bgTabBar()   { return darkMode ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)"; }
    public static String borderSoft() { return darkMode ? "rgba(255,255,255,0.10)" : "rgba(0,0,0,0.08)"; }
    public static String textMain()   { return darkMode ? "#F2F2F5" : "#1C1C1E"; }
    public static String textSub()    { return darkMode ? "#98989E" : "#6B7280"; }
    public static String accent()     { return darkMode ? "#0A84FF" : "#007AFF"; }

    // ── Static hex constants ──────────────────────────────────────────────────
    public static final String RSSI_LOW   = "#2D6CF6";
    public static final String RSSI_MID1  = "#22C55E";
    public static final String RSSI_MID2  = "#EAB308";
    public static final String RSSI_MID3  = "#F97316";
    public static final String RSSI_HIGH  = "#EF4444";

    public static final String STATUS_INFO    = "#3B82F6";
    public static final String STATUS_SUCCESS = "#22C55E";
    public static final String STATUS_ERROR   = "#EF4444";

    public static final String FONT_STACK =
            "'SF Pro Text','-Apple SD Gothic Neo','Apple SD 산돌고딕 Neo','Noto Sans KR','Malgun Gothic',sans-serif";
    public static final String FONT_MONO  = "'SF Mono','Menlo','Monaco','Consolas',monospace";

    // ── Liquid Glass internals ────────────────────────────────────────────────
    private static String glassSpecular() {
        // Top white veil — stronger in light, subtler in dark
        return darkMode ? "rgba(255,255,255,0.06)" : "rgba(255,255,255,0.75)";
    }
    private static String glassBody() {
        // 다이얼로그: 배경 비침 방지를 위해 불투명도 높임 (0.88 → 0.96)
        return darkMode ? "rgba(34,34,36,0.96)" : "rgba(250,250,253,0.96)";
    }
    private static String glassBorder() {
        // Inner rim glow — white for light, subtle for dark
        return darkMode ? "rgba(255,255,255,0.14)" : "rgba(255,255,255,0.78)";
    }
    private static String glassShadow() {
        return darkMode
                ? "dropshadow(gaussian,rgba(0,0,0,0.85),28,0.14,0,6)"
                : "dropshadow(gaussian,rgba(0,0,0,0.13),24,0.08,0,5)";
    }
    private static String inputBg() {
        return darkMode ? "#2C2C2E" : "rgba(255,255,255,0.92)";
    }
    private static String btnBg() {
        return darkMode ? "#2C2C2E" : "rgba(255,255,255,0.82)";
    }
    private static String btnHoverBg() {
        return darkMode ? "#3A3A3C" : "rgba(255,255,255,0.98)";
    }
    private static String btnPressedBg() {
        return darkMode ? "#1C1C1E" : "rgba(210,210,220,0.95)";
    }
    private static String popupBg() {
        return darkMode ? "#242426" : "#FFFFFF";
    }
    private static String popupShadow() {
        return darkMode
                ? "dropshadow(gaussian,rgba(0,0,0,0.90),32,0.18,0,8)"
                : "dropshadow(gaussian,rgba(0,0,0,0.18),28,0.10,0,7)";
    }

    // ── Shared style strings ──────────────────────────────────────────────────
    static String flatBase() {
        return "-fx-background-color:" + btnBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-background-radius:9;" +
               "-fx-border-color:" + borderSoft() + ";" +
               "-fx-border-radius:9;" +
               "-fx-padding:6 10;" +
               "-fx-font-size:12px;" +
               "-fx-font-family:" + FONT_STACK + ";";
    }
    private static String flatHover() {
        return "-fx-background-color:" + btnHoverBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-background-radius:9;" +
               "-fx-border-color:" + accent() + ";" +
               "-fx-border-radius:9;" +
               "-fx-padding:6 10;" +
               "-fx-font-size:12px;" +
               "-fx-font-family:" + FONT_STACK + ";";
    }
    private static String flatPressed() {
        return "-fx-background-color:" + btnPressedBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-background-radius:9;" +
               "-fx-border-color:" + accent() + ";" +
               "-fx-border-radius:9;" +
               "-fx-padding:6 10;" +
               "-fx-font-size:12px;" +
               "-fx-font-family:" + FONT_STACK + ";";
    }
    private static String accentBase() {
        return "-fx-background-color:" + accent() + ";" +
               "-fx-text-fill:white;" +
               "-fx-background-radius:9;" +
               "-fx-border-color:transparent;" +
               "-fx-padding:6 14;" +
               "-fx-font-size:12px;" +
               "-fx-font-weight:600;" +
               "-fx-font-family:" + FONT_STACK + ";";
    }
    static String textFieldBase() {
        return "-fx-background-color:" + inputBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-prompt-text-fill:" + textSub() + ";" +
               "-fx-background-radius:9;" +
               "-fx-border-color:" + borderSoft() + ";" +
               "-fx-border-radius:9;" +
               "-fx-padding:7 10;" +
               "-fx-font-size:12px;" +
               "-fx-font-family:" + FONT_STACK + ";";
    }
    public static String comboBase() {
        return "-fx-background-color:" + inputBg() + ";" +
               "-fx-text-fill:" + textMain() + ";" +
               "-fx-border-color:" + borderSoft() + ";" +
               "-fx-border-radius:9;" +
               "-fx-background-radius:9;" +
               "-fx-padding:2 4;" +
               "-fx-font-size:12px;" +
               "-fx-font-family:" + FONT_STACK + ";";
    }

    // ── Button helpers ────────────────────────────────────────────────────────

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
                    "-fx-background-color:derive(" + accent() + ",-12%);" +
                    "-fx-text-fill:white;-fx-background-radius:9;-fx-border-color:transparent;" +
                    "-fx-padding:6 14;-fx-font-size:12px;-fx-font-weight:600;" +
                    "-fx-font-family:" + FONT_STACK + ";"); });
            b.setOnMouseExited(e -> b.setStyle(accentBase()));
            b.setOnMousePressed(e -> { if (!b.isDisabled()) b.setStyle(
                    "-fx-background-color:derive(" + accent() + ",-22%);" +
                    "-fx-text-fill:white;-fx-background-radius:9;-fx-border-color:transparent;" +
                    "-fx-padding:6 14;-fx-font-size:12px;-fx-font-weight:600;" +
                    "-fx-font-family:" + FONT_STACK + ";"); });
            b.setOnMouseReleased(e -> { if (!b.isDisabled()) b.setStyle(
                    "-fx-background-color:derive(" + accent() + ",-12%);" +
                    "-fx-text-fill:white;-fx-background-radius:9;-fx-border-color:transparent;" +
                    "-fx-padding:6 14;-fx-font-size:12px;-fx-font-weight:600;" +
                    "-fx-font-family:" + FONT_STACK + ";"); });
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
                    "-fx-border-radius:9;" +
                    "-fx-background-radius:9;");
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
                    "-fx-background-radius:9;" +
                    "-fx-border-color:transparent;" +
                    "-fx-border-radius:9;" +
                    "-fx-padding:6 10;" +
                    "-fx-font-size:12px;" +
                    "-fx-font-family:" + FONT_STACK + ";");
        } else {
            t.setStyle("-fx-background-color:" + btnBg() + ";" +
                    "-fx-text-fill:" + textMain() + ";" +
                    "-fx-background-radius:9;" +
                    "-fx-border-color:" + borderSoft() + ";" +
                    "-fx-border-radius:9;" +
                    "-fx-padding:6 10;" +
                    "-fx-font-size:12px;" +
                    "-fx-font-family:" + FONT_STACK + ";");
        }
    }

    // ── Liquid Glass card ─────────────────────────────────────────────────────

    /** Creates a left-panel glass card with title + content nodes. */
    public static VBox card(String title, Node... content) {
        Label t = new Label(title);
        Runnable applyTitle = () -> t.setStyle(
                "-fx-text-fill:" + textMain() + ";" +
                "-fx-font-size:13px;" +
                "-fx-font-weight:700;" +
                "-fx-font-family:" + FONT_STACK + ";");
        applyTitle.run();

        VBox box = new VBox(10);
        box.getChildren().add(t);
        box.getChildren().addAll(content);
        box.setPadding(new Insets(14, 14, 14, 14));

        Runnable applyCard = () -> box.setStyle(
                // Two-layer glass: specular (top glint) + body (frosted)
                "-fx-background-color:" + glassSpecular() + "," + glassBody() + ";" +
                "-fx-background-insets:0,0.5;" +
                "-fx-background-radius:18;" +
                // Inner rim — brighter than body = "wet glass" edge
                "-fx-border-color:" + glassBorder() + "," + borderSoft() + ";" +
                "-fx-border-insets:0,0.5;" +
                "-fx-border-radius:18,17.5;" +
                "-fx-border-width:0.5;" +
                "-fx-effect:" + glassShadow() + ";");
        applyCard.run();
        addThemeListener(applyTitle);
        addThemeListener(applyCard);
        return box;
    }

    // ── ComboBox popup ────────────────────────────────────────────────────────

    /**
     * Styles the floating popup window of a ComboBox as a solid Liquid Glass panel.
     *
     * ComboBox 팝업은 별도 PopupWindow(= 독립 Scene)로 열리므로
     * 메인 Scene의 glass.css가 적용되지 않습니다.
     * ComboBoxListViewSkin.getListView()로 직접 접근하여
     *   1) popup Scene에 glass.css 추가
     *   2) ListView 배경을 불투명 solid 로 설정
     *   3) 팝업 컨테이너(ListView 부모)에 둥근 테두리 + 그림자 적용
     */
    public static void installComboPopupStyle(ComboBox<?> cb) {
        cb.showingProperty().addListener((o, ov, showing) -> {
            if (!showing) return;
            Platform.runLater(() -> {
                try {
                    applyPopupStyle(cb);
                } catch (Exception ignored) {}
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static void applyPopupStyle(ComboBox<?> cb) {
        // ── ComboBoxListViewSkin 경로 (JavaFX 17+, 가장 신뢰성 높음) ───────
        if (cb.getSkin() instanceof javafx.scene.control.skin.ComboBoxListViewSkin<?> skin
                && skin.getPopupContent() instanceof javafx.scene.control.ListView<?> lv) {
            // lv = popup 내부 ListView (getPopupContent() 는 public API)

            // 1) popup Scene 에 glass.css 추가 (ComboBox 셀 hover/selected 스타일)
            javafx.scene.Scene popupScene = lv.getScene();
            if (popupScene != null) {
                java.net.URL cssUrl = Styles.class.getResource("/glass.css");
                if (cssUrl != null) {
                    String urlStr = cssUrl.toExternalForm();
                    if (!popupScene.getStylesheets().contains(urlStr))
                        popupScene.getStylesheets().add(urlStr);
                }
            }

            // 2) ListView: solid 불투명 배경
            Runnable applyLv = () -> lv.setStyle(
                    "-fx-background-color:" + popupBg() + ";" +
                    "-fx-border-color:transparent;" +
                    "-fx-padding:4;");
            applyLv.run();
            if (registerOnce(lv)) addThemeListener(applyLv);

            // 3) 팝업 루트(= ListView 의 부모 컨테이너): 둥근 테두리 + 그림자
            Node popupRoot = lv.getParent();
            if (popupRoot != null) {
                Runnable applyRoot = () -> popupRoot.setStyle(
                        "-fx-background-color:" + popupBg() + ";" +
                        "-fx-background-radius:14;" +
                        "-fx-border-color:" + borderSoft() + ";" +
                        "-fx-border-radius:14;" +
                        "-fx-border-width:0.5;" +
                        "-fx-effect:" + popupShadow() + ";");
                applyRoot.run();
                if (registerOnce(popupRoot)) addThemeListener(applyRoot);
            }
            return; // 성공
        }

        // ── Fallback: lookup 기반 (구버전 JavaFX 호환) ───────────────────────
        Node popup = cb.lookup(".combo-box-popup");
        if (popup != null) {
            Runnable r = () -> popup.setStyle(
                    "-fx-background-color:" + popupBg() + ";" +
                    "-fx-background-radius:14;" +
                    "-fx-border-color:" + borderSoft() + ";" +
                    "-fx-border-radius:14;" +
                    "-fx-border-width:0.5;" +
                    "-fx-effect:" + popupShadow() + ";" +
                    "-fx-padding:4;");
            r.run();
            if (registerOnce(popup)) addThemeListener(r);
        }
        Node lv = cb.lookup(".combo-box-popup .list-view");
        if (lv != null) {
            Runnable r = () -> lv.setStyle(
                    "-fx-background-color:" + popupBg() + ";" +
                    "-fx-border-color:transparent;");
            r.run();
            if (registerOnce(lv)) addThemeListener(r);
        }
    }

    // ── Alert / Dialog ────────────────────────────────────────────────────────

    /**
     * Core glass styling for any DialogPane.
     * Called by styleAlert() and styleDialog() — do not call directly unless needed.
     */
    public static void styleDialogPane(DialogPane dp) {
        Runnable apply = () -> {
            // Glass background
            dp.setStyle(
                    "-fx-background-color:" + glassSpecular() + "," + glassBody() + ";" +
                    "-fx-background-insets:0,0.5;" +
                    "-fx-background-radius:20;" +
                    "-fx-border-color:" + glassBorder() + "," + borderSoft() + ";" +
                    "-fx-border-insets:0,0.5;" +
                    "-fx-border-radius:20,19.5;" +
                    "-fx-border-width:0.5;" +
                    "-fx-effect:" + (darkMode
                            ? "dropshadow(gaussian,rgba(0,0,0,0.92),44,0.22,0,14)"
                            : "dropshadow(gaussian,rgba(0,0,0,0.18),36,0.10,0,10)") + ";" +
                    "-fx-font-family:" + FONT_STACK + ";");
            // Header area
            Platform.runLater(() -> {
                Node header = dp.lookup(".header-panel");
                if (header != null) header.setStyle(
                        "-fx-background-color:transparent;" +
                        "-fx-border-color:" + borderSoft() + ";" +
                        "-fx-border-width:0 0 0.5 0;");
                Node content = dp.lookup(".content");
                if (content != null) content.setStyle(
                        "-fx-text-fill:" + textMain() + ";" +
                        "-fx-font-family:" + FONT_STACK + ";");
                Node bb = dp.lookup(".button-bar");
                if (bb != null) bb.setStyle("-fx-background-color:transparent;");
                dp.lookupAll(".button").forEach(btn -> {
                    if (btn instanceof Button b) styleFlatButton(b);
                });
                dp.lookupAll(".label").forEach(lbl -> {
                    if (lbl instanceof Label l)
                        l.setStyle("-fx-text-fill:" + textMain() + ";-fx-font-family:" + FONT_STACK + ";");
                });
                dp.lookupAll(".text-field").forEach(tf -> {
                    if (tf instanceof TextField t) styleTextField(t);
                });
                dp.lookupAll(".combo-box").forEach(cb -> {
                    if (cb instanceof ComboBox<?> c) {
                        Runnable r = () -> c.setStyle(comboBase());
                        r.run();
                        if (registerOnce(c)) addThemeListener(r);
                        installComboPopupStyle(c);
                    }
                });
                dp.lookupAll(".spinner").forEach(sp -> {
                    if (sp instanceof Spinner<?> s) styleSpinner(s);
                });
                // Choice dialog list
                Node lv = dp.lookup(".list-view");
                if (lv instanceof ListView<?> listView) styleListView(listView);
                // Tab pane
                Node tp = dp.lookup(".tab-pane");
                if (tp instanceof TabPane tabPane) styleTabPane(tabPane);
            });
        };
        dp.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) Platform.runLater(apply);
        });
        apply.run();
        if (registerOnce(dp)) addThemeListener(apply);
    }

    /**
     * Glass styling for an Alert dialog.
     */
    public static void styleAlert(Alert alert) {
        styleDialogPane(alert.getDialogPane());
    }

    /**
     * Glass styling for any generic Dialog (ChoiceDialog, TextInputDialog, etc.).
     */
    public static void styleDialog(Dialog<?> dialog) {
        styleDialogPane(dialog.getDialogPane());
    }

    // ── ListView ─────────────────────────────────────────────────────────────

    /** Applies glass background + transparent border to a ListView. */
    public static void styleListView(ListView<?> lv) {
        Runnable apply = () -> lv.setStyle(
                "-fx-background-color:transparent;" +
                "-fx-border-color:transparent;");
        apply.run();
        if (registerOnce(lv)) addThemeListener(apply);
    }

    // ── TabPane ──────────────────────────────────────────────────────────────

    /** Styles a TabPane header to match the glass design system. */
    public static void styleTabPane(TabPane tp) {
        Runnable apply = () -> {
            tp.setStyle(
                    "-fx-background-color:transparent;" +
                    "-fx-border-color:transparent;");
            // Tab header area
            Node header = tp.lookup(".tab-header-area");
            if (header != null) header.setStyle(
                    "-fx-background-color:" + bgTabBar() + ";" +
                    "-fx-background-radius:10 10 0 0;" +
                    "-fx-border-color:transparent;");
            // Individual tabs
            tp.lookupAll(".tab").forEach(tabNode -> tabNode.setStyle(
                    "-fx-background-color:transparent;" +
                    "-fx-border-color:transparent;"));
            tp.lookupAll(".tab-label").forEach(lbl -> {
                if (lbl instanceof Label l)
                    l.setStyle("-fx-text-fill:" + textMain() + ";-fx-font-family:" + FONT_STACK + ";");
            });
            tp.lookupAll(".tab:selected").forEach(tabNode -> tabNode.setStyle(
                    "-fx-background-color:" + accent() + ";" +
                    "-fx-border-color:transparent;" +
                    "-fx-background-radius:8;"));
        };
        apply.run();
        if (registerOnce(tp)) addThemeListener(apply);
    }
}
