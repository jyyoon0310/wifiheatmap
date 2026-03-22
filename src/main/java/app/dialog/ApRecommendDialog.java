package app.dialog;

import app.engine.ApRecommender;
import app.model.Band;
import app.model.Wall;
import app.ui.Styles;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * AP 위치 추천 다이얼로그 — Flood Fill 방식.
 *
 * 흐름:
 *   1) 도면 위의 "우리 집" 내부를 클릭
 *      → 벽 경계로 Flood Fill → 집 내부만 파란색 오버레이
 *   2) AP 개수 선택 + 빠른/정밀 모드
 *   3) 추천 실행 → 결과 미리보기 → 적용
 */
public class ApRecommendDialog {

    private static final double PREVIEW_MAX_W = 620;
    private static final double PREVIEW_MAX_H = 440;
    private static final int FLOOD_CELL_SIZE = 4;  // 4px/cell
    private static final int FLOOD_WALL_THICK = 2;  // 벽 두께 (셀)

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
        canvas.setCursor(javafx.scene.Cursor.CROSSHAIR);
        StackPane previewPane = new StackPane(canvas);
        Runnable stylePreview = () -> previewPane.setStyle(
                "-fx-background-color:" + Styles.bgRow() + ";" +
                "-fx-background-radius:12;");
        stylePreview.run();
        Styles.addThemeListener(stylePreview);
        previewPane.setPadding(new Insets(6));

        // ── 상태 ─────────────────────────────────────────────────────────
        ApRecommender.Result[] resultRef = {null};
        ApRecommender.FloodResult[] floodRef = {null};

        // 오버레이 이미지 (채워진 영역 표시용)
        WritableImage[] overlayRef = {null};

        // ── 설정 패널 ────────────────────────────────────────────────────
        Label areaTitle = sectionLabel("① 집 내부를 클릭하세요");
        Label areaHint = subLabel("도면 위에서 Wi-Fi가 필요한 영역\n(거실, 침실 등) 안쪽을 클릭하면\n벽 경계를 따라 자동으로 영역이\n잡힙니다.");
        Label areaStatus = subLabel("클릭 대기 중...");

        Button resetAreaBtn = new Button("영역 초기화");
        Styles.styleFlatButton(resetAreaBtn);

        // AP 개수
        Label countTitle = sectionLabel("② AP 개수");
        HBox countRow = new HBox(6);
        countRow.setAlignment(Pos.CENTER_LEFT);
        ToggleGroup countGroup = new ToggleGroup();
        ToggleButton[] countBtns = new ToggleButton[5];
        for (int i = 0; i < 5; i++) {
            ToggleButton tb = new ToggleButton(String.valueOf(i + 1));
            tb.setToggleGroup(countGroup);
            tb.setUserData(i + 1);
            tb.setPrefWidth(38); tb.setPrefHeight(34);
            Styles.styleToggle(tb);
            countBtns[i] = tb;
        }
        countBtns[1].setSelected(true);
        countRow.getChildren().addAll(countBtns);

        // 정밀도
        Label modeTitle = sectionLabel("③ 추천 방식");
        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton fastBtn = new ToggleButton("⚡ 빠르게");
        fastBtn.setToggleGroup(modeGroup); fastBtn.setUserData("fast");
        Styles.styleToggle(fastBtn);
        ToggleButton preciseBtn = new ToggleButton("🔬 정밀");
        preciseBtn.setToggleGroup(modeGroup); preciseBtn.setUserData("precise");
        Styles.styleToggle(preciseBtn);
        fastBtn.setSelected(true);
        HBox modeRow = new HBox(6, fastBtn, preciseBtn);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        Label modeHint = subLabel("정밀 모드는 파동 시뮬레이션으로\n벽 반사/간섭까지 고려합니다.");

        // 실행
        Button runBtn = new Button("추천 실행");
        Styles.styleAccentButton(runBtn);
        runBtn.setMaxWidth(Double.MAX_VALUE); runBtn.setPrefHeight(38);
        runBtn.setDisable(true);

        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE); progress.setPrefHeight(5);
        Runnable styleProg = () -> progress.setStyle("-fx-accent:" + Styles.accent() + ";");
        styleProg.run(); Styles.addThemeListener(styleProg);

        Label statusLbl = subLabel("");
        Label resultTitle = sectionLabel("");
        Label resultDetail = subLabel("");

        // 패널 조립
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
        paramPanel.setPrefWidth(240); paramPanel.setMaxWidth(240);
        Runnable stylePanel = () -> paramPanel.setStyle(
                "-fx-background-color:" + Styles.bgPanel() + ";" +
                "-fx-background-radius:12;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:12;-fx-border-width:0.5;");
        stylePanel.run(); Styles.addThemeListener(stylePanel);

        // ── 렌더 ─────────────────────────────────────────────────────────
        Runnable redraw = () -> drawPreview(canvas, floorplanImage, pvW, pvH,
                overlayRef[0],
                resultRef[0] != null ? resultRef[0].positions() : List.of(),
                scale);
        redraw.run();

        // ── 클릭 → Flood Fill ────────────────────────────────────────────
        List<Wall> walls = new ArrayList<>(env.getWalls());

        canvas.setOnMouseClicked(e -> {
            // 캔버스 좌표 → 원본 좌표
            int seedX = (int) (e.getX() / scale);
            int seedY = (int) (e.getY() / scale);

            areaStatus.setText("영역 감지 중...");

            ApRecommender.FloodResult fr = ApRecommender.floodFill(
                    walls, canvasW, canvasH,
                    FLOOD_CELL_SIZE, seedX, seedY, FLOOD_WALL_THICK);
            floodRef[0] = fr;

            if (fr.filledCells() < 10) {
                areaStatus.setText("영역이 너무 작습니다.\n다른 곳을 클릭하세요.");
                overlayRef[0] = null;
                runBtn.setDisable(true);
                redraw.run();
                return;
            }

            // 오버레이 이미지 생성
            overlayRef[0] = buildOverlayImage(fr, pvW, pvH, FLOOD_CELL_SIZE, scale);

            // 면적 계산
            double cellM = FLOOD_CELL_SIZE * env.getScaleMPerPx();
            double areaM2 = fr.filledCells() * cellM * cellM;
            areaStatus.setText(String.format("✓ 영역 감지 완료 (약 %.0f m²)", areaM2));

            runBtn.setDisable(false);
            resultRef[0] = null;
            resultTitle.setText(""); resultDetail.setText("");
            redraw.run();
        });

        resetAreaBtn.setOnAction(e -> {
            floodRef[0] = null; overlayRef[0] = null; resultRef[0] = null;
            runBtn.setDisable(true);
            areaStatus.setText("클릭 대기 중...");
            resultTitle.setText(""); resultDetail.setText("");
            statusLbl.setText("");
            progress.setProgress(0);
            redraw.run();
        });

        // ── 실행 로직 ────────────────────────────────────────────────────
        Task<?>[] taskRef = {null};

        runBtn.setOnAction(e -> {
            if (taskRef[0] != null && taskRef[0].isRunning()) {
                taskRef[0].cancel(); runBtn.setText("추천 실행"); return;
            }

            ApRecommender.FloodResult fr = floodRef[0];
            if (fr == null) return;

            Toggle selCount = countGroup.getSelectedToggle();
            int apCount = selCount != null ? (int) selCount.getUserData() : 2;
            Toggle selMode = modeGroup.getSelectedToggle();
            boolean precise = selMode != null && "precise".equals(selMode.getUserData());

            ApRecommender.Params params = new ApRecommender.Params(
                    apCount, -65.0,
                    precise ? 20 : 30, precise ? 12 : 20,
                    precise, 300, Band.GHZ_5,
                    fr.mask(), fr.maskW(), fr.maskH()
            );

            runBtn.setText("중지");
            progress.setProgress(-1);
            resultTitle.setText(""); resultDetail.setText("");

            Task<ApRecommender.Result> task = new Task<>() {
                @Override protected ApRecommender.Result call() {
                    return ApRecommender.recommend(env, canvasW, canvasH, params,
                            msg -> javafx.application.Platform.runLater(() -> statusLbl.setText(msg)));
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
                runBtn.setText("추천 실행"); progress.setProgress(0);
                Throwable ex = task.getException();
                statusLbl.setText("오류: " + (ex != null ? ex.getMessage() : "알 수 없음"));
            });
            task.setOnCancelled(ev -> {
                runBtn.setText("추천 실행"); progress.setProgress(0);
                statusLbl.setText("취소됨");
            });

            taskRef[0] = task;
            Thread t = new Thread(task, "ap-recommender"); t.setDaemon(true); t.start();
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
            if (bt == applyBtn && resultRef[0] != null && onApply != null)
                onApply.accept(resultRef[0].positions());
            return null;
        });
        dlg.showAndWait();
    }

    // ── 오버레이 이미지 생성 (채워진 영역 = 파랑, 밖 = 어둡게) ──────────────

    private static WritableImage buildOverlayImage(ApRecommender.FloodResult fr,
                                                   int pvW, int pvH,
                                                   int cellSize, double scale) {
        WritableImage img = new WritableImage(pvW, pvH);
        PixelWriter pw = img.getPixelWriter();

        int mw = fr.maskW(), mh = fr.maskH();
        boolean[] mask = fr.mask();

        for (int py = 0; py < pvH; py++) {
            for (int px = 0; px < pvW; px++) {
                // 캔버스 → 원본 → 마스크 좌표
                int origX = (int) (px / scale);
                int origY = (int) (py / scale);
                int mx = origX / cellSize;
                int my = origY / cellSize;

                boolean inside = (mx >= 0 && mx < mw && my >= 0 && my < mh)
                        && mask[my * mw + mx];

                if (inside) {
                    pw.setColor(px, py, Color.rgb(52, 120, 246, 0.15));
                } else {
                    pw.setColor(px, py, Color.rgb(0, 0, 0, 0.40));
                }
            }
        }
        return img;
    }

    // ── 캔버스 렌더링 ────────────────────────────────────────────────────────

    private static void drawPreview(Canvas canvas, Image bgImage, int w, int h,
                                    WritableImage overlay, List<Point2D> positions,
                                    double scale) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.drawImage(bgImage, 0, 0, w, h);

        // 오버레이
        if (overlay != null) {
            g.drawImage(overlay, 0, 0, w, h);

            // 채워진 영역 테두리 (파란색 글로우)
            g.setStroke(Color.rgb(52, 120, 246, 0.6));
            g.setLineWidth(1.0);
        }

        // AP 마커
        for (int i = 0; i < positions.size(); i++) {
            Point2D p = positions.get(i);
            double cx = p.getX() * scale, cy = p.getY() * scale;

            g.setFill(Color.rgb(52, 120, 246, 0.08));
            g.fillOval(cx - 40, cy - 40, 80, 80);
            g.setFill(Color.rgb(52, 120, 246, 0.22));
            g.fillOval(cx - 18, cy - 18, 36, 36);
            g.setStroke(Color.rgb(52, 120, 246, 0.85));
            g.setLineWidth(2.5);
            g.strokeOval(cx - 13, cy - 13, 26, 26);
            g.setFill(Color.rgb(52, 120, 246));
            g.fillOval(cx - 6, cy - 6, 12, 12);
            g.setFill(Color.WHITE);
            g.setFont(javafx.scene.text.Font.font("SF Pro Text",
                    javafx.scene.text.FontWeight.BOLD, 10));
            g.fillText(String.valueOf(i + 1), cx - 3, cy + 4);
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
        r.run(); Styles.addThemeListener(r);
        return l;
    }

    private static Label subLabel(String text) {
        Label l = new Label(text); l.setWrapText(true);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run(); Styles.addThemeListener(r);
        return l;
    }

    private static Separator styledSeparator() {
        Separator sep = new Separator();
        Runnable r = () -> sep.setStyle("-fx-background-color:" + Styles.borderSoft() + ";");
        r.run(); Styles.addThemeListener(r);
        return sep;
    }
}
