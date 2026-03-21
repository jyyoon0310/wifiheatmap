package app.dialog;

import app.engine.WallDetector;
import app.model.WallMaterial;
import app.ui.Styles;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * 벽 자동 인식 다이얼로그.
 *
 * 인식된 선분을 클릭하면 글라스 팝오버가 뜨며
 * 재질을 고르고 "추가"를 누르면 즉시 추가 목록에 들어갑니다.
 * "완료" 버튼으로 최종 적용합니다.
 *
 * 색상 범례:
 *   하늘색 = 후보 (아직 미추가)
 *   주황   = 호버
 *   노란색 = 팝오버가 열린 선분
 *   초록   = 추가 확정됨
 */
public class WallDetectorDialog {

    private static final double PREVIEW_MAX_W = 700;
    private static final double PREVIEW_MAX_H = 500;
    private static final double HIT_RADIUS_PX = 9.0;

    public static void show(Window owner, BufferedImage floorplan,
                            BiConsumer<List<WallDetector.Segment>, WallMaterial> onApply) {
        if (floorplan == null) return;

        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("벽 자동 인식");
        if (owner != null) dlg.initOwner(owner);
        dlg.getDialogPane().setPrefSize(1000, 680);

        // ── 스케일 / 캔버스 ────────────────────────────────────────────────
        double scaleX = Math.min(1.0, PREVIEW_MAX_W / floorplan.getWidth());
        double scaleY = Math.min(1.0, PREVIEW_MAX_H / floorplan.getHeight());
        double scale  = Math.min(scaleX, scaleY);
        int canvasW   = (int) Math.round(floorplan.getWidth()  * scale);
        int canvasH   = (int) Math.round(floorplan.getHeight() * scale);

        Canvas canvas = new Canvas(canvasW, canvasH);
        Image grayBg  = toGrayFxImage(floorplan, canvasW, canvasH);

        StackPane previewPane = new StackPane(canvas);
        Runnable stylePreview = () -> previewPane.setStyle(
                "-fx-background-color:" + Styles.bgRow() + ";" +
                "-fx-background-radius:10;");
        stylePreview.run();
        Styles.addThemeListener(stylePreview);
        previewPane.setPadding(new Insets(6));

        // ── 상태 ──────────────────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        List<WallDetector.Segment>[] detectedRef = new List[]{List.of()};

        // 추가 확정된 (세그먼트 인덱스 → 선택된 재질) 맵
        Map<Integer, WallMaterial> addedMap = new LinkedHashMap<>();

        int[] hoveredIdx = {-1};
        int[] pendingIdx = {-1}; // 팝오버가 열린 선분 인덱스

        Label statusLbl = styledLabel("인식 중...");
        Label addedLbl  = styledLabel("");

        Runnable updateLabels = () -> {
            int total = detectedRef[0].size();
            int added = addedMap.size();
            statusLbl.setText("인식된 벽: " + total + "개");
            addedLbl.setText(added > 0 ? "추가 확정: " + added + "개" : "");
        };

        // ── 글라스 팝오버 ─────────────────────────────────────────────────
        Popup popup = new Popup();
        popup.setAutoHide(true);

        ComboBox<WallMaterial> matCombo = new ComboBox<>();
        matCombo.getItems().setAll(WallMaterial.values());
        matCombo.getSelectionModel().select(WallMaterial.CONCRETE_WALL);
        matCombo.setMaxWidth(Double.MAX_VALUE);
        matCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(WallMaterial m) { return m == null ? "" : m.labelKo(); }
            @Override public WallMaterial fromString(String s) { return null; }
        });
        Runnable applyCombo = () -> matCombo.setStyle(Styles.comboBase());
        applyCombo.run();
        Styles.addThemeListener(applyCombo);
        Styles.installComboPopupStyle(matCombo);

        Label popupTitle = new Label("벽 추가");
        Runnable applyTitle = () -> popupTitle.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:12px;-fx-font-weight:600;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        applyTitle.run();
        Styles.addThemeListener(applyTitle);

        Label popupHint = new Label("재질을 선택하고 추가하세요");
        Runnable applyHint = () -> popupHint.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        applyHint.run();
        Styles.addThemeListener(applyHint);

        Button popupAddBtn    = new Button("추가");
        Button popupCancelBtn = new Button("취소");
        Styles.styleAccentButton(popupAddBtn);
        Styles.styleFlatButton(popupCancelBtn);

        HBox popupBtnRow = new HBox(8, popupAddBtn, popupCancelBtn);
        popupBtnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox popupBox = new VBox(10, popupTitle, popupHint, matCombo, popupBtnRow);
        popupBox.setPadding(new Insets(14));
        popupBox.setPrefWidth(210);
        Runnable applyPopupBox = () -> popupBox.setStyle(
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-background-radius:14;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:14;" +
                "-fx-border-width:0.5;" +
                "-fx-effect:" + (Styles.isDark()
                        ? "dropshadow(gaussian,rgba(0,0,0,0.85),28,0.20,0,10)"
                        : "dropshadow(gaussian,rgba(0,0,0,0.18),20,0.10,0,6)") + ";");
        applyPopupBox.run();
        Styles.addThemeListener(applyPopupBox);
        popup.getContent().add(popupBox);

        // ── 렌더링 클로저 ─────────────────────────────────────────────────
        Runnable redraw = () ->
                drawPreview(canvas, grayBg, detectedRef[0], scale,
                        addedMap.keySet(), hoveredIdx[0], pendingIdx[0]);

        // 팝오버 닫힘 시 pending 해제
        popup.setOnHidden(e -> {
            pendingIdx[0] = -1;
            redraw.run();
        });

        // 추가 버튼
        popupAddBtn.setOnAction(e -> {
            int idx = pendingIdx[0];
            if (idx >= 0 && idx < detectedRef[0].size()) {
                WallMaterial mat = matCombo.getValue();
                if (mat == null) mat = WallMaterial.CONCRETE_WALL;
                addedMap.put(idx, mat);
                updateLabels.run();
            }
            popup.hide(); // onHidden이 pendingIdx 초기화 + redraw
        });

        popupCancelBtn.setOnAction(e -> popup.hide());

        // ── 캔버스 마우스 이벤트 ──────────────────────────────────────────
        canvas.setOnMouseMoved(e -> {
            int prev = hoveredIdx[0];
            hoveredIdx[0] = hitTest(detectedRef[0], e.getX(), e.getY(), scale);
            if (hoveredIdx[0] != prev) redraw.run();
        });

        canvas.setOnMouseExited(e -> {
            hoveredIdx[0] = -1;
            redraw.run();
        });

        canvas.setOnMouseClicked(e -> {
            int idx = hitTest(detectedRef[0], e.getX(), e.getY(), scale);
            if (idx < 0) return;

            if (addedMap.containsKey(idx)) {
                // 이미 추가된 선분 → 클릭하면 제거 (토글)
                addedMap.remove(idx);
                updateLabels.run();
                redraw.run();
                return;
            }

            pendingIdx[0] = idx;
            redraw.run();

            // 팝오버 위치: 선분 중점 근처 (캔버스 → 화면 좌표)
            WallDetector.Segment seg = detectedRef[0].get(idx);
            double mx = ((seg.x1() + seg.x2()) * 0.5) * scale;
            double my = ((seg.y1() + seg.y2()) * 0.5) * scale;
            Point2D screen = canvas.localToScreen(mx + 14, my - 10);
            if (screen != null) popup.show(canvas, screen.getX(), screen.getY());
        });

        // ── 파라미터 슬라이더 ──────────────────────────────────────────────
        // 색상 필터
        Slider sBlackV    = slider( 30, 160,  80, "검정 임계값 (V)");
        Slider sBlackS    = slider( 10, 120,  60, "채도 임계값 (S)");
        // 굵기 필터
        Slider sThickPx   = slider(  2,  12,   3, "최소 굵기 (px)");
        // 엣지
        Slider sCanny1    = slider( 10,  80,  20, "Canny 하한");
        Slider sCanny2    = slider( 30, 150,  80, "Canny 상한");
        // 선분
        Slider sThreshold = slider( 10, 100,  35, "누적 임계값");
        Slider sMinLen    = slider( 20, 200,  40, "최소 길이 (px)");
        Slider sMaxGap    = slider(  2,  40,  10, "최대 갭 (px)");
        // 병합
        Slider sMergeDist = slider(  2,  40,  12, "병합 거리 (px)");

        // ── 범례 ─────────────────────────────────────────────────────────
        HBox legend = new HBox(10,
                legendItem("#5BC8F5", "후보"),
                legendItem("#FFB740", "호버"),
                legendItem("#FFE84D", "선택 중"),
                legendItem("#34D34A", "추가됨 (재클릭=취소)")
        );
        legend.setAlignment(Pos.CENTER_LEFT);

        // ── 오른쪽 파라미터 패널 ───────────────────────────────────────────
        VBox paramPanel = new VBox(8,
                sectionLabel("① 색상 필터 (유색 채움 제거)"),
                sliderRow("검정 밝기 한계",  sBlackV),
                sliderRow("채도 한계",       sBlackS),
                new Separator(),
                sectionLabel("② 굵기 필터 (얇은 선 제거)"),
                sliderRow("최소 굵기 (px)",  sThickPx),
                new Separator(),
                sectionLabel("③ 선분 인식"),
                sliderRow("Canny 하한",     sCanny1),
                sliderRow("Canny 상한",     sCanny2),
                sliderRow("누적 임계값",    sThreshold),
                sliderRow("최소 길이 (px)", sMinLen),
                sliderRow("최대 갭 (px)",   sMaxGap),
                new Separator(),
                sectionLabel("④ 병합"),
                sliderRow("병합 거리 (px)", sMergeDist),
                new Separator(),
                legend
        );
        paramPanel.setPadding(new Insets(12));
        paramPanel.setPrefWidth(230);
        paramPanel.setMaxWidth(230);
        Runnable applyPanel = () -> paramPanel.setStyle(
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-background-radius:10;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:10;" +
                "-fx-border-width:0.5;");
        applyPanel.run();
        Styles.addThemeListener(applyPanel);
        for (javafx.scene.Node n : paramPanel.getChildren()) {
            if (n instanceof Separator sep) {
                Runnable r = () -> sep.setStyle("-fx-background-color:" + Styles.borderSoft() + ";");
                r.run(); Styles.addThemeListener(r);
            }
        }

        // ── 레이아웃 ──────────────────────────────────────────────────────
        HBox bottomRow = new HBox(16, statusLbl, addedLbl);
        bottomRow.setAlignment(Pos.CENTER_LEFT);

        VBox leftBox = new VBox(8, previewPane, bottomRow);
        leftBox.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(previewPane, Priority.ALWAYS);

        HBox content = new HBox(12, leftBox, paramPanel);
        HBox.setHgrow(leftBox, Priority.ALWAYS);
        content.setPadding(new Insets(14));
        content.setStyle("-fx-background-color:transparent;");

        dlg.getDialogPane().setContent(content);

        // ── 대화상자 버튼 ─────────────────────────────────────────────────
        ButtonType doneBtn  = new ButtonType("완료", ButtonBar.ButtonData.OK_DONE);
        ButtonType closeBtn = new ButtonType("닫기", ButtonBar.ButtonData.CANCEL_CLOSE);
        dlg.getDialogPane().getButtonTypes().addAll(doneBtn, closeBtn);

        Styles.styleDialogPane(dlg.getDialogPane());

        // ── 감지 로직 ─────────────────────────────────────────────────────
        PauseTransition debounce = new PauseTransition(Duration.millis(350));
        Task<?>[] taskRef = new Task[]{null};

        Runnable runDetect = () -> {
            statusLbl.setText("인식 중...");
            popup.hide();
            addedMap.clear();
            updateLabels.run();

            WallDetector.Params p = new WallDetector.Params(
                    sCanny1.getValue(), sCanny2.getValue(),
                    (int) sThreshold.getValue(),
                    (int) sMinLen.getValue(),
                    (int) sMaxGap.getValue(),
                    (int) sMergeDist.getValue(),
                    5.0,
                    (int) sBlackV.getValue(),
                    (int) sBlackS.getValue(),
                    (int) sThickPx.getValue());

            if (taskRef[0] != null && taskRef[0].isRunning()) taskRef[0].cancel();

            Task<List<WallDetector.Segment>> task = new Task<>() {
                @Override protected List<WallDetector.Segment> call() {
                    return WallDetector.detect(floorplan, p);
                }
            };
            task.setOnSucceeded(ev -> {
                detectedRef[0] = task.getValue();
                updateLabels.run();
                redraw.run();
            });
            task.setOnFailed(ev -> {
                Throwable ex = task.getException();
                statusLbl.setText("인식 실패: " + (ex != null ? ex.getMessage() : "?"));
            });
            taskRef[0] = task;
            Thread t = new Thread(task, "wall-detector");
            t.setDaemon(true);
            t.start();
        };

        debounce.setOnFinished(e -> runDetect.run());
        Runnable schedule = () -> debounce.playFromStart();

        sBlackV.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sBlackS.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sThickPx.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sCanny1.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sCanny2.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sThreshold.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sMinLen.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sMaxGap.valueProperty().addListener((o, ov, nv) -> schedule.run());
        sMergeDist.valueProperty().addListener((o, ov, nv) -> schedule.run());

        runDetect.run();

        // ── 완료 처리 ─────────────────────────────────────────────────────
        dlg.setResultConverter(bt -> {
            popup.hide();
            if (bt == doneBtn && onApply != null && !addedMap.isEmpty()) {
                // 재질별로 그룹핑해서 onApply 호출 (재질이 다를 수 있으므로)
                Map<WallMaterial, List<WallDetector.Segment>> byMat = new LinkedHashMap<>();
                List<WallDetector.Segment> all = detectedRef[0];
                for (Map.Entry<Integer, WallMaterial> e : addedMap.entrySet()) {
                    int idx = e.getKey();
                    if (idx >= 0 && idx < all.size())
                        byMat.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(all.get(idx));
                }
                byMat.forEach((mat, segs) -> onApply.accept(segs, mat));
            }
            return null;
        });

        dlg.showAndWait();
    }

    // ── 렌더링 ────────────────────────────────────────────────────────────────

    private static void drawPreview(Canvas canvas, Image grayBg,
                                    List<WallDetector.Segment> segs, double scale,
                                    Set<Integer> addedSet, int hovered, int pending) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.drawImage(grayBg, 0, 0, canvas.getWidth(), canvas.getHeight());

        for (int i = 0; i < segs.size(); i++) {
            WallDetector.Segment s = segs.get(i);
            double x1 = s.x1() * scale, y1 = s.y1() * scale;
            double x2 = s.x2() * scale, y2 = s.y2() * scale;

            boolean isAdded   = addedSet.contains(i);
            boolean isHovered = (i == hovered) && !isAdded;
            boolean isPending = (i == pending);

            if (isPending) {
                // 팝오버 열린 선분: 노란색 글로우
                g.setStroke(Color.rgb(255, 232, 77, 0.45));
                g.setLineWidth(7);
                g.strokeLine(x1, y1, x2, y2);
                g.setStroke(Color.rgb(255, 240, 80, 1.0));
                g.setLineWidth(2.5);
                g.strokeLine(x1, y1, x2, y2);
            } else if (isAdded) {
                // 추가 확정: 초록
                g.setStroke(Color.rgb(52, 211, 74, 1.0));
                g.setLineWidth(2.0);
                g.strokeLine(x1, y1, x2, y2);
                g.setFill(Color.rgb(52, 211, 74, 0.9));
                g.fillOval(x1 - 3, y1 - 3, 6, 6);
                g.fillOval(x2 - 3, y2 - 3, 6, 6);
            } else if (isHovered) {
                // 호버: 주황 글로우
                g.setStroke(Color.rgb(255, 160, 30, 0.4));
                g.setLineWidth(7);
                g.strokeLine(x1, y1, x2, y2);
                g.setStroke(Color.rgb(255, 180, 50, 1.0));
                g.setLineWidth(2.0);
                g.strokeLine(x1, y1, x2, y2);
            } else {
                // 후보: 하늘색
                g.setStroke(Color.rgb(91, 200, 245, 0.75));
                g.setLineWidth(1.5);
                g.strokeLine(x1, y1, x2, y2);
            }
        }
    }

    // ── 히트 테스트 ───────────────────────────────────────────────────────────

    private static int hitTest(List<WallDetector.Segment> segs, double mx, double my, double scale) {
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

    private static double ptSegDist(double px, double py,
                                    double ax, double ay,
                                    double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) return Math.hypot(px - ax, py - ay);
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private static Image toGrayFxImage(BufferedImage src, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int rgb = scaled.getRGB(x, y);
            int lum = (int)(0.299*((rgb>>16)&0xff) + 0.587*((rgb>>8)&0xff) + 0.114*(rgb&0xff));
            gray.setRGB(x, y, (0xff<<24)|(lum<<16)|(lum<<8)|lum);
        }
        WritableImage fx = new WritableImage(w, h);
        SwingFXUtils.toFXImage(gray, fx);
        return fx;
    }

    private static HBox legendItem(String hex, String label) {
        javafx.scene.shape.Rectangle rect = new javafx.scene.shape.Rectangle(12, 12);
        rect.setFill(Color.web(hex));
        rect.setArcWidth(4); rect.setArcHeight(4);
        Label lbl = new Label(label);
        Runnable r = () -> lbl.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:10px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run(); Styles.addThemeListener(r);
        HBox box = new HBox(4, rect, lbl);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Label styledLabel(String text) {
        Label l = new Label(text);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run(); Styles.addThemeListener(r);
        return l;
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
        lbl.setPrefWidth(90); lbl.setMinWidth(90);

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
                "-fx-font-size:11px;-fx-font-weight:600;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run(); Styles.addThemeListener(r);
        return l;
    }
}
