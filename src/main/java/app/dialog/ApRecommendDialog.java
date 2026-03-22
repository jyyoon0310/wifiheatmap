package app.dialog;

import app.engine.ApRecommender;
import app.model.Band;
import app.ui.Styles;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/**
 * AP 위치 추천 다이얼로그.
 *
 * Phase 1 (Ray-cast) → Phase 2 (FDTD 검증) 2단계 표시.
 * 결과를 캔버스에 ★로 표시, "적용" 시 실제 AP로 추가.
 */
public class ApRecommendDialog {

    private static final double PREVIEW_MAX_W = 620;
    private static final double PREVIEW_MAX_H = 440;

    public static void show(Window owner, Image floorplanImage,
                            int canvasW, int canvasH,
                            app.model.WifiEnvironment env,
                            Consumer<List<Point2D>> onApply) {
        if (floorplanImage == null) return;

        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("AP 위치 추천");
        if (owner != null) dlg.initOwner(owner);
        dlg.getDialogPane().setPrefSize(920, 600);

        // ── 캔버스 ───────────────────────────────────────────────────────
        double scaleX = Math.min(1.0, PREVIEW_MAX_W / canvasW);
        double scaleY = Math.min(1.0, PREVIEW_MAX_H / canvasH);
        double scale  = Math.min(scaleX, scaleY);
        int pvW = (int) Math.round(canvasW * scale);
        int pvH = (int) Math.round(canvasH * scale);

        Canvas canvas = new Canvas(pvW, pvH);
        StackPane previewPane = new StackPane(canvas);
        Runnable stylePreview = () -> previewPane.setStyle(
                "-fx-background-color:" + Styles.bgRow() + ";" +
                "-fx-background-radius:10;");
        stylePreview.run();
        Styles.addThemeListener(stylePreview);
        previewPane.setPadding(new Insets(4));

        // ── 상태 변수 ────────────────────────────────────────────────────
        ApRecommender.Result[] resultRef = {null};

        // ── 파라미터 패널 ────────────────────────────────────────────────
        Label titleLbl = sectionLabel("추천 설정");

        Label apCountLbl = subLabel("AP 개수");
        Spinner<Integer> apCountSp = new Spinner<>(1, 5, 2);
        apCountSp.setEditable(true);
        Styles.styleSpinner(apCountSp);
        apCountSp.setMaxWidth(Double.MAX_VALUE);

        Label rssiLbl = subLabel("목표 RSSI (dBm)");
        Slider rssiSlider = new Slider(-80, -50, -65);
        rssiSlider.setShowTickLabels(true);
        rssiSlider.setMajorTickUnit(5);
        Label rssiValLbl = subLabel(String.valueOf((int) rssiSlider.getValue()));
        rssiSlider.valueProperty().addListener((o, ov, nv) ->
                rssiValLbl.setText(String.valueOf(nv.intValue())));
        Runnable styleSl = () -> rssiSlider.setStyle(
                "-fx-control-inner-background:" + Styles.bgRow() + ";" +
                "-fx-accent:" + Styles.accent() + ";");
        styleSl.run();
        Styles.addThemeListener(styleSl);

        Label gridLbl = subLabel("탐색 해상도 (px)");
        Spinner<Integer> gridSp = new Spinner<>(10, 60, 25, 5);
        gridSp.setEditable(true);
        Styles.styleSpinner(gridSp);
        gridSp.setMaxWidth(Double.MAX_VALUE);

        CheckBox fdtdCheck = new CheckBox("FDTD 정밀 검증");
        fdtdCheck.setSelected(true);
        Runnable styleCb = () -> fdtdCheck.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:12px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        styleCb.run();
        Styles.addThemeListener(styleCb);

        Label fdtdStepsLbl = subLabel("FDTD 스텝 수");
        Spinner<Integer> fdtdStepsSp = new Spinner<>(100, 1000, 300, 50);
        fdtdStepsSp.setEditable(true);
        Styles.styleSpinner(fdtdStepsSp);
        fdtdStepsSp.setMaxWidth(Double.MAX_VALUE);

        Label bandLbl = subLabel("FDTD 대역");
        ComboBox<Band> bandCombo = new ComboBox<>();
        bandCombo.getItems().setAll(Band.values());
        bandCombo.getSelectionModel().select(Band.GHZ_5);
        bandCombo.setMaxWidth(Double.MAX_VALUE);
        Runnable styleBC = () -> bandCombo.setStyle(Styles.comboBase());
        styleBC.run();
        Styles.addThemeListener(styleBC);
        Styles.installComboPopupStyle(bandCombo);

        // ── 실행/상태 ────────────────────────────────────────────────────
        Button runBtn = new Button("추천 실행");
        Styles.styleAccentButton(runBtn);
        runBtn.setMaxWidth(Double.MAX_VALUE);

        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(6);

        Label statusLbl = subLabel("설정 후 \"추천 실행\"을 누르세요.");

        // ── 결과 표시 ────────────────────────────────────────────────────
        Label resultLbl = subLabel("");

        // ── 파라미터 패널 조립 ───────────────────────────────────────────
        VBox paramPanel = new VBox(10,
                titleLbl,
                vbox(apCountLbl, apCountSp),
                vbox(rssiLbl, new HBox(6, rssiSlider, rssiValLbl)),
                vbox(gridLbl, gridSp),
                new Separator(),
                fdtdCheck,
                vbox(fdtdStepsLbl, fdtdStepsSp),
                vbox(bandLbl, bandCombo),
                new Separator(),
                runBtn,
                progress,
                statusLbl,
                resultLbl
        );
        paramPanel.setPadding(new Insets(14));
        paramPanel.setPrefWidth(240);
        paramPanel.setMaxWidth(240);
        Runnable stylePanel = () -> paramPanel.setStyle(
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-background-radius:10;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:10;" +
                "-fx-border-width:0.5;");
        stylePanel.run();
        Styles.addThemeListener(stylePanel);
        for (javafx.scene.Node n : paramPanel.getChildren()) {
            if (n instanceof Separator sep) {
                Runnable r = () -> sep.setStyle("-fx-background-color:" + Styles.borderSoft() + ";");
                r.run(); Styles.addThemeListener(r);
            }
        }

        // fdtdCheck ↔ fdtd controls 연동
        fdtdStepsSp.disableProperty().bind(fdtdCheck.selectedProperty().not());
        bandCombo.disableProperty().bind(fdtdCheck.selectedProperty().not());

        // ── 캔버스 초기 렌더 ─────────────────────────────────────────────
        Runnable redraw = () -> drawPreview(canvas, floorplanImage, pvW, pvH,
                resultRef[0] != null ? resultRef[0].positions() : List.of(), scale);
        redraw.run();

        // ── 실행 버튼 로직 ───────────────────────────────────────────────
        Task<?>[] taskRef = {null};

        runBtn.setOnAction(e -> {
            if (taskRef[0] != null && taskRef[0].isRunning()) {
                taskRef[0].cancel();
                runBtn.setText("추천 실행");
                return;
            }

            ApRecommender.Params params = new ApRecommender.Params(
                    apCountSp.getValue(),
                    rssiSlider.getValue(),
                    gridSp.getValue(),
                    Math.max(10, gridSp.getValue() - 5),
                    fdtdCheck.isSelected(),
                    fdtdStepsSp.getValue(),
                    bandCombo.getValue()
            );

            runBtn.setText("중지");
            progress.setProgress(-1); // indeterminate

            Task<ApRecommender.Result> task = new Task<>() {
                @Override
                protected ApRecommender.Result call() {
                    return ApRecommender.recommend(env, canvasW, canvasH, params,
                            msg -> javafx.application.Platform.runLater(
                                    () -> statusLbl.setText(msg)));
                }
            };

            task.setOnSucceeded(ev -> {
                resultRef[0] = task.getValue();
                runBtn.setText("추천 실행");
                progress.setProgress(1.0);
                statusLbl.setText(resultRef[0].summary());
                resultLbl.setText(formatResult(resultRef[0]));
                redraw.run();
            });

            task.setOnFailed(ev -> {
                runBtn.setText("추천 실행");
                progress.setProgress(0);
                Throwable ex = task.getException();
                statusLbl.setText("오류: " + (ex != null ? ex.getMessage() : "알 수 없음"));
            });

            task.setOnCancelled(ev -> {
                runBtn.setText("추천 실행");
                progress.setProgress(0);
                statusLbl.setText("취소됨");
            });

            taskRef[0] = task;
            Thread t = new Thread(task, "ap-recommender");
            t.setDaemon(true);
            t.start();
        });

        // ── 레이아웃 ─────────────────────────────────────────────────────
        VBox leftBox = new VBox(8, previewPane);
        leftBox.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(previewPane, Priority.ALWAYS);

        HBox content = new HBox(12, leftBox, paramPanel);
        HBox.setHgrow(leftBox, Priority.ALWAYS);
        content.setPadding(new Insets(14));
        content.setStyle("-fx-background-color:transparent;");

        dlg.getDialogPane().setContent(content);

        ButtonType applyBtn = new ButtonType("적용", ButtonBar.ButtonData.OK_DONE);
        ButtonType closeBtn = new ButtonType("닫기", ButtonBar.ButtonData.CANCEL_CLOSE);
        dlg.getDialogPane().getButtonTypes().addAll(applyBtn, closeBtn);

        Styles.styleDialogPane(dlg.getDialogPane());

        dlg.setResultConverter(bt -> {
            if (bt == applyBtn && resultRef[0] != null && onApply != null) {
                onApply.accept(resultRef[0].positions());
            }
            return null;
        });

        dlg.showAndWait();
    }

    // ── 캔버스 렌더링 ────────────────────────────────────────────────────────

    private static void drawPreview(Canvas canvas, Image bgImage,
                                    int w, int h, List<Point2D> positions, double scale) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.drawImage(bgImage, 0, 0, w, h);

        // 추천 AP 위치 표시
        for (int i = 0; i < positions.size(); i++) {
            Point2D p = positions.get(i);
            double cx = p.getX() * scale;
            double cy = p.getY() * scale;

            // 글로우
            g.setFill(Color.rgb(52, 120, 246, 0.25));
            g.fillOval(cx - 22, cy - 22, 44, 44);

            // 외곽 링
            g.setStroke(Color.rgb(52, 120, 246, 0.8));
            g.setLineWidth(2.5);
            g.strokeOval(cx - 16, cy - 16, 32, 32);

            // 중심 원
            g.setFill(Color.rgb(52, 120, 246, 1.0));
            g.fillOval(cx - 6, cy - 6, 12, 12);

            // 번호
            g.setFill(Color.WHITE);
            g.setFont(javafx.scene.text.Font.font("SF Pro Text", javafx.scene.text.FontWeight.BOLD, 11));
            String num = String.valueOf(i + 1);
            g.fillText(num, cx - 3, cy + 4);

            // 레이블
            g.setFill(Color.rgb(52, 120, 246));
            g.setFont(javafx.scene.text.Font.font("SF Pro Text", javafx.scene.text.FontWeight.BOLD, 11));
            g.fillText("AP " + (i + 1), cx + 18, cy + 4);
        }
    }

    // ── 결과 텍스트 ──────────────────────────────────────────────────────────

    private static String formatResult(ApRecommender.Result result) {
        if (result.positions().isEmpty()) return "추천 결과 없음";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < result.positions().size(); i++) {
            Point2D p = result.positions().get(i);
            sb.append(String.format("AP%d: (%.0f, %.0f)  ", i + 1, p.getX(), p.getY()));
        }
        return sb.toString().trim();
    }

    // ── 스타일 헬퍼 ──────────────────────────────────────────────────────────

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:13px;-fx-font-weight:700;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run();
        Styles.addThemeListener(r);
        return l;
    }

    private static Label subLabel(String text) {
        Label l = new Label(text);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run();
        Styles.addThemeListener(r);
        return l;
    }

    private static VBox vbox(javafx.scene.Node... children) {
        VBox b = new VBox(4, children);
        return b;
    }
}
