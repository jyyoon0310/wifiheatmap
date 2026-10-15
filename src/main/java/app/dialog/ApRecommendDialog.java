package app.dialog;

import app.engine.ApRecommender;
import app.model.Band;
import app.model.RssiLevel;
import app.model.Wall;
import app.ui.Sheet;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 공유기 자리 추천 창 — Flood Fill 로 방을 고르고, 대수·확인 방식을 정해 추천을 받는다.
 *
 * <p>흐름: ① 도면에서 방 누르기 → ② 공유기 대수 → ③ 빠르게/꼼꼼하게 → [자리 찾기] → 결과 확인 → [적용하기]</p>
 * <p>모양은 {@link Sheet} (어두운 배경 위 흰 카드, 굵은 제목, 꽉 찬 둥근 버튼).</p>
 */
public class ApRecommendDialog {

    private static final double PREVIEW_MAX_W = 600;
    private static final double PREVIEW_MAX_H = 470;
    private static final int FLOOD_CELL_SIZE = 4;  // 4px/cell
    private static final int FLOOD_WALL_THICK = 2;  // 벽 두께 (셀)
    private static final double PANEL_W = 300;

    public static void show(Window owner, Image floorplanImage,
                            int canvasW, int canvasH,
                            app.model.WifiEnvironment env,
                            app.model.AppState state,
                            app.model.ApPreset preset,
                            Consumer<List<Point2D>> onApply) {
        if (floorplanImage == null) return;

        // ── 도면 미리보기 ─────────────────────────────────────────────────
        double scale = Math.min(Math.min(1.0, PREVIEW_MAX_W / canvasW), PREVIEW_MAX_H / canvasH);
        int pvW = (int) Math.round(canvasW * scale);
        int pvH = (int) Math.round(canvasH * scale);
        Canvas canvas = new Canvas(pvW, pvH);
        canvas.setCursor(Cursor.CROSSHAIR);
        StackPane preview = new StackPane(canvas);
        preview.setPadding(new Insets(12));
        preview.setStyle("-fx-background-color:" + Sheet.fill() + ";-fx-background-radius:20;");
        preview.setMinSize(pvW + 24, pvH + 24);

        // ── 상태 ─────────────────────────────────────────────────────────
        ApRecommender.Result[] resultRef = {null};
        int maskW = (canvasW + FLOOD_CELL_SIZE - 1) / FLOOD_CELL_SIZE;
        int maskH = (canvasH + FLOOD_CELL_SIZE - 1) / FLOOD_CELL_SIZE;
        boolean[] combinedMask = new boolean[maskW * maskH];
        int[] totalFilledCells = {0};
        int[] clickCount = {0};
        WritableImage[] overlayRef = {null};
        int[] apCount = {2};
        boolean[] precise = {false};

        // ── ① 방 고르기 ──────────────────────────────────────────────────
        Label areaStatus = Sheet.text("도면에서 와이파이가 필요한 방을 눌러 주세요.", 14, Sheet.body(), "400");
        Button resetArea = textButton("다시 고르기");
        resetArea.setVisible(false);
        HBox areaRow = new HBox(8, areaStatus, spacer(), resetArea);
        areaRow.setAlignment(Pos.CENTER_LEFT);

        // ── ② 대수 ───────────────────────────────────────────────────────
        HBox countRow = new HBox(6);
        List<Button> countChips = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final int n = i;
            Button chip = new Button(n + "대");
            chip.setCursor(Cursor.HAND);
            chip.setPrefHeight(40);
            chip.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(chip, Priority.ALWAYS);
            chip.setOnAction(e -> { apCount[0] = n; refreshChips(countChips, n); });
            countChips.add(chip);
        }
        refreshChips(countChips, apCount[0]);
        countRow.getChildren().addAll(countChips);

        // ── ③ 확인 방식 ──────────────────────────────────────────────────
        VBox fastCard = modeCard("빠르게", "몇 초 · 경로 손실 모델로 계산");
        VBox preciseCard = modeCard("꼼꼼하게", "2~3분 · 벽 반사·간섭까지 파동으로 확인");
        Runnable refreshModes = () -> {
            styleModeCard(fastCard, !precise[0]);
            styleModeCard(preciseCard, precise[0]);
        };
        fastCard.setOnMouseClicked(e -> { precise[0] = false; refreshModes.run(); });
        preciseCard.setOnMouseClicked(e -> { precise[0] = true; refreshModes.run(); });
        refreshModes.run();
        HBox modeRow = new HBox(8, fastCard, preciseCard);
        HBox.setHgrow(fastCard, Priority.ALWAYS);
        HBox.setHgrow(preciseCard, Priority.ALWAYS);

        // ── 실행 · 진행 ─────────────────────────────────────────────────
        Button runBtn = Sheet.primaryButton("자리 찾기");
        runBtn.setMaxWidth(Double.MAX_VALUE);
        runBtn.setDisable(true);
        ProgressLine progressLine = new ProgressLine();
        Label statusLbl = Sheet.text("", 13, Sheet.caption(), "400");
        VBox runningBox = new VBox(8, progressLine, statusLbl);
        runningBox.setVisible(false);
        runningBox.setManaged(false);

        // ── 결과 ─────────────────────────────────────────────────────────
        VBox resultBox = new VBox(10);
        resultBox.setPadding(new Insets(18));
        resultBox.setStyle("-fx-background-color:" + Sheet.fill() + ";-fx-background-radius:18;");
        resultBox.setVisible(false);
        resultBox.setManaged(false);

        VBox panel = new VBox(
                section("와이파이가 필요한 방", areaRow),
                section("공유기 몇 대를 둘까요?", countRow),
                section("어떻게 확인할까요?", modeRow),
                gap(4), runBtn, gap(10), runningBox, resultBox);
        panel.setPrefWidth(PANEL_W);
        panel.setMaxWidth(PANEL_W);
        ScrollPane panelScroll = new ScrollPane(panel);
        panelScroll.setFitToWidth(true);
        panelScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        panelScroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;-fx-padding:0;");
        panelScroll.setPrefViewportWidth(PANEL_W + 8);
        panelScroll.setPrefViewportHeight(pvH + 24);

        HBox body = new HBox(20, preview, panelScroll);

        // ── 하단 버튼 ───────────────────────────────────────────────────
        Button closeBtn = Sheet.secondaryButton("닫기");
        Button applyBtn = Sheet.primaryButton("적용하기");
        applyBtn.setDisable(true);
        closeBtn.setPrefWidth(140);
        applyBtn.setPrefWidth(180);
        HBox footer = new HBox(8, spacer(), closeBtn, applyBtn);
        VBox.setMargin(footer, new Insets(20, 0, 0, 0));

        VBox card = Sheet.card("공유기 자리 추천",
                "와이파이가 필요한 방을 고르면, 공유기를 어디에 두면 좋을지 알려 드려요.", body, pvW + 24 + 20 + PANEL_W + 56);
        card.getChildren().add(footer);
        Stage stage = Sheet.createStage(owner, card, false);

        // ── 그리기 ───────────────────────────────────────────────────────
        Runnable redraw = () -> drawPreview(canvas, floorplanImage, pvW, pvH, overlayRef[0],
                resultRef[0] != null ? resultRef[0].positions() : List.of(), scale);
        redraw.run();

        Runnable clearResult = () -> {
            resultRef[0] = null;
            resultBox.setVisible(false);
            resultBox.setManaged(false);
            applyBtn.setDisable(true);
            runBtn.setText("자리 찾기");
        };

        // ── 방 클릭 → Flood Fill (여러 곳 누적) ─────────────────────────
        List<Wall> walls = new ArrayList<>(env.getWalls());
        canvas.setOnMouseClicked(e -> {
            int seedX = (int) (e.getX() / scale);
            int seedY = (int) (e.getY() / scale);
            int mx = seedX / FLOOD_CELL_SIZE, my = seedY / FLOOD_CELL_SIZE;
            if (mx >= 0 && mx < maskW && my >= 0 && my < maskH && combinedMask[my * maskW + mx]) return;

            ApRecommender.FloodResult fr = ApRecommender.floodFill(
                    walls, canvasW, canvasH, FLOOD_CELL_SIZE, seedX, seedY, FLOOD_WALL_THICK);
            if (fr.filledCells() < 10) {
                areaStatus.setText("여기는 너무 좁아요. 방 안쪽을 눌러 주세요.");
                return;
            }
            boolean[] newMask = fr.mask();
            int added = 0;
            for (int i = 0; i < combinedMask.length && i < newMask.length; i++) {
                if (newMask[i] && !combinedMask[i]) { combinedMask[i] = true; added++; }
            }
            totalFilledCells[0] += added;
            clickCount[0]++;
            overlayRef[0] = buildOverlayImage(new ApRecommender.FloodResult(
                    combinedMask, maskW, maskH, totalFilledCells[0]), pvW, pvH, FLOOD_CELL_SIZE, scale);
            double cellM = FLOOD_CELL_SIZE * env.getScaleMPerPx();
            areaStatus.setText(String.format("%d곳 선택 · 약 %.0f㎡", clickCount[0],
                    totalFilledCells[0] * cellM * cellM));
            resetArea.setVisible(true);
            runBtn.setDisable(false);
            clearResult.run();
            redraw.run();
        });

        resetArea.setOnAction(e -> {
            java.util.Arrays.fill(combinedMask, false);
            totalFilledCells[0] = 0;
            clickCount[0] = 0;
            overlayRef[0] = null;
            areaStatus.setText("도면에서 와이파이가 필요한 방을 눌러 주세요.");
            resetArea.setVisible(false);
            runBtn.setDisable(true);
            clearResult.run();
            redraw.run();
        });

        // ── 실행 ─────────────────────────────────────────────────────────
        Task<?>[] taskRef = {null};
        runBtn.setOnAction(e -> {
            if (taskRef[0] != null && taskRef[0].isRunning()) {
                taskRef[0].cancel();
                return;
            }
            if (totalFilledCells[0] < 10) return;

            boolean[] maskCopy = java.util.Arrays.copyOf(combinedMask, combinedMask.length);
            ApRecommender.Params params = new ApRecommender.Params(
                    apCount[0], RssiLevel.TARGET.minDbm,  // 목표: 고른 방 전부 영상통화·스트리밍 수준
                    precise[0] ? 20 : 30, precise[0] ? 12 : 20,
                    precise[0], 6000, Band.GHZ_5,
                    maskCopy, maskW, maskH, FLOOD_CELL_SIZE,
                    true, true, 150.0, preset);

            clearResult.run();
            runBtn.setText("그만하기");
            progressLine.set(0);
            statusLbl.setText("준비하는 중");
            runningBox.setVisible(true);
            runningBox.setManaged(true);

            Task<ApRecommender.Result> task = new Task<>() {
                @Override protected ApRecommender.Result call() {
                    String[] step = {""};
                    double[] pct = {0};
                    Runnable show = () -> statusLbl.setText(step[0] + " · " + Math.round(pct[0] * 100) + "%");
                    return ApRecommender.recommend(env, canvasW, canvasH, params,
                            msg -> Platform.runLater(() -> { step[0] = msg; show.run(); }),
                            v -> Platform.runLater(() -> { pct[0] = v; progressLine.set(v); show.run(); }));
                }
            };
            task.setOnSucceeded(ev -> {
                runningBox.setVisible(false);
                runningBox.setManaged(false);
                resultRef[0] = task.getValue();
                fillResult(resultBox, resultRef[0], state != null && state.isAdvancedMode());
                resultBox.setVisible(true);
                resultBox.setManaged(true);
                runBtn.setText("다시 찾기");
                applyBtn.setDisable(resultRef[0].positions().isEmpty());
                redraw.run();
            });
            task.setOnFailed(ev -> {
                runBtn.setText("자리 찾기");
                Throwable ex = task.getException();
                statusLbl.setText("문제가 생겼어요: " + (ex != null ? ex.getMessage() : "알 수 없는 오류"));
            });
            task.setOnCancelled(ev -> {
                runBtn.setText("자리 찾기");
                runningBox.setVisible(false);
                runningBox.setManaged(false);
            });
            taskRef[0] = task;
            Thread t = new Thread(task, "ap-recommender");
            t.setDaemon(true);
            t.start();
        });

        closeBtn.setOnAction(e -> {
            if (taskRef[0] != null && taskRef[0].isRunning()) taskRef[0].cancel();
            stage.close();
        });
        applyBtn.setOnAction(e -> {
            // 고른 방 마스크를 공용 상태에 저장 → 결과 카드가 이 영역 기준으로 계산된다
            if (state != null && totalFilledCells[0] > 0) {
                state.setCoverageMask(java.util.Arrays.copyOf(combinedMask, combinedMask.length),
                        maskW, maskH, FLOOD_CELL_SIZE);
            }
            stage.close();
            if (resultRef[0] != null && onApply != null) onApply.accept(resultRef[0].positions());
        });

        stage.showAndWait();
    }

    // ── 결과 카드 ─────────────────────────────────────────────────────────
    private static void fillResult(VBox box, ApRecommender.Result r, boolean advanced) {
        box.getChildren().clear();
        ApRecommender.SignalStats m = r.mapStats();
        if (r.positions().isEmpty() || m == null || m.totalPoints() == 0) {
            box.getChildren().add(Sheet.text(r.summary(), 14, Sheet.body(), "400"));
            return;
        }
        double cover = 100.0 * m.strongCount() / m.totalPoints();
        Label big = Sheet.text(String.format("%.0f%%", cover), 34, Sheet.title(), "700");
        Label bigCap = Sheet.text(String.format("고른 방 중 영상통화·스트리밍이 원활한 곳 (%.0f dBm 이상)",
                RssiLevel.TARGET.minDbm), 13, Sheet.caption(), "400");
        box.getChildren().addAll(Sheet.text("추천 결과", 14, Sheet.body(), "700"), big, bigCap,
                levelBar(m), levelLegend(m));

        List<String> lines = new ArrayList<>();
        lines.add(String.format("평균 %.0f dBm · 가장 약한 곳 %.0f dBm", m.avgRssi(), m.minRssi()));
        if (r.fdtdCoveragePercent() >= 0) {
            lines.add(String.format("파동 시뮬레이션으로 다시 확인한 결과 %.0f%%", r.fdtdCoveragePercent()));
        }
        if (r.apsForFullCoverage() > 0 && r.apsForFullCoverage() < r.positions().size()) {
            lines.add(String.format("공유기 %d대만으로도 충분해요. 나머지는 약한 곳을 보강하는 자리예요.",
                    r.apsForFullCoverage()));
        }
        lines.add("도면의 번호 자리를 확인하고 [적용하기]를 눌러 주세요.");
        for (String l : lines) box.getChildren().add(Sheet.text(l, 13, Sheet.body(), "400"));
        if (advanced) {
            long ex = r.dpmTotalExpanded(), pr = r.dpmTotalPruned();
            box.getChildren().add(Sheet.text(String.format("[DPM] 호출 %d · expanded %,d · pruned %,d (%.1f%%) · %,d ms",
                    r.dpmCandidateCount(), ex, pr, (ex + pr) > 0 ? 100.0 * pr / (ex + pr) : 0.0,
                    r.dpmTotalElapsedMs()), 11, Sheet.caption(), "400"));
        }
    }

    /** 등급 비율 막대 — 강함부터 사각지대까지 한 줄. */
    private static Node levelBar(ApRecommender.SignalStats m) {
        HBox bar = new HBox(2);
        bar.setPrefHeight(10);
        bar.setMinHeight(10);
        int[] counts = {m.strongCount(), m.goodCount(), m.weakCount(), m.deadCount()};
        RssiLevel[] lv = {RssiLevel.STRONG, RssiLevel.GOOD, RssiLevel.WEAK, RssiLevel.DEAD};
        for (int i = 0; i < 4; i++) {
            if (counts[i] == 0) continue;
            Region seg = new Region();
            seg.setStyle("-fx-background-color:" + lv[i].hex + ";-fx-background-radius:5;");
            seg.setPrefWidth(1000.0 * counts[i] / m.totalPoints());
            seg.setMinWidth(6);
            HBox.setHgrow(seg, Priority.ALWAYS);
            bar.getChildren().add(seg);
        }
        VBox.setMargin(bar, new Insets(6, 0, 0, 0));
        return bar;
    }

    private static Node levelLegend(ApRecommender.SignalStats m) {
        FlowPane legend = new FlowPane(12, 4);
        int[] counts = {m.strongCount(), m.goodCount(), m.weakCount(), m.deadCount()};
        RssiLevel[] lv = {RssiLevel.STRONG, RssiLevel.GOOD, RssiLevel.WEAK, RssiLevel.DEAD};
        for (int i = 0; i < 4; i++) {
            Region dot = new Region();
            dot.setMinSize(8, 8);
            dot.setMaxSize(8, 8);
            dot.setStyle("-fx-background-color:" + lv[i].hex + ";-fx-background-radius:4;");
            Label l = Sheet.text(String.format("%s %.0f%%", lv[i].label, 100.0 * counts[i] / m.totalPoints()),
                    12, Sheet.caption(), "400");
            HBox item = new HBox(5, dot, l);
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }
        return legend;
    }

    // ── 부품 ─────────────────────────────────────────────────────────────

    /** 진행 막대 — 둥근 회색 트랙 위 파란 채움 (기본 ProgressBar 는 얇은 선처럼 보였다). */
    private static final class ProgressLine extends StackPane {
        private final Region fillBar = new Region();
        ProgressLine() {
            Region track = new Region();
            track.setStyle("-fx-background-color:" + Sheet.line() + ";-fx-background-radius:4;");
            fillBar.setStyle("-fx-background-color:" + Sheet.BLUE + ";-fx-background-radius:4;");
            fillBar.setMaxWidth(0);
            setAlignment(Pos.CENTER_LEFT);
            getChildren().addAll(track, fillBar);
            setMinHeight(8);
            setPrefHeight(8);
            setMaxHeight(8);
        }
        void set(double v) {
            double f = Math.max(0, Math.min(1, v));
            fillBar.maxWidthProperty().unbind();
            fillBar.maxWidthProperty().bind(widthProperty().multiply(f));
        }
    }

    private static VBox section(String titleText, Node content) {
        Label t = Sheet.text(titleText, 15, Sheet.title(), "700");
        VBox v = new VBox(10, t, content);
        v.setPadding(new Insets(0, 0, 22, 0));
        return v;
    }

    private static VBox modeCard(String titleText, String sub) {
        VBox card = new VBox(4, Sheet.text(titleText, 15, Sheet.title(), "700"),
                Sheet.text(sub, 12, Sheet.caption(), "400"));
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setCursor(Cursor.HAND);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static void styleModeCard(VBox card, boolean selected) {
        card.setStyle("-fx-background-radius:14;-fx-border-radius:14;-fx-border-width:2;" +
                "-fx-background-color:" + (selected ? Sheet.BLUE_SOFT : Sheet.fill()) + ";" +
                "-fx-border-color:" + (selected ? Sheet.BLUE : "transparent") + ";");
    }

    private static void refreshChips(List<Button> chips, int selected) {
        for (int i = 0; i < chips.size(); i++) {
            boolean on = (i + 1) == selected;
            chips.get(i).setStyle("-fx-background-radius:12;" +
                    "-fx-background-color:" + (on ? Sheet.BLUE : Sheet.fill()) + ";" +
                    "-fx-text-fill:" + (on ? "white" : Sheet.body()) + ";" +
                    "-fx-font-size:14px;-fx-font-weight:700;" +
                    "-fx-font-family:" + app.ui.Styles.FONT_STACK + ";");
        }
    }

    private static Button textButton(String label) {
        Button b = new Button(label);
        b.setCursor(Cursor.HAND);
        b.setStyle("-fx-background-color:transparent;-fx-text-fill:" + Sheet.BLUE + ";" +
                "-fx-font-size:13px;-fx-font-weight:700;-fx-padding:4 0 4 0;" +
                "-fx-font-family:" + app.ui.Styles.FONT_STACK + ";");
        return b;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Region gap(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        r.setPrefHeight(h);
        return r;
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
                    pw.setColor(px, py, Color.rgb(49, 130, 246, 0.15));
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
            g.setStroke(Color.rgb(49, 130, 246, 0.6));
            g.setLineWidth(1.0);
        }

        // AP 마커
        for (int i = 0; i < positions.size(); i++) {
            Point2D p = positions.get(i);
            double cx = p.getX() * scale, cy = p.getY() * scale;

            g.setFill(Color.rgb(49, 130, 246, 0.08));
            g.fillOval(cx - 40, cy - 40, 80, 80);
            g.setFill(Color.rgb(49, 130, 246, 0.22));
            g.fillOval(cx - 18, cy - 18, 36, 36);
            g.setStroke(Color.rgb(49, 130, 246, 0.85));
            g.setLineWidth(2.5);
            g.strokeOval(cx - 13, cy - 13, 26, 26);
            g.setFill(Color.rgb(49, 130, 246));
            g.fillOval(cx - 6, cy - 6, 12, 12);
            g.setFill(Color.WHITE);
            g.setFont(javafx.scene.text.Font.font("SF Pro Text",
                    javafx.scene.text.FontWeight.BOLD, 10));
            g.fillText(String.valueOf(i + 1), cx - 3, cy + 4);
            g.setFill(Color.rgb(49, 130, 246));
            g.setFont(javafx.scene.text.Font.font("SF Pro Text",
                    javafx.scene.text.FontWeight.BOLD, 11));
            g.fillText("공유기 " + (i + 1), cx + 16, cy + 4);
        }
    }
}
