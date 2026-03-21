package app.dialog;

import app.engine.WallDetector;
import app.model.WallMaterial;
import app.ui.Styles;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import java.util.List;
import java.util.function.Consumer;

/**
 * 벽 자동 인식 다이얼로그.
 * 파라미터 슬라이더로 인식 결과를 실시간 미리보기하고 '적용'으로 벽을 추가합니다.
 */
public class WallDetectorDialog {

    private static final double PREVIEW_MAX_W = 680;
    private static final double PREVIEW_MAX_H = 480;

    /**
     * @param owner     부모 창
     * @param floorplan 평면도 BufferedImage
     * @param onApply   (segments, material) → void (적용 콜백)
     */
    public static void show(Window owner, BufferedImage floorplan,
                            Consumer<List<WallDetector.Segment>> onApply) {
        if (floorplan == null) return;

        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("벽 자동 인식");
        if (owner != null) dlg.initOwner(owner);
        dlg.getDialogPane().setPrefSize(960, 620);

        // ── 미리보기 캔버스 ────────────────────────────────────────────────
        double scaleX = Math.min(1.0, PREVIEW_MAX_W / floorplan.getWidth());
        double scaleY = Math.min(1.0, PREVIEW_MAX_H / floorplan.getHeight());
        double scale  = Math.min(scaleX, scaleY);
        int    canvasW = (int)Math.round(floorplan.getWidth()  * scale);
        int    canvasH = (int)Math.round(floorplan.getHeight() * scale);

        Canvas canvas = new Canvas(canvasW, canvasH);
        canvas.setStyle("-fx-background-color:transparent;");

        // 그레이스케일 미리보기 이미지 (1회 생성)
        Image grayPreview = toGrayFxImage(floorplan, canvasW, canvasH);

        StackPane previewPane = new StackPane(canvas);
        Runnable stylePreview = () -> previewPane.setStyle(
                "-fx-background-color:" + Styles.bgRow() + ";" +
                "-fx-background-radius:10;");
        stylePreview.run();
        Styles.addThemeListener(stylePreview);
        previewPane.setPadding(new Insets(6));

        // ── 상태 레이블 ────────────────────────────────────────────────────
        Label statusLbl = new Label("인식 중...");
        Runnable applyStatus = () -> statusLbl.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        applyStatus.run();
        Styles.addThemeListener(applyStatus);

        // 검출된 선분 상태
        @SuppressWarnings("unchecked")
        List<WallDetector.Segment>[] detectedRef = new List[]{List.of()};

        // ── 파라미터 슬라이더 ────────────────────────────────────────────────
        Slider sCanny1    = slider(10, 150,  40, "Canny 하한");
        Slider sCanny2    = slider(40, 250, 120, "Canny 상한");
        Slider sMinLen    = slider(10, 150,  30, "최소 길이 (px)");
        Slider sMaxGap    = slider( 2,  50,   8, "최대 갭 (px)");
        Slider sThreshold = slider(10, 100,  30, "누적 임계값");
        Slider sMergeDist = slider( 2,  40,  10, "병합 거리 (px)");

        // ── 재질 선택 ────────────────────────────────────────────────────────
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

        // ── 오른쪽 파라미터 패널 ─────────────────────────────────────────────
        VBox paramPanel = new VBox(10,
                sectionLabel("엣지 감지"),
                sliderRow("Canny 하한",   sCanny1),
                sliderRow("Canny 상한",   sCanny2),
                sliderRow("누적 임계값",  sThreshold),
                new Separator(),
                sectionLabel("선분 인식"),
                sliderRow("최소 길이 (px)", sMinLen),
                sliderRow("최대 갭 (px)",   sMaxGap),
                new Separator(),
                sectionLabel("병합"),
                sliderRow("병합 거리 (px)", sMergeDist),
                new Separator(),
                sectionLabel("재질"),
                matCombo
        );
        paramPanel.setPadding(new Insets(12));
        paramPanel.setPrefWidth(220);
        paramPanel.setMaxWidth(220);
        Runnable applyParam = () -> paramPanel.setStyle(
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-background-radius:10;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:10;" +
                "-fx-border-width:0.5;");
        applyParam.run();
        Styles.addThemeListener(applyParam);

        // style separators
        for (Node n : paramPanel.getChildren()) {
            if (n instanceof Separator sep) {
                Runnable r = () -> sep.setStyle("-fx-background-color:" + Styles.borderSoft() + ";");
                r.run();
                Styles.addThemeListener(r);
            }
        }

        // ── 레이아웃 ──────────────────────────────────────────────────────────
        VBox leftBox = new VBox(8, previewPane, statusLbl);
        leftBox.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(previewPane, Priority.ALWAYS);

        HBox content = new HBox(12, leftBox, paramPanel);
        HBox.setHgrow(leftBox, Priority.ALWAYS);
        content.setPadding(new Insets(14));
        content.setStyle("-fx-background-color:transparent;");

        dlg.getDialogPane().setContent(content);

        // ── 버튼 ──────────────────────────────────────────────────────────────
        ButtonType applyBtn  = new ButtonType("적용",  ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("취소",  ButtonBar.ButtonData.CANCEL_CLOSE);
        dlg.getDialogPane().getButtonTypes().addAll(applyBtn, cancelBtn);

        // 적용 버튼 비활성화 → 감지 완료 후 활성화
        Button applyNode = (Button) dlg.getDialogPane().lookupButton(applyBtn);
        if (applyNode != null) applyNode.setDisable(true);

        // ── 글라스 스타일 ─────────────────────────────────────────────────────
        Styles.styleDialogPane(dlg.getDialogPane());

        // ── 감지 로직 ─────────────────────────────────────────────────────────
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
                @Override
                protected List<WallDetector.Segment> call() {
                    return WallDetector.detect(floorplan, p);
                }
            };
            task.setOnSucceeded(ev -> {
                detectedRef[0] = task.getValue();
                statusLbl.setText("인식된 벽: " + detectedRef[0].size() + "개");
                if (applyNode != null) applyNode.setDisable(false);
                drawPreview(canvas, grayPreview, detectedRef[0], scale);
            });
            task.setOnFailed(ev -> {
                statusLbl.setText("인식 실패: " + task.getException().getMessage());
            });
            taskRef[0] = task;
            Thread t = new Thread(task, "wall-detector");
            t.setDaemon(true);
            t.start();
        };

        debounce.setOnFinished(e -> runDetect.run());

        Runnable scheduleDetect = () -> debounce.playFromStart();
        sCanny1.valueProperty().addListener((o, ov, nv) -> scheduleDetect.run());
        sCanny2.valueProperty().addListener((o, ov, nv) -> scheduleDetect.run());
        sCanny2.valueProperty().addListener((o, ov, nv) -> scheduleDetect.run());
        sMinLen.valueProperty().addListener((o, ov, nv) -> scheduleDetect.run());
        sMaxGap.valueProperty().addListener((o, ov, nv) -> scheduleDetect.run());
        sThreshold.valueProperty().addListener((o, ov, nv) -> scheduleDetect.run());
        sMergeDist.valueProperty().addListener((o, ov, nv) -> scheduleDetect.run());

        // 최초 감지 실행
        runDetect.run();

        // ── 결과 처리 ─────────────────────────────────────────────────────────
        dlg.setResultConverter(bt -> {
            if (bt == applyBtn && onApply != null) {
                onApply.accept(detectedRef[0]);
            }
            return null;
        });

        dlg.showAndWait();
    }

    // ── 미리보기 렌더링 ───────────────────────────────────────────────────────

    private static void drawPreview(Canvas canvas, Image grayBg,
                                    List<WallDetector.Segment> segments, double scale) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.drawImage(grayBg, 0, 0, canvas.getWidth(), canvas.getHeight());

        g.setStroke(Color.rgb(255, 80, 80, 0.85));
        g.setLineWidth(1.5);
        for (WallDetector.Segment seg : segments) {
            g.strokeLine(seg.x1() * scale, seg.y1() * scale,
                         seg.x2() * scale, seg.y2() * scale);
        }
    }

    private static Image toGrayFxImage(BufferedImage src, int w, int h) {
        // 그레이스케일로 축소한 JavaFX Image 생성
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();

        // 그레이스케일 변환
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = scaled.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int gv = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int lum = (int)(0.299 * r + 0.587 * gv + 0.114 * b);
                gray.setRGB(x, y, (0xff << 24) | (lum << 16) | (lum << 8) | lum);
            }
        }
        WritableImage fx = new WritableImage(w, h);
        SwingFXUtils.toFXImage(gray, fx);
        return fx;
    }

    // ── UI 헬퍼 ──────────────────────────────────────────────────────────────

    private static Slider slider(double min, double max, double init, String tooltip) {
        Slider s = new Slider(min, max, init);
        s.setShowTickLabels(false);
        s.setMajorTickUnit((max - min) / 4.0);
        s.setTooltip(new Tooltip(tooltip + ": " + (int)init));
        s.valueProperty().addListener((o, ov, nv) ->
                s.getTooltip().setText(tooltip + ": " + nv.intValue()));
        Runnable r = () -> s.setStyle(
                "-fx-control-inner-background:" + Styles.bgRow() + ";" +
                "-fx-accent:" + Styles.accent() + ";");
        r.run();
        Styles.addThemeListener(r);
        return s;
    }

    private static HBox sliderRow(String label, Slider slider) {
        Label lbl = new Label(label);
        Runnable r = () -> lbl.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run();
        Styles.addThemeListener(r);
        lbl.setPrefWidth(90);
        lbl.setMinWidth(90);

        Label valLbl = new Label(String.valueOf((int)slider.getValue()));
        Runnable rv = () -> valLbl.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        rv.run();
        Styles.addThemeListener(rv);
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
        r.run();
        Styles.addThemeListener(r);
        return l;
    }
}
