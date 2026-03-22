package app.dialog;

import app.engine.ApRecommender;
import app.model.Band;
import app.ui.Styles;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
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
 * AP 위치 추천 다이얼로그 — 직관적 UI.
 *
 * 흐름:
 *   1) 도면 위에서 드래그하여 "우리 집" 영역 지정 (파란 사각형)
 *   2) AP 개수 선택 + 빠른/정밀 모드
 *   3) 추천 실행 → 결과 미리보기 → 적용
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
        dlg.getDialogPane().setPrefSize(880, 580);

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
                "-fx-background-radius:12;");
        stylePreview.run();
        Styles.addThemeListener(stylePreview);
        previewPane.setPadding(new Insets(6));

        // ── 상태 ─────────────────────────────────────────────────────────
        ApRecommender.Result[] resultRef = {null};

        // 드래그 영역 (캔버스 좌표)
        double[] dragStart = {-1, -1};
        double[] selRect = {-1, -1, -1, -1}; // x, y, w, h (캔버스 좌표)

        // ── 설정 패널 ────────────────────────────────────────────────────
        // 0) 영역 선택 안내
        Label areaTitle = sectionLabel("① 커버 영역을 드래그하세요");
        Label areaHint = subLabel("도면 위에서 드래그하여 Wi-Fi가 필요한\n영역(우리 집)을 선택하세요.");
        Label areaStatus = subLabel("선택 안 됨");

        Button resetAreaBtn = new Button("영역 초기화");
        Styles.styleFlatButton(resetAreaBtn);

        // 1) AP 개수
        Label countTitle = sectionLabel("② AP 개수");
        HBox countRow = new HBox(6);
        countRow.setAlignment(Pos.CENTER_LEFT);
        ToggleGroup countGroup = new ToggleGroup();
        int[] countOptions = {1, 2, 3, 4, 5};
        ToggleButton[] countBtns = new ToggleButton[countOptions.length];
        for (int i = 0; i < countOptions.length; i++) {
            ToggleButton tb = new ToggleButton(String.valueOf(countOptions[i]));
            tb.setToggleGroup(countGroup);
            tb.setUserData(countOptions[i]);
            tb.setPrefWidth(38);
            tb.setPrefHeight(34);
            Styles.styleToggle(tb);
            countBtns[i] = tb;
        }
        countBtns[1].setSelected(true);
        countRow.getChildren().addAll(countBtns);

        // 2) 정밀도 토글
        Label modeTitle = sectionLabel("③ 추천 방식");
        ToggleGroup modeGroup = new ToggleGroup();

        ToggleButton fastBtn = new ToggleButton("⚡ 빠르게");
        fastBtn.setToggleGroup(modeGroup);
        fastBtn.setUserData("fast");
        Styles.styleToggle(fastBtn);

        ToggleButton preciseBtn = new ToggleButton("🔬 정밀");
        preciseBtn.setToggleGroup(modeGroup);
        preciseBtn.setUserData("precise");
        Styles.styleToggle(preciseBtn);

        fastBtn.setSelected(true);

        HBox modeRow = new HBox(6, fastBtn, preciseBtn);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        Label modeHint = subLabel("정밀 모드는 파동 시뮬레이션으로\n벽 반사/간섭까지 고려합니다.");

        // 3) 실행
        Button runBtn = new Button("추천 실행");
        Styles.styleAccentButton(runBtn);
        runBtn.setMaxWidth(Double.MAX_VALUE);
        runBtn.setPrefHeight(38);
        runBtn.setDisable(true); // 영역 선택 전 비활성화

        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(5);
        Runnable styleProg = () -> progress.setStyle("-fx-accent:" + Styles.accent() + ";");
        styleProg.run();
        Styles.addThemeListener(styleProg);

        Label statusLbl = subLabel("");
        Label resultTitle = sectionLabel("");
        Label resultDetail = subLabel("");

        // ── 패널 조립 ────────────────────────────────────────────────────
        VBox paramPanel = new VBox(10,
                areaTitle, areaHint, areaStatus, resetAreaBtn,
                styledSeparator(),
                countTitle, countRow,
                styledSeparator(),
                modeTitle, modeRow, modeHint,
                styledSeparator(),
                runBtn, progress, statusLbl,
                styledSeparator(),
                resultTitle, resultDetail
        );
        paramPanel.setPadding(new Insets(14));
        paramPanel.setPrefWidth(240);
        paramPanel.setMaxWidth(240);
        Runnable stylePanel = () -> paramPanel.setStyle(
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-background-radius:12;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:12;" +
                "-fx-border-width:0.5;");
        stylePanel.run();
        Styles.addThemeListener(stylePanel);

        // ── 캔버스 렌더 ──────────────────────────────────────────────────
        Runnable redraw = () -> drawPreview(canvas, floorplanImage, pvW, pvH,
                resultRef[0] != null ? resultRef[0].positions() : List.of(),
                scale, selRect);
        redraw.run();

        // ── 캔버스 드래그 (영역 선택) ────────────────────────────────────
        canvas.setOnMousePressed(e -> {
            dragStart[0] = e.getX();
            dragStart[1] = e.getY();
            selRect[0] = -1; selRect[1] = -1; selRect[2] = -1; selRect[3] = -1;
            resultRef[0] = null;
            resultTitle.setText("");
            resultDetail.setText("");
            redraw.run();
        });

        canvas.setOnMouseDragged(e -> {
            double x0 = Math.max(0, Math.min(dragStart[0], e.getX()));
            double y0 = Math.max(0, Math.min(dragStart[1], e.getY()));
            double x1 = Math.min(pvW, Math.max(dragStart[0], e.getX()));
            double y1 = Math.min(pvH, Math.max(dragStart[1], e.getY()));
            selRect[0] = x0; selRect[1] = y0;
            selRect[2] = x1 - x0; selRect[3] = y1 - y0;
            redraw.run();

            // 실제 크기(m) 표시
            double wm = (selRect[2] / scale) * env.getScaleMPerPx();
            double hm = (selRect[3] / scale) * env.getScaleMPerPx();
            areaStatus.setText(String.format("%.1f × %.1f m", wm, hm));
        });

        canvas.setOnMouseReleased(e -> {
            if (selRect[2] > 20 && selRect[3] > 20) {
                runBtn.setDisable(false);
                double wm = (selRect[2] / scale) * env.getScaleMPerPx();
                double hm = (selRect[3] / scale) * env.getScaleMPerPx();
                areaStatus.setText(String.format("✓ %.1f × %.1f m 선택됨", wm, hm));
            } else {
                selRect[0] = -1;
                runBtn.setDisable(true);
                areaStatus.setText("영역이 너무 작습니다. 다시 드래그하세요.");
                redraw.run();
            }
        });

        resetAreaBtn.setOnAction(e -> {
            selRect[0] = -1; selRect[1] = -1; selRect[2] = -1; selRect[3] = -1;
            resultRef[0] = null;
            resultTitle.setText("");
            resultDetail.setText("");
            runBtn.setDisable(true);
            areaStatus.setText("선택 안 됨");
            redraw.run();
        });

        // ── 실행 로직 ────────────────────────────────────────────────────
        Task<?>[] taskRef = {null};

        runBtn.setOnAction(e -> {
            if (taskRef[0] != null && taskRef[0].isRunning()) {
                taskRef[0].cancel();
                runBtn.setText("추천 실행");
                return;
            }

            Toggle selectedCount = countGroup.getSelectedToggle();
            int apCount = selectedCount != null ? (int) selectedCount.getUserData() : 2;

            Toggle selectedMode = modeGroup.getSelectedToggle();
            boolean precise = selectedMode != null && "precise".equals(selectedMode.getUserData());

            // 드래그 영역 → 원본 캔버스 좌표로 변환
            Rectangle2D bounds = null;
            if (selRect[0] >= 0 && selRect[2] > 0 && selRect[3] > 0) {
                bounds = new Rectangle2D(
                        selRect[0] / scale, selRect[1] / scale,
                        selRect[2] / scale, selRect[3] / scale);
            }

            ApRecommender.Params params = new ApRecommender.Params(
                    apCount, -65.0,
                    precise ? 20 : 30,
                    precise ? 12 : 20,
                    precise, 300, Band.GHZ_5,
                    bounds
            );

            runBtn.setText("중지");
            progress.setProgress(-1);
            resultTitle.setText("");
            resultDetail.setText("");

            Task<ApRecommender.Result> task = new Task<>() {
                @Override protected ApRecommender.Result call() {
                    return ApRecommender.recommend(env, canvasW, canvasH, params,
                            msg -> javafx.application.Platform.runLater(
                                    () -> statusLbl.setText(msg)));
                }
            };

            task.setOnSucceeded(ev -> {
                resultRef[0] = task.getValue();
                runBtn.setText("다시 실행");
                progress.setProgress(1.0);

                ApRecommender.Result r = resultRef[0];
                resultTitle.setText("📍 추천 결과");
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("커버율: %.0f%%", r.coveragePercent()));
                if (r.fdtdCoveragePercent() >= 0)
                    sb.append(String.format(" (FDTD: %.0f%%)", r.fdtdCoveragePercent()));
                sb.append("\n");
                for (int i = 0; i < r.positions().size(); i++) {
                    Point2D p = r.positions().get(i);
                    sb.append(String.format("AP %d: (%.0f, %.0f)\n", i + 1, p.getX(), p.getY()));
                }
                resultDetail.setText(sb.toString().trim());
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

        HBox content = new HBox(14, leftBox, paramPanel);
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
                                    int w, int h, List<Point2D> positions,
                                    double scale, double[] selRect) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.drawImage(bgImage, 0, 0, w, h);

        // 선택 영역 밖을 어둡게
        if (selRect[0] >= 0 && selRect[2] > 0 && selRect[3] > 0) {
            g.setFill(Color.rgb(0, 0, 0, 0.45));
            // 상단
            g.fillRect(0, 0, w, selRect[1]);
            // 하단
            g.fillRect(0, selRect[1] + selRect[3], w, h - selRect[1] - selRect[3]);
            // 좌측
            g.fillRect(0, selRect[1], selRect[0], selRect[3]);
            // 우측
            g.fillRect(selRect[0] + selRect[2], selRect[1],
                    w - selRect[0] - selRect[2], selRect[3]);

            // 선택 영역 테두리
            g.setStroke(Color.rgb(52, 120, 246, 0.9));
            g.setLineWidth(2.0);
            g.setLineDashes(6, 4);
            g.strokeRect(selRect[0], selRect[1], selRect[2], selRect[3]);
            g.setLineDashes(null);
        }

        // 추천 AP 위치
        for (int i = 0; i < positions.size(); i++) {
            Point2D p = positions.get(i);
            double cx = p.getX() * scale;
            double cy = p.getY() * scale;

            // 커버 범위 원
            g.setFill(Color.rgb(52, 120, 246, 0.08));
            g.fillOval(cx - 40, cy - 40, 80, 80);

            // 글로우
            g.setFill(Color.rgb(52, 120, 246, 0.22));
            g.fillOval(cx - 18, cy - 18, 36, 36);

            // 외곽 링
            g.setStroke(Color.rgb(52, 120, 246, 0.85));
            g.setLineWidth(2.5);
            g.strokeOval(cx - 13, cy - 13, 26, 26);

            // 중심 원
            g.setFill(Color.rgb(52, 120, 246));
            g.fillOval(cx - 6, cy - 6, 12, 12);

            // 번호
            g.setFill(Color.WHITE);
            g.setFont(javafx.scene.text.Font.font("SF Pro Text",
                    javafx.scene.text.FontWeight.BOLD, 10));
            g.fillText(String.valueOf(i + 1), cx - 3, cy + 4);

            // 레이블
            g.setFill(Color.rgb(52, 120, 246));
            g.setFont(javafx.scene.text.Font.font("SF Pro Text",
                    javafx.scene.text.FontWeight.BOLD, 11));
            g.fillText("AP " + (i + 1), cx + 16, cy + 4);
        }
    }

    // ── 스타일 헬퍼 ──────────────────────────────────────────────────────────

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:12px;-fx-font-weight:600;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run();
        Styles.addThemeListener(r);
        return l;
    }

    private static Label subLabel(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run();
        Styles.addThemeListener(r);
        return l;
    }

    private static Separator styledSeparator() {
        Separator sep = new Separator();
        Runnable r = () -> sep.setStyle("-fx-background-color:" + Styles.borderSoft() + ";");
        r.run();
        Styles.addThemeListener(r);
        return sep;
    }
}
