package app.dialog;

import app.engine.WallDetector;
import app.model.WallMaterial;
import app.ui.Styles;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Consumer;

/**
 * 벽 자동 인식 다이얼로그.
 *
 * 인식된 벽을 캔버스에서 클릭으로 선택/해제할 수 있습니다.
 *   초록  = 선택됨 (적용 대상)
 *   회색  = 미선택
 *   주황  = 호버 중
 */
public class WallDetectorDialog {

    private static final double PREVIEW_MAX_W = 680;
    private static final double PREVIEW_MAX_H = 480;
    private static final double HIT_RADIUS_PX  = 8.0; // 클릭 판정 반경

    public static void show(Window owner, BufferedImage floorplan,
                            Consumer<List<WallDetector.Segment>> onApply) {
        if (floorplan == null) return;

        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("벽 자동 인식");
        if (owner != null) dlg.initOwner(owner);
        dlg.getDialogPane().setPrefSize(980, 660);

        // ── 스케일 / 캔버스 ────────────────────────────────────────────────
        double scaleX = Math.min(1.0, PREVIEW_MAX_W / floorplan.getWidth());
        double scaleY = Math.min(1.0, PREVIEW_MAX_H / floorplan.getHeight());
        double scale  = Math.min(scaleX, scaleY);
        int canvasW = (int) Math.round(floorplan.getWidth()  * scale);
        int canvasH = (int) Math.round(floorplan.getHeight() * scale);

        Canvas canvas = new Canvas(canvasW, canvasH);
        canvas.setCursor(Cursor.CROSSHAIR);

        Image grayPreview = toGrayFxImage(floorplan, canvasW, canvasH);

        StackPane previewPane = new StackPane(canvas);
        Runnable stylePreview = () -> previewPane.setStyle(
                "-fx-background-color:" + Styles.bgRow() + ";" +
                "-fx-background-radius:10;");
        stylePreview.run();
        Styles.addThemeListener(stylePreview);
        previewPane.setPadding(new Insets(6));

        // ── 선택 상태 ──────────────────────────────────────────────────────
        // detected[0] : 현재 인식된 전체 선분
        // selected    : 선택된 인덱스 집합 (기본: 전체 선택)
        @SuppressWarnings("unchecked")
        List<WallDetector.Segment>[] detectedRef = new List[]{List.of()};
        Set<Integer> selected  = new LinkedHashSet<>();
        int[]        hovered   = {-1};

        // ── 상태 / 선택 레이블 ─────────────────────────────────────────────
        Label statusLbl = styledLabel("인식 중...", false);
        Label selLbl    = styledLabel("", false);

        Runnable updateSelLbl = () -> {
            int total = detectedRef[0].size();
            int sel   = selected.size();
            selLbl.setText(sel + " / " + total + "개 선택");
        };

        // ── 선택 버튼들 ────────────────────────────────────────────────────
        Button selAllBtn  = smallBtn("모두 선택");
        Button selNoneBtn = smallBtn("모두 해제");
        Button selInvBtn  = smallBtn("반전");

        HBox selBtns = new HBox(6, selAllBtn, selNoneBtn, selInvBtn);
        selBtns.setAlignment(Pos.CENTER_LEFT);

        // ── 재질 선택 ──────────────────────────────────────────────────────
        ComboBox<WallMaterial> matCombo = new ComboBox<>();
        matCombo.getItems().setAll(WallMaterial.values());
        matCombo.getSelectionModel().select(WallMaterial.CONCRETE_WALL);
        matCombo.setMaxWidth(Double.MAX_VALUE);
        Runnable applyMat = () -> matCombo.setStyle(Styles.comboBase());
        applyMat.run();
        Styles.addThemeListener(applyMat);
        Styles.installComboPopupStyle(matCombo);
        matCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(WallMaterial m) { return m == null ? "" : m.labelKo(); }
            @Override public WallMaterial fromString(String s) { return null; }
        });

        // ── 파라미터 슬라이더 ──────────────────────────────────────────────
        Slider sCanny1    = slider(10, 150,  40, "Canny 하한");
        Slider sCanny2    = slider(40, 250, 120, "Canny 상한");
        Slider sMinLen    = slider(10, 150,  30, "최소 길이 (px)");
        Slider sMaxGap    = slider( 2,  50,   8, "최대 갭 (px)");
        Slider sThreshold = slider(10, 100,  30, "누적 임계값");
        Slider sMergeDist = slider( 2,  40,  10, "병합 거리 (px)");

        // ── 오른쪽 파라미터 패널 ───────────────────────────────────────────
        VBox paramPanel = new VBox(10,
                sectionLabel("엣지 감지"),
                sliderRow("Canny 하한",    sCanny1),
                sliderRow("Canny 상한",    sCanny2),
                sliderRow("누적 임계값",   sThreshold),
                new Separator(),
                sectionLabel("선분 인식"),
                sliderRow("최소 길이 (px)", sMinLen),
                sliderRow("최대 갭 (px)",   sMaxGap),
                new Separator(),
                sectionLabel("병합"),
                sliderRow("병합 거리 (px)", sMergeDist),
                new Separator(),
                sectionLabel("선택"),
                selBtns,
                new Separator(),
                sectionLabel("재질"),
                matCombo
        );
        paramPanel.setPadding(new Insets(12));
        paramPanel.setPrefWidth(220);
        paramPanel.setMaxWidth(220);
        Runnable applyPanel = () -> paramPanel.setStyle(
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-background-radius:10;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:10;" +
                "-fx-border-width:0.5;");
        applyPanel.run();
        Styles.addThemeListener(applyPanel);
        for (Node n : paramPanel.getChildren()) {
            if (n instanceof Separator sep) {
                Runnable r = () -> sep.setStyle("-fx-background-color:" + Styles.borderSoft() + ";");
                r.run(); Styles.addThemeListener(r);
            }
        }

        // ── 레이아웃 ──────────────────────────────────────────────────────
        HBox bottomRow = new HBox(12, statusLbl, selLbl);
        bottomRow.setAlignment(Pos.CENTER_LEFT);

        VBox leftBox = new VBox(8, previewPane, bottomRow);
        leftBox.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(previewPane, Priority.ALWAYS);

        HBox content = new HBox(12, leftBox, paramPanel);
        HBox.setHgrow(leftBox, Priority.ALWAYS);
        content.setPadding(new Insets(14));
        content.setStyle("-fx-background-color:transparent;");

        dlg.getDialogPane().setContent(content);

        // ── 버튼 ──────────────────────────────────────────────────────────
        ButtonType applyBtnType  = new ButtonType("선택 항목 추가", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtnType = new ButtonType("취소",            ButtonBar.ButtonData.CANCEL_CLOSE);
        dlg.getDialogPane().getButtonTypes().addAll(applyBtnType, cancelBtnType);

        Button applyNode = (Button) dlg.getDialogPane().lookupButton(applyBtnType);
        if (applyNode != null) applyNode.setDisable(true);

        Styles.styleDialogPane(dlg.getDialogPane());

        // ── 렌더링 클로저 ─────────────────────────────────────────────────
        Runnable redraw = () ->
                drawPreview(canvas, grayPreview, detectedRef[0], scale, selected, hovered[0]);

        // ── 선택 버튼 동작 ─────────────────────────────────────────────────
        selAllBtn.setOnAction(e -> {
            for (int i = 0; i < detectedRef[0].size(); i++) selected.add(i);
            updateSelLbl.run(); redraw.run();
        });
        selNoneBtn.setOnAction(e -> {
            selected.clear();
            updateSelLbl.run(); redraw.run();
        });
        selInvBtn.setOnAction(e -> {
            Set<Integer> inv = new LinkedHashSet<>();
            for (int i = 0; i < detectedRef[0].size(); i++)
                if (!selected.contains(i)) inv.add(i);
            selected.clear(); selected.addAll(inv);
            updateSelLbl.run(); redraw.run();
        });

        // ── 캔버스 마우스 이벤트 ──────────────────────────────────────────
        canvas.setOnMouseMoved(e -> {
            int prev = hovered[0];
            hovered[0] = hitTest(detectedRef[0], e.getX(), e.getY(), scale);
            if (hovered[0] != prev) redraw.run();
        });
        canvas.setOnMouseExited(e -> {
            hovered[0] = -1;
            redraw.run();
        });
        canvas.setOnMouseClicked(e -> {
            int idx = hitTest(detectedRef[0], e.getX(), e.getY(), scale);
            if (idx < 0) return;
            if (selected.contains(idx)) selected.remove(idx);
            else selected.add(idx);
            updateSelLbl.run(); redraw.run();
        });

        // ── 감지 로직 ─────────────────────────────────────────────────────
        PauseTransition debounce = new PauseTransition(Duration.millis(350));
        Task<?>[] taskRef = new Task[]{null};

        Runnable runDetect = () -> {
            statusLbl.setText("인식 중...");
            if (applyNode != null) applyNode.setDisable(true);

            WallDetector.Params p = new WallDetector.Params(
                    sCanny1.getValue(), sCanny2.getValue(),
                    (int) sThreshold.getValue(),
                    (int) sMinLen.getValue(),
                    (int) sMaxGap.getValue(),
                    (int) sMergeDist.getValue(),
                    5.0);

            if (taskRef[0] != null && taskRef[0].isRunning()) taskRef[0].cancel();

            Task<List<WallDetector.Segment>> task = new Task<>() {
                @Override protected List<WallDetector.Segment> call() {
                    return WallDetector.detect(floorplan, p);
                }
            };
            task.setOnSucceeded(ev -> {
                detectedRef[0] = task.getValue();
                // 재인식 시 전체 선택으로 초기화
                selected.clear();
                for (int i = 0; i < detectedRef[0].size(); i++) selected.add(i);

                statusLbl.setText("인식 완료: " + detectedRef[0].size() + "개");
                if (applyNode != null) applyNode.setDisable(detectedRef[0].isEmpty());
                updateSelLbl.run();
                redraw.run();
            });
            task.setOnFailed(ev -> {
                Throwable ex = task.getException();
                statusLbl.setText("인식 실패: " + (ex != null ? ex.getMessage() : "알 수 없는 오류"));
            });
            taskRef[0] = task;
            Thread t = new Thread(task, "wall-detector");
            t.setDaemon(true);
            t.start();
        };

        debounce.setOnFinished(e -> runDetect.run());
        Runnable schedule = () -> debounce.playFromStart();

        sCanny1.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sCanny2.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sMinLen.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sMaxGap.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sThreshold.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sMergeDist.valueProperty().addListener((o, ov, nv) -> schedule.run());

        runDetect.run(); // 최초 실행

        // ── 적용 ─────────────────────────────────────────────────────────
        dlg.setResultConverter(bt -> {
            if (bt == applyBtnType && onApply != null) {
                List<WallDetector.Segment> all = detectedRef[0];
                List<WallDetector.Segment> out = new ArrayList<>();
                for (int idx : selected) {
                    if (idx >= 0 && idx < all.size()) out.add(all.get(idx));
                }
                onApply.accept(out);
            }
            return null;
        });

        dlg.showAndWait();
    }

    // ── 렌더링 ────────────────────────────────────────────────────────────────

    private static void drawPreview(Canvas canvas, Image grayBg,
                                    List<WallDetector.Segment> segments,
                                    double scale, Set<Integer> selected, int hovered) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.drawImage(grayBg, 0, 0, canvas.getWidth(), canvas.getHeight());

        for (int i = 0; i < segments.size(); i++) {
            WallDetector.Segment seg = segments.get(i);
            double x1 = seg.x1() * scale, y1 = seg.y1() * scale;
            double x2 = seg.x2() * scale, y2 = seg.y2() * scale;

            if (i == hovered) {
                // 호버: 주황 글로우
                g.setStroke(Color.rgb(255, 160, 30, 0.5));
                g.setLineWidth(6);
                g.strokeLine(x1, y1, x2, y2);
                g.setStroke(Color.rgb(255, 180, 50, 1.0));
                g.setLineWidth(2.5);
            } else if (selected.contains(i)) {
                // 선택됨: 초록
                g.setStroke(Color.rgb(52, 211, 74, 1.0));
                g.setLineWidth(2.0);
            } else {
                // 미선택: 흐린 회색
                g.setStroke(Color.rgb(160, 160, 160, 0.45));
                g.setLineWidth(1.0);
            }
            g.strokeLine(x1, y1, x2, y2);

            // 선택된 선분의 끝점 점 표시
            if (selected.contains(i) && i != hovered) {
                g.setFill(Color.rgb(52, 211, 74, 0.9));
                g.fillOval(x1 - 2.5, y1 - 2.5, 5, 5);
                g.fillOval(x2 - 2.5, y2 - 2.5, 5, 5);
            }
        }
    }

    // ── 히트 테스트 ───────────────────────────────────────────────────────────

    /**
     * 마우스 좌표(캔버스 px)에서 가장 가까운 선분 인덱스를 반환.
     * HIT_RADIUS_PX 이내 선분이 없으면 -1.
     */
    private static int hitTest(List<WallDetector.Segment> segs,
                                double mx, double my, double scale) {
        int    best  = -1;
        double bestD = HIT_RADIUS_PX;
        for (int i = 0; i < segs.size(); i++) {
            WallDetector.Segment s = segs.get(i);
            double d = ptSegDist(mx, my,
                    s.x1() * scale, s.y1() * scale,
                    s.x2() * scale, s.y2() * scale);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /** 점 (px, py)에서 선분 (ax,ay)-(bx,by)까지의 최단 거리 */
    private static double ptSegDist(double px, double py,
                                    double ax, double ay,
                                    double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) return Math.hypot(px - ax, py - ay);
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    // ── 그레이 미리보기 ───────────────────────────────────────────────────────

    private static Image toGrayFxImage(BufferedImage src, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();

        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = scaled.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, gv = (rgb >> 8) & 0xff, b = rgb & 0xff;
                int lum = (int)(0.299 * r + 0.587 * gv + 0.114 * b);
                gray.setRGB(x, y, (0xff << 24) | (lum << 16) | (lum << 8) | lum);
            }
        }
        WritableImage fx = new WritableImage(w, h);
        SwingFXUtils.toFXImage(gray, fx);
        return fx;
    }

    // ── UI 헬퍼 ──────────────────────────────────────────────────────────────

    private static Label styledLabel(String text, boolean bold) {
        Label l = new Label(text);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                (bold ? "-fx-font-weight:600;" : "") +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run(); Styles.addThemeListener(r);
        return l;
    }

    private static Button smallBtn(String text) {
        Button b = new Button(text);
        Styles.styleFlatButton(b);
        b.setStyle(b.getStyle() +
                "-fx-font-size:11px;" +
                "-fx-padding:3 8;");
        return b;
    }

    private static Slider slider(double min, double max, double init, String tip) {
        Slider s = new Slider(min, max, init);
        s.setShowTickLabels(false);
        s.setMajorTickUnit((max - min) / 4.0);
        s.setTooltip(new Tooltip(tip + ": " + (int) init));
        s.valueProperty().addListener((o, ov, nv) ->
                s.getTooltip().setText(tip + ": " + nv.intValue()));
        Runnable r = () -> s.setStyle(
                "-fx-control-inner-background:" + Styles.bgRow() + ";" +
                "-fx-accent:" + Styles.accent() + ";");
        r.run(); Styles.addThemeListener(r);
        return s;
    }

    private static HBox sliderRow(String label, Slider slider) {
        Label lbl = new Label(label);
        Runnable r = () -> lbl.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run(); Styles.addThemeListener(r);
        lbl.setPrefWidth(90);
        lbl.setMinWidth(90);

        Label valLbl = new Label(String.valueOf((int) slider.getValue()));
        Runnable rv = () -> valLbl.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        rv.run(); Styles.addThemeListener(rv);
        valLbl.setMinWidth(28);
        valLbl.setAlignment(Pos.CENTER_RIGHT);
        slider.valueProperty().addListener((o, ov, nv) ->
                valLbl.setText(String.valueOf(nv.intValue())));

        HBox.setHgrow(slider, Priority.ALWAYS);
        HBox row = new HBox(6, lbl, slider, valLbl);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-weight:600;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run(); Styles.addThemeListener(r);
        return l;
    }
}
