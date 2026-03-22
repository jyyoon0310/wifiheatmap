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
 * AP 위치 추천 다이얼로그 — 직관적 UI.
 *
 * 사용자에게 보이는 것:
 *   1) AP 개수 선택
 *   2) "빠른 추천" / "정밀 추천" 토글
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
        dlg.getDialogPane().setPrefSize(860, 560);

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

        // ── 설정 패널 ────────────────────────────────────────────────────
        // 1) AP 개수
        Label countTitle = sectionLabel("몇 개의 AP를 배치할까요?");
        HBox countRow = new HBox(8);
        countRow.setAlignment(Pos.CENTER_LEFT);
        ToggleGroup countGroup = new ToggleGroup();
        int[] countOptions = {1, 2, 3, 4, 5};
        ToggleButton[] countBtns = new ToggleButton[countOptions.length];
        for (int i = 0; i < countOptions.length; i++) {
            ToggleButton tb = new ToggleButton(String.valueOf(countOptions[i]));
            tb.setToggleGroup(countGroup);
            tb.setUserData(countOptions[i]);
            tb.setPrefWidth(40);
            tb.setPrefHeight(36);
            Styles.styleToggle(tb);
            countBtns[i] = tb;
        }
        countBtns[1].setSelected(true); // 기본 2개
        countRow.getChildren().addAll(countBtns);
        Label countUnit = subLabel("개");

        HBox countBox = new HBox(8, countRow, countUnit);
        countBox.setAlignment(Pos.CENTER_LEFT);

        // 2) 정밀도 토글
        Label modeTitle = sectionLabel("추천 방식");
        ToggleGroup modeGroup = new ToggleGroup();

        ToggleButton fastBtn = new ToggleButton("⚡ 빠른 추천");
        fastBtn.setToggleGroup(modeGroup);
        fastBtn.setUserData("fast");
        Styles.styleToggle(fastBtn);

        ToggleButton preciseBtn = new ToggleButton("🔬 정밀 추천");
        preciseBtn.setToggleGroup(modeGroup);
        preciseBtn.setUserData("precise");
        Styles.styleToggle(preciseBtn);

        fastBtn.setSelected(true);

        HBox modeRow = new HBox(6, fastBtn, preciseBtn);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        Label modeHint = subLabel("빠른 추천: ~3초  |  정밀 추천: ~30초 (파동 시뮬레이션 포함)");

        // 3) 실행 버튼
        Button runBtn = new Button("추천 실행");
        Styles.styleAccentButton(runBtn);
        runBtn.setMaxWidth(Double.MAX_VALUE);
        runBtn.setPrefHeight(40);

        // 4) 프로그레스
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(5);
        Runnable styleProg = () -> progress.setStyle(
                "-fx-accent:" + Styles.accent() + ";");
        styleProg.run();
        Styles.addThemeListener(styleProg);

        Label statusLbl = subLabel("AP 개수와 방식을 선택 후 실행하세요.");

        // 5) 결과 영역
        Label resultTitle = sectionLabel("");
        Label resultDetail = subLabel("");

        // ── 패널 조립 ────────────────────────────────────────────────────
        Separator sep1 = styledSeparator();
        Separator sep2 = styledSeparator();
        Separator sep3 = styledSeparator();

        VBox paramPanel = new VBox(12,
                countTitle, countBox,
                sep1,
                modeTitle, modeRow, modeHint,
                sep2,
                runBtn, progress, statusLbl,
                sep3,
                resultTitle, resultDetail
        );
        paramPanel.setPadding(new Insets(16));
        paramPanel.setPrefWidth(250);
        paramPanel.setMaxWidth(250);
        Runnable stylePanel = () -> paramPanel.setStyle(
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-background-radius:12;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:12;" +
                "-fx-border-width:0.5;");
        stylePanel.run();
        Styles.addThemeListener(stylePanel);

        // ── 캔버스 초기 렌더 ─────────────────────────────────────────────
        Runnable redraw = () -> drawPreview(canvas, floorplanImage, pvW, pvH,
                resultRef[0] != null ? resultRef[0].positions() : List.of(), scale);
        redraw.run();

        // ── 실행 로직 ────────────────────────────────────────────────────
        Task<?>[] taskRef = {null};

        runBtn.setOnAction(e -> {
            if (taskRef[0] != null && taskRef[0].isRunning()) {
                taskRef[0].cancel();
                runBtn.setText("추천 실행");
                return;
            }

            // 선택된 AP 개수
            Toggle selectedCount = countGroup.getSelectedToggle();
            int apCount = selectedCount != null ? (int) selectedCount.getUserData() : 2;

            // 정밀/빠름
            Toggle selectedMode = modeGroup.getSelectedToggle();
            boolean precise = selectedMode != null && "precise".equals(selectedMode.getUserData());

            // 내부적으로 최적 파라미터 자동 결정
            ApRecommender.Params params = new ApRecommender.Params(
                    apCount,
                    -65.0,              // 목표 RSSI: -65 dBm (실내 권장)
                    precise ? 20 : 30,  // 그리드 해상도: 정밀=20px, 빠름=30px
                    precise ? 12 : 20,  // 측정 해상도
                    precise,            // FDTD 사용 여부
                    300,                // FDTD 스텝 (정밀 시)
                    Band.GHZ_5          // 5GHz 기준 검증
            );

            runBtn.setText("중지");
            progress.setProgress(-1);
            resultTitle.setText("");
            resultDetail.setText("");

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
                runBtn.setText("다시 실행");
                progress.setProgress(1.0);

                ApRecommender.Result r = resultRef[0];
                statusLbl.setText("완료!");

                // 결과 표시
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
                                    int w, int h, List<Point2D> positions, double scale) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.drawImage(bgImage, 0, 0, w, h);

        for (int i = 0; i < positions.size(); i++) {
            Point2D p = positions.get(i);
            double cx = p.getX() * scale;
            double cy = p.getY() * scale;

            // 범위 원 (커버리지 느낌)
            g.setFill(Color.rgb(52, 120, 246, 0.10));
            g.fillOval(cx - 40, cy - 40, 80, 80);

            // 글로우
            g.setFill(Color.rgb(52, 120, 246, 0.25));
            g.fillOval(cx - 20, cy - 20, 40, 40);

            // 외곽 링
            g.setStroke(Color.rgb(52, 120, 246, 0.85));
            g.setLineWidth(2.5);
            g.strokeOval(cx - 14, cy - 14, 28, 28);

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
            g.fillText("AP " + (i + 1), cx + 18, cy + 4);
        }
    }

    // ── 스타일 헬퍼 ──────────────────────────────────────────────────────────

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:13px;-fx-font-weight:600;" +
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
