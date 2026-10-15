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
        resetArea.managedProperty().bind(resetArea.visibleProperty());  // 숨었을 때는 자리도 비운다
        resetArea.setMinWidth(Region.USE_PREF_SIZE);
        // 안내문이 남은 폭을 다 쓰도록 — 예전엔 버튼 옆에서 좁게 눌려 세 줄로 접혔다
        areaStatus.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(areaStatus, Priority.ALWAYS);
        HBox areaRow = new HBox(8, areaStatus, resetArea);
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
        VBox fastCard = modeCard("빠르게", "1분 안팎 · 경로 손실 모델로 계산");
        VBox preciseCard = modeCard("꼼꼼하게", "2~4분 · 벽 반사·간섭까지 파동으로 확인");
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
        // 결과까지 한눈에 보이도록 미리보기보다 조금 길게 (작은 화면에서는 스크롤)
        panelScroll.setPrefViewportHeight(Math.max(pvH + 24, 560));
        // 결과가 붙어 패널 높이가 바뀐 뒤에 맨 아래로 내린다 (바뀌기 전에 내리면 효과가 없다)
        boolean[] scrollToEnd = {false};
        panel.heightProperty().addListener((o, oldH, newH) -> {
            if (scrollToEnd[0]) {
                scrollToEnd[0] = false;
                Platform.runLater(() -> panelScroll.setVvalue(1.0));
            }
        });

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
        Live live = new Live();
        WritableImage[] heatRef = {null};
        Node heatLegend = heatLegend();
        heatLegend.setVisible(false);
        preview.getChildren().add(heatLegend);
        StackPane.setAlignment(heatLegend, Pos.BOTTOM_LEFT);
        StackPane.setMargin(heatLegend, new Insets(0, 0, 4, 4));
        Runnable redraw = () -> drawPreview(canvas, floorplanImage, pvW, pvH, overlayRef[0], heatRef[0], live,
                resultRef[0] != null ? resultRef[0].positions() : List.copyOf(live.picked), scale);
        redraw.run();
        // 계산 중에는 알림이 여러 스레드에서 쏟아지므로, 화면은 1초에 20번까지만 다시 그린다
        javafx.animation.AnimationTimer liveTimer = new javafx.animation.AnimationTimer() {
            long last = 0;
            @Override public void handle(long now) {
                if (live.dirty && now - last > 50_000_000L) {
                    live.dirty = false;
                    last = now;
                    redraw.run();
                }
            }
        };

        Runnable clearResult = () -> {
            resultRef[0] = null;
            heatRef[0] = null;
            heatLegend.setVisible(false);
            live.reset();
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

            Task<Outcome> task = new Task<>() {
                @Override protected Outcome call() {
                    String[] step = {""};
                    double[] pct = {0};
                    Runnable show = () -> statusLbl.setText(step[0] + " · " + Math.round(pct[0] * 100) + "%");
                    ApRecommender.Result r = ApRecommender.recommend(env, canvasW, canvasH, params,
                            msg -> Platform.runLater(() -> { step[0] = msg; show.run(); }),
                            v -> Platform.runLater(() -> { pct[0] = v; progressLine.set(v); show.run(); }),
                            live.listener(this::isCancelled));
                    if (isCancelled() || r.positions().isEmpty()) return new Outcome(r, null);
                    Platform.runLater(() -> statusLbl.setText("추천 배치의 신호 지도를 그리는 중"));
                    return new Outcome(r, recommendedHeatmap(env, r.positions(), preset, canvasW, canvasH,
                            maskCopy, maskW, FLOOD_CELL_SIZE));
                }
            };
            live.reset();
            liveTimer.start();
            task.setOnSucceeded(ev -> {
                liveTimer.stop();
                runningBox.setVisible(false);
                runningBox.setManaged(false);
                resultRef[0] = task.getValue().result();
                int[] argb = task.getValue().heatArgb();
                if (argb != null) {
                    WritableImage img = new WritableImage(canvasW, canvasH);
                    img.getPixelWriter().setPixels(0, 0, canvasW, canvasH,
                            javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, canvasW);
                    heatRef[0] = app.engine.WifiMath.boxBlur(img, 4);
                    heatLegend.setVisible(true);
                }
                fillResult(resultBox, resultRef[0], state != null && state.isAdvancedMode());
                resultBox.setVisible(true);
                resultBox.setManaged(true);
                // 결과는 패널 맨 아래에 붙는다 — 보이도록 끝까지 내린다 (예전엔 스크롤해야 보였다)
                scrollToEnd[0] = true;
                runBtn.setText("다시 찾기");
                applyBtn.setDisable(resultRef[0].positions().isEmpty());
                redraw.run();
            });
            task.setOnFailed(ev -> {
                liveTimer.stop();
                runBtn.setText("자리 찾기");
                Throwable ex = task.getException();
                statusLbl.setText("문제가 생겼어요: " + (ex != null ? ex.getMessage() : "알 수 없는 오류"));
            });
            task.setOnCancelled(ev -> {
                liveTimer.stop();
                live.reset();
                redraw.run();
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
                                    WritableImage overlay, WritableImage heat, Live live,
                                    List<Point2D> positions, double scale) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.drawImage(bgImage, 0, 0, w, h);

        // 고른 방 밖은 어둡게
        if (overlay != null) g.drawImage(overlay, 0, 0, w, h);
        // 결과 신호 지도 (고른 방 안만)
        if (heat != null) g.drawImage(heat, 0, 0, w, h);

        // ── 계산 중: 후보 자리 점수 (파랄수록·클수록 좋은 자리) ──
        if (heat == null && live != null) {
            java.util.Map<Point2D, Double> scores = new java.util.HashMap<>(live.scores);
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (double v : scores.values()) { min = Math.min(min, v); max = Math.max(max, v); }
            double span = (max > min) ? max - min : 1.0;
            for (Point2D c : live.candidates) {
                double cx = c.getX() * scale, cy = c.getY() * scale;
                Double v = scores.get(c);
                if (v == null) {
                    g.setFill(Color.rgb(120, 130, 145, 0.35));
                    g.fillOval(cx - 1.5, cy - 1.5, 3, 3);
                } else {
                    double t = (v - min) / span;
                    double r = 2.5 + 3.5 * t;
                    g.setFill(Color.rgb(180, 190, 205, 0.5).interpolate(Color.rgb(49, 130, 246, 0.95), t));
                    g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
                }
            }
            Point2D best = live.best;
            if (best != null) {
                double cx = best.getX() * scale, cy = best.getY() * scale;
                g.setStroke(Color.WHITE);
                g.setLineWidth(4);
                g.strokeOval(cx - 11, cy - 11, 22, 22);
                g.setStroke(Color.rgb(49, 130, 246));
                g.setLineWidth(2.5);
                g.strokeOval(cx - 11, cy - 11, 22, 22);
                pill(g, "지금까지 가장 좋은 자리", cx + 15, cy - 8);
            }
        }

        // ── 정한 자리 (번호 마커) ──
        for (int i = 0; i < positions.size(); i++) {
            Point2D p = positions.get(i);
            double cx = p.getX() * scale, cy = p.getY() * scale;
            g.setFill(Color.rgb(49, 130, 246, 0.16));
            g.fillOval(cx - 20, cy - 20, 40, 40);
            g.setFill(Color.WHITE);
            g.fillOval(cx - 12, cy - 12, 24, 24);
            g.setFill(Color.rgb(49, 130, 246));
            g.fillOval(cx - 10, cy - 10, 20, 20);
            g.setFill(Color.WHITE);
            g.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 11));
            g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
            g.fillText(String.valueOf(i + 1), cx, cy + 4);
            g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
            pill(g, "공유기 " + (i + 1), cx + 16, cy - 8);
        }
    }

    /** 흰 바탕 이름표 (지도 위 글자가 묻히지 않게). */
    private static void pill(GraphicsContext g, String text, double x, double y) {
        javafx.scene.text.Font f = javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 11);
        javafx.scene.text.Text t = new javafx.scene.text.Text(text);
        t.setFont(f);
        double tw = t.getLayoutBounds().getWidth();
        g.setFill(Color.rgb(255, 255, 255, 0.92));
        g.fillRoundRect(x, y, tw + 12, 18, 9, 9);
        g.setFill(Color.web("#191F28"));
        g.setFont(f);
        g.fillText(text, x + 6, y + 13);
    }

    /** 결과 신호 지도 아래 작은 범례 (강함 빨강 → 사각지대 파랑). */
    private static Node heatLegend() {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(6, 10, 6, 10));
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setStyle("-fx-background-color:rgba(255,255,255,0.92);-fx-background-radius:10;");
        box.getChildren().add(Sheet.text("추천 배치의 신호", 11, "#191F28", "700"));
        for (RssiLevel lv : RssiLevel.values()) {
            Region dot = new Region();
            dot.setMinSize(8, 8);
            dot.setMaxSize(8, 8);
            dot.setStyle("-fx-background-color:" + lv.hex + ";-fx-background-radius:4;");
            HBox item = new HBox(4, dot, Sheet.text(lv.label, 11, "#4E5968", "400"));
            item.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(item);
        }
        return box;
    }

    /**
     * 추천 배치로 만든 신호 지도 (ARGB) — 메인 화면과 같은 생성기·같은 공유기 설정.
     * 고른 방 밖은 투명하게 비운다.
     */
    private static int[] recommendedHeatmap(app.model.WifiEnvironment env, List<Point2D> positions,
                                            app.model.ApPreset preset, int w, int h,
                                            boolean[] mask, int maskW, int cell) {
        app.model.WifiEnvironment copy = new app.model.WifiEnvironment();
        copy.setScaleMPerPx(env.getScaleMPerPx());
        copy.getWalls().setAll(env.getWalls());
        for (int i = 0; i < positions.size(); i++) {
            app.model.AP ap = new app.model.AP();
            ap.name = "공유기 " + (i + 1);
            ap.x = positions.get(i).getX();
            ap.y = positions.get(i).getY();
            ap.heightM = 2.5;
            ap.useDualBandDefaults();
            if (preset != null) preset.applyTo(ap);
            copy.getAps().add(ap);
        }
        int[] argb = new app.engine.HeatmapGenerator(copy, null).generate(w, h, 6, 0, null).argbPixels;
        for (int y = 0; y < h; y++) {
            int my = y / cell;
            for (int x = 0; x < w; x++) {
                int idx = my * maskW + x / cell;
                if (idx < 0 || idx >= mask.length || !mask[idx]) argb[y * w + x] = 0;
            }
        }
        return argb;
    }

    /** 계산 결과 + 결과 신호 지도. */
    private record Outcome(ApRecommender.Result result, int[] heatArgb) {}

    /** 계산 과정 미리보기 상태 — 계산 스레드가 쓰고 FX 스레드가 읽는다. */
    private static final class Live {
        volatile List<Point2D> candidates = List.of();
        final java.util.concurrent.ConcurrentHashMap<Point2D, Double> scores = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.CopyOnWriteArrayList<Point2D> picked = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile Point2D best;
        volatile double bestScore = Double.NEGATIVE_INFINITY;
        volatile int currentAp = -1;
        volatile boolean dirty;

        void reset() {
            candidates = List.of();
            scores.clear();
            picked.clear();
            best = null;
            bestScore = Double.NEGATIVE_INFINITY;
            currentAp = -1;
            dirty = true;
        }

        /** @param cancelled 취소된 뒤 늦게 오는 알림은 버린다 */
        ApRecommender.Listener listener(java.util.function.BooleanSupplier cancelled) {
            return new ApRecommender.Listener() {
                @Override public void onCandidates(List<Point2D> c) {
                    if (cancelled.getAsBoolean()) return;
                    candidates = c;
                    dirty = true;
                }
                @Override public void onCandidateScored(int apIndex, Point2D p, double score) {
                    if (cancelled.getAsBoolean()) return;
                    if (apIndex != currentAp) {
                        synchronized (Live.this) {
                            if (apIndex != currentAp) {
                                currentAp = apIndex;
                                scores.clear();
                                best = null;
                                bestScore = Double.NEGATIVE_INFINITY;
                            }
                        }
                    }
                    scores.put(p, score);
                    synchronized (Live.this) {
                        if (score > bestScore) { bestScore = score; best = p; }
                    }
                    dirty = true;
                }
                @Override public void onApChosen(int apIndex, Point2D position) {
                    if (cancelled.getAsBoolean()) return;
                    picked.add(position);
                    synchronized (Live.this) {
                        scores.clear();
                        best = null;
                        bestScore = Double.NEGATIVE_INFINITY;
                    }
                    dirty = true;
                }
                @Override public void onValidating(int round, List<Point2D> positions) {
                    if (cancelled.getAsBoolean()) return;
                    picked.clear();
                    picked.addAll(positions);
                    dirty = true;
                }
                @Override public void onFinished(List<Point2D> positions) {
                    if (cancelled.getAsBoolean()) return;
                    picked.clear();
                    picked.addAll(positions);
                    dirty = true;
                }
            };
        }
    }
}
