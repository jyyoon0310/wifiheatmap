package app.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Function;

/**
 * 모달 시트 — 모든 팝업(알림·확인·선택·설정 창)의 공통 틀.
 *
 * <p>토스 앱 같은 정돈된 모양을 목표로 한다: 뒤 화면을 어둡게 덮고, 가운데 흰 카드에
 * 큰 굵은 제목 · 회색 본문 · 꽉 찬 둥근 버튼(주 버튼 파랑, 보조 버튼 회색). OS 제목 막대와
 * 물음표 아이콘이 붙던 기본 Alert/ChoiceDialog 를 대신한다.</p>
 *
 * <ul>
 *   <li>Esc · 바깥 클릭 = 닫기(결과 null) — {@code dismissible} 이 false 면 바깥 클릭은 무시</li>
 *   <li>Enter = 주 버튼</li>
 * </ul>
 */
public final class Sheet {
    private Sheet() {}

    // ── 디자인 토큰 (토스 계열 회색·파랑) ───────────────────────────────────
    public static String cardBg()     { return Styles.isDark() ? "#202027" : "#FFFFFF"; }
    public static String title()      { return Styles.isDark() ? "#FFFFFF" : "#191F28"; }
    public static String body()       { return Styles.isDark() ? "#B0B8C1" : "#4E5968"; }
    public static String caption()    { return Styles.isDark() ? "#8B95A1" : "#8B95A1"; }
    public static String fill()       { return Styles.isDark() ? "#2C2C35" : "#F2F4F6"; }   // 회색 면(보조 버튼·입력칸·선택지)
    public static String fillHover()  { return Styles.isDark() ? "#3A3A45" : "#E5E8EB"; }
    public static String line()       { return Styles.isDark() ? "#33333D" : "#E5E8EB"; }
    public static final String BLUE        = "#3182F6";
    public static final String BLUE_HOVER  = "#1B64DA";
    public static final String BLUE_SOFT   = "rgba(49,130,246,0.10)";
    private static String dim()       { return Styles.isDark() ? "rgba(0,0,0,0.62)" : "rgba(0,0,0,0.42)"; }

    /** 버튼 종류. */
    public enum Kind { PRIMARY, SECONDARY }

    /** 시트 하단 버튼. id 가 결과로 돌아온다. */
    public record Action(String id, String label, Kind kind) {
        public static Action primary(String id, String label)   { return new Action(id, label, Kind.PRIMARY); }
        public static Action secondary(String id, String label) { return new Action(id, label, Kind.SECONDARY); }
    }

    // ── 자주 쓰는 형태 ─────────────────────────────────────────────────────

    /** 확인 버튼 하나짜리 안내. */
    public static void notice(Window owner, String titleText, String bodyText) {
        show(owner, titleText, bodyText, null, 380, true, Action.primary("ok", "확인"));
    }

    /** 예/아니오. 주 버튼을 누르면 true. */
    public static boolean confirm(Window owner, String titleText, String bodyText, String yes, String no) {
        return "yes".equals(show(owner, titleText, bodyText, null, 380, true,
                Action.secondary("no", no), Action.primary("yes", yes)));
    }

    /**
     * 목록에서 하나 고르기 — 줄을 누르면 고르고 바로 닫힌다 (토스의 선택 시트처럼).
     *
     * @param subLabel 줄 아래 회색 설명 (null 이면 없음)
     * @return 고른 항목, 닫으면 null
     */
    public static <T> T choose(Window owner, String titleText, String bodyText, List<T> items,
                               Function<T, String> label, Function<T, String> subLabel, T current) {
        Object[] picked = {null};
        VBox list = new VBox(6);
        Runnable[] closer = {null};
        for (T item : items) {
            boolean selected = item.equals(current);
            Label name = text(label.apply(item), 16, selected ? BLUE : title(), selected ? "700" : "600");
            VBox texts = new VBox(2, name);
            String sub = (subLabel == null) ? null : subLabel.apply(item);
            if (sub != null && !sub.isBlank()) texts.getChildren().add(text(sub, 13, caption(), "400"));
            HBox.setHgrow(texts, Priority.ALWAYS);
            HBox row = new HBox(12, texts);
            row.setAlignment(Pos.CENTER_LEFT);
            if (selected) row.getChildren().add(checkMark());
            row.setPadding(new Insets(14, 16, 14, 16));
            String base = "-fx-background-radius:14;-fx-background-color:" + (selected ? BLUE_SOFT : "transparent") + ";";
            row.setStyle(base);
            row.setCursor(Cursor.HAND);
            row.setOnMouseEntered(e -> row.setStyle("-fx-background-radius:14;-fx-background-color:"
                    + (selected ? BLUE_SOFT : fill()) + ";"));
            row.setOnMouseExited(e -> row.setStyle(base));
            row.setOnMouseClicked(e -> { picked[0] = item; closer[0].run(); });
            list.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;-fx-padding:0;");
        scroll.setMaxHeight(420);
        scroll.setPrefViewportHeight(Math.min(420, items.size() * 64.0));

        VBox card = card(titleText, bodyText, scroll, 400);
        Stage stage = stageFor(owner, card, true);
        closer[0] = stage::close;
        stage.showAndWait();
        @SuppressWarnings("unchecked") T result = (T) picked[0];
        return result;
    }

    /**
     * 일반 시트. content 는 본문 아래에 들어가는 임의 노드(없으면 null).
     *
     * @param width        카드 폭
     * @param dismissible  바깥 클릭·Esc 로 닫을 수 있는지
     * @return 누른 버튼 id, 닫으면 null
     */
    public static String show(Window owner, String titleText, String bodyText, Node content,
                              double width, boolean dismissible, Action... actions) {
        String[] result = {null};
        Stage[] stageRef = {null};
        HBox buttons = buttonRow(actions, id -> { result[0] = id; stageRef[0].close(); });
        VBox card = card(titleText, bodyText, content, width);
        if (actions.length > 0) card.getChildren().add(buttons);
        Stage stage = stageFor(owner, card, dismissible);
        stageRef[0] = stage;
        stage.getScene().setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                for (Action a : actions) {
                    if (a.kind() == Kind.PRIMARY) { result[0] = a.id(); stage.close(); return; }
                }
            }
        });
        stage.showAndWait();
        return result[0];
    }

    // ── 직접 꾸미는 창(설정·추천 창)용 부품 ─────────────────────────────────

    /**
     * 직접 꾸민 카드를 담을 시트 창을 만든다 (아직 띄우지 않음).
     * 호출자가 {@link Stage#showAndWait()} 로 띄우고, 카드 안의 버튼이 {@link Stage#close()} 로 닫는다.
     */
    public static Stage createStage(Window owner, Region card, boolean dismissible) {
        return stageFor(owner, card, dismissible);
    }

    /** 흰 카드 바탕 (제목·본문 포함, 없으면 생략). */
    public static VBox card(String titleText, String bodyText, Node content, double width) {
        VBox card = new VBox(0);
        card.setPadding(new Insets(28, 24, 20, 24));
        card.setPrefWidth(width);
        card.setMaxWidth(width);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setStyle(cardStyle());
        if (titleText != null && !titleText.isBlank()) {
            Label t = text(titleText, 21, title(), "700");
            card.getChildren().add(t);
        }
        if (bodyText != null && !bodyText.isBlank()) {
            Label b = text(bodyText, 15, body(), "400");
            b.setLineSpacing(3);
            VBox.setMargin(b, new Insets(10, 0, 0, 0));
            card.getChildren().add(b);
        }
        if (content != null) {
            VBox.setMargin(content, new Insets(20, 0, 0, 0));
            card.getChildren().add(content);
        }
        return card;
    }

    public static String cardStyle() {
        return "-fx-background-color:" + cardBg() + ";" +
               "-fx-background-radius:24;" +
               "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.18),30,0.0,0,8);";
    }

    /** 하단 버튼 줄 — 버튼이 줄을 꽉 채운다. */
    public static HBox buttonRow(Action[] actions, java.util.function.Consumer<String> onClick) {
        HBox row = new HBox(8);
        VBox.setMargin(row, new Insets(24, 0, 0, 0));
        for (Action a : actions) {
            Button b = (a.kind() == Kind.PRIMARY) ? primaryButton(a.label()) : secondaryButton(a.label());
            b.setOnAction(e -> onClick.accept(a.id()));
            HBox.setHgrow(b, Priority.ALWAYS);
            b.setMaxWidth(Double.MAX_VALUE);
            row.getChildren().add(b);
        }
        return row;
    }

    public static Button primaryButton(String label) {
        return button(label, BLUE, BLUE_HOVER, "white");
    }

    public static Button secondaryButton(String label) {
        return button(label, fill(), fillHover(), body());
    }

    private static Button button(String label, String bg, String bgHover, String fg) {
        Button b = new Button(label);
        b.setPrefHeight(52);
        b.setMinHeight(52);
        b.setCursor(Cursor.HAND);
        b.setFocusTraversable(false);
        Function<String, String> style = color ->
                "-fx-background-color:" + color + ";" +
                "-fx-background-radius:14;" +
                "-fx-text-fill:" + fg + ";" +
                "-fx-font-size:16px;-fx-font-weight:700;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";" +
                "-fx-padding:0 18 0 18;";
        b.setStyle(style.apply(bg));
        b.setOnMouseEntered(e -> { if (!b.isDisabled()) b.setStyle(style.apply(bgHover)); });
        b.setOnMouseExited(e -> b.setStyle(style.apply(bg)));
        b.disabledProperty().addListener((o, was, dis) -> b.setOpacity(dis ? 0.4 : 1.0));
        return b;
    }

    /** 글자 — size [px], weight "400"/"600"/"700". */
    public static Label text(String s, double size, String color, String weight) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        l.setStyle("-fx-text-fill:" + color + ";" +
                   "-fx-font-size:" + size + "px;" +
                   "-fx-font-weight:" + weight + ";" +
                   "-fx-font-family:" + Styles.FONT_STACK + ";");
        return l;
    }

    private static Node checkMark() {
        SVGPath p = new SVGPath();
        p.setContent("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z");
        p.setFill(Color.web(BLUE));
        return new StackPane(p);
    }

    // ── 창 ────────────────────────────────────────────────────────────────

    private static Stage stageFor(Window owner, Region card, boolean dismissible) {
        Region backdrop = new Region();
        backdrop.setStyle("-fx-background-color:" + dim() + ";");
        StackPane root = new StackPane(backdrop, card);
        root.setStyle("-fx-background-color:transparent;");
        StackPane.setAlignment(card, Pos.CENTER);

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        } else {
            stage.initModality(Modality.APPLICATION_MODAL);
        }
        Scene scene = new Scene(root, Color.TRANSPARENT);
        java.net.URL css = Sheet.class.getResource("/glass.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setScene(scene);

        // 주인 창을 그대로 덮어 어둡게 한다 (없으면 주 화면 전체)
        Rectangle2D area = (owner != null)
                ? new Rectangle2D(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight())
                : Screen.getPrimary().getVisualBounds();
        stage.setX(area.getMinX());
        stage.setY(area.getMinY());
        stage.setWidth(area.getWidth());
        stage.setHeight(area.getHeight());

        if (dismissible) {
            backdrop.setOnMouseClicked(e -> stage.close());
        }
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && dismissible) { stage.close(); e.consume(); }
        });

        // 들어오는 동작: 배경 서서히 + 카드 살짝 커지며 나타남
        backdrop.setOpacity(0);
        card.setOpacity(0);
        card.setScaleX(0.96);
        card.setScaleY(0.96);
        stage.setOnShown(e -> {
            FadeTransition fb = new FadeTransition(Duration.millis(140), backdrop);
            fb.setToValue(1);
            FadeTransition fc = new FadeTransition(Duration.millis(160), card);
            fc.setToValue(1);
            ScaleTransition sc = new ScaleTransition(Duration.millis(160), card);
            sc.setToX(1);
            sc.setToY(1);
            new ParallelTransition(fb, fc, sc).play();
        });
        return stage;
    }

    /** 여러 줄 본문을 위한 회색 박스 (예: 파일 경로). */
    public static Node infoBox(String s) {
        Label l = text(s, 13, body(), "400");
        StackPane box = new StackPane(l);
        StackPane.setAlignment(l, Pos.CENTER_LEFT);
        box.setPadding(new Insets(12, 14, 12, 14));
        box.setStyle("-fx-background-color:" + fill() + ";-fx-background-radius:12;");
        return box;
    }

    /**
     * 누를 수 있는 선택지 줄 — 왼쪽 둥근 아이콘, 굵은 제목, 회색 설명, 오른쪽 화살표.
     * 마우스를 올리면 회색 면이 깔린다.
     */
    public static HBox optionRow(String iconPath, String titleText, String subText, Runnable onClick) {
        SVGPath icon = new SVGPath();
        icon.setContent(iconPath);
        icon.setFill(Color.web(BLUE));
        StackPane iconBox = new StackPane(new javafx.scene.Group(icon));
        icon.setScaleX(22 / 24.0);
        icon.setScaleY(22 / 24.0);
        iconBox.setMinSize(44, 44);
        iconBox.setMaxSize(44, 44);
        iconBox.setStyle("-fx-background-color:" + BLUE_SOFT + ";-fx-background-radius:14;");

        VBox texts = new VBox(3, text(titleText, 16, title(), "700"));
        if (subText != null && !subText.isBlank()) texts.getChildren().add(text(subText, 13, caption(), "400"));
        HBox.setHgrow(texts, Priority.ALWAYS);

        SVGPath chevron = new SVGPath();
        chevron.setContent("M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z");
        chevron.setFill(Color.web(caption()));

        HBox row = new HBox(14, iconBox, texts, new StackPane(chevron));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 12, 12, 12));
        String base = "-fx-background-radius:16;-fx-background-color:transparent;";
        row.setStyle(base);
        row.setCursor(Cursor.HAND);
        row.setOnMouseEntered(e -> row.setStyle("-fx-background-radius:16;-fx-background-color:" + fill() + ";"));
        row.setOnMouseExited(e -> row.setStyle(base));
        row.setOnMouseClicked(e -> onClick.run());
        return row;
    }

    /** 켜고 끄는 스위치 (토스 계열 둥근 트랙). */
    public static final class Switch extends StackPane {
        private final javafx.beans.property.BooleanProperty on = new javafx.beans.property.SimpleBooleanProperty();
        private final Region track = new Region();
        private final javafx.scene.shape.Circle knob = new javafx.scene.shape.Circle(11, Color.WHITE);

        public Switch(boolean initial) {
            track.setMinSize(46, 28);
            track.setMaxSize(46, 28);
            knob.setEffect(new javafx.scene.effect.DropShadow(3, 0, 1, Color.rgb(0, 0, 0, 0.25)));
            getChildren().addAll(track, knob);
            setMaxSize(46, 28);
            setCursor(Cursor.HAND);
            on.set(initial);
            refresh(false);
            on.addListener((o, a, b) -> refresh(true));
            setOnMouseClicked(e -> on.set(!on.get()));
        }

        public boolean isOn() { return on.get(); }

        private void refresh(boolean animate) {
            track.setStyle("-fx-background-radius:14;-fx-background-color:" + (on.get() ? BLUE : fillHover()) + ";");
            double x = on.get() ? 9 : -9;
            if (animate) {
                javafx.animation.TranslateTransition t = new javafx.animation.TranslateTransition(Duration.millis(120), knob);
                t.setToX(x);
                t.play();
            } else {
                knob.setTranslateX(x);
            }
        }
    }

    /** 설정 한 줄 — 왼쪽 제목·설명, 오른쪽 조작 부품(스위치 등). */
    public static HBox settingRow(String titleText, String subText, Node control) {
        VBox texts = new VBox(3, text(titleText, 16, title(), "600"));
        if (subText != null && !subText.isBlank()) texts.getChildren().add(text(subText, 13, caption(), "400"));
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(16, texts, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** 작은 체크 줄 (예: 다음부터 보지 않기). */
    public static javafx.scene.control.CheckBox checkRow(String label) {
        javafx.scene.control.CheckBox cb = new javafx.scene.control.CheckBox(label);
        cb.setStyle("-fx-text-fill:" + caption() + ";-fx-font-size:13px;-fx-font-family:" + Styles.FONT_STACK + ";");
        cb.setCursor(Cursor.HAND);
        return cb;
    }
}
