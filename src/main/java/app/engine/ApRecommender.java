package app.engine;

import app.model.*;
import javafx.geometry.Point2D;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;

/**
 * AP 위치 추천 엔진.
 *
 * 2단계 접근:
 *   Phase 1 — Ray-cast 그리디 탐색
 *   Phase 2 — FDTD 검증 (옵션)
 *
 * coverageMask가 지정되면 해당 영역 안에서만 후보/측정 포인트를 생성.
 */
public class ApRecommender {

    // ── 파라미터 ─────────────────────────────────────────────────────────────
    public record Params(
            int apCount,
            double targetRssiDbm,
            int gridStepPx,
            int sampleStepPx,
            boolean useFdtd,
            int fdtdSteps,
            Band fdtdBand,
            boolean[] coverageMask,   // canvasW × canvasH 비트맵 (null=전체)
            int maskW, int maskH,     // mask 해상도
            int maskCellSize,         // 마스크 셀 크기 (px) — floodFill 시 사용한 값
            boolean useDpmEvaluation, // 후보 평가 시 DPM 사용 (false=기존 sampleRssiAt)
            boolean dpmGreedyEnabled, // 가지치기 ON/OFF (메모리 효율 도구)
            double pruningThresholdDb // selCost 가지치기 임계값 [dB]. 무한대→OFF
    ) {
        public static Params defaults() {
            return new Params(2, -65.0, 25, 15, true, 6000, Band.GHZ_5, null, 0, 0, 1,
                    true,   // useDpmEvaluation: 영구 ON (이번 캡스톤 기여)
                    true,   // dpmGreedyEnabled: 영구 ON (메모리 효율)
                    150.0); // pruningThresholdDb: 기본 150dB
        }
    }

    // ── 결과 ─────────────────────────────────────────────────────────────────
    public record Result(
            List<Point2D> positions,
            double coveragePercent,
            double fdtdCoveragePercent,
            String summary,
            FdtdSignalStats fdtdStats,  // null if FDTD not used
            // 발표용 측정값 (useDpmEvaluation일 때만 의미 있음, 아니면 0)
            long dpmTotalExpanded,
            long dpmTotalPruned,
            long dpmTotalElapsedMs,
            int  dpmCandidateCount       // DPM이 호출된 후보 수 (= 후보 풀 × phase 수)
    ) {}

    /** FDTD 시뮬레이션에서 측정된 신호 품질 통계 */
    public record FdtdSignalStats(
            double minRssi,          // 최약 신호 (dBm)
            double maxRssi,          // 최강 신호 (dBm)
            double avgRssi,          // 평균 신호 (dBm)
            double medianRssi,       // 중앙값 (dBm)
            double stdDev,           // 표준편차 (낮을수록 균일)
            double pct5Rssi,         // 하위 5% 신호 (dBm) — 최악 지점 대표
            int strongCount,         // 강한 신호 (-55 이상) 측정점 수
            int goodCount,           // 양호 (-65 이상) 측정점 수
            int weakCount,           // 약함 (-75 이상) 측정점 수
            int deadCount,           // 사각지대 (-75 미만) 측정점 수
            int totalPoints          // 전체 측정점
    ) {
        /** 신호 균일도 (0~100%, 높을수록 균일) */
        public double uniformityPercent() {
            if (totalPoints == 0) return 0;
            // 표준편차 기반: stdDev=0이면 100%, stdDev≥15이면 ~0%
            return Math.max(0, 100.0 * (1.0 - stdDev / 15.0));
        }
    }

    private record CandidateScore(int px, int py, double score) {}

    // ── Flood Fill로 커버 마스크 생성 ────────────────────────────────────────
    /**
     * 벽을 래스터화한 뒤 seedPx/seedPy에서 BFS로 내부 영역을 채운 마스크 반환.
     *
     * @param walls      벽 목록
     * @param canvasW    도면 너비 (px)
     * @param canvasH    도면 높이 (px)
     * @param cellSize   마스크 셀 크기 (px) — 4~6 권장
     * @param seedPx     시드 X (원본 px 좌표)
     * @param seedPy     시드 Y (원본 px 좌표)
     * @param wallThick  벽 두께 (셀 수) — 2~3 권장 (누수 방지)
     * @return {mask, maskW, maskH}
     */
    public static FloodResult floodFill(List<Wall> walls, int canvasW, int canvasH,
                                        int cellSize, int seedPx, int seedPy, int wallThick) {
        int mw = (canvasW + cellSize - 1) / cellSize;
        int mh = (canvasH + cellSize - 1) / cellSize;
        boolean[] wallGrid = new boolean[mw * mh];

        // 1) 벽 래스터화 (Bresenham + 두께)
        for (Wall w : walls) {
            if (w == null) continue;
            rasterWall(wallGrid, mw, mh,
                    (int) (w.x1 / cellSize), (int) (w.y1 / cellSize),
                    (int) (w.x2 / cellSize), (int) (w.y2 / cellSize),
                    wallThick);
        }

        // 2) BFS flood fill
        boolean[] filled = new boolean[mw * mh];
        int sx = seedPx / cellSize;
        int sy = seedPy / cellSize;
        if (sx < 0 || sx >= mw || sy < 0 || sy >= mh) {
            return new FloodResult(filled, mw, mh, 0);
        }
        if (wallGrid[sy * mw + sx]) {
            // 시드가 벽 위 → 가장 가까운 빈 셀 탐색
            int[] near = findNearestEmpty(wallGrid, mw, mh, sx, sy);
            if (near == null) return new FloodResult(filled, mw, mh, 0);
            sx = near[0]; sy = near[1];
        }

        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sy});
        filled[sy * mw + sx] = true;
        int filledCount = 0;

        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            filledCount++;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int nx = c[0] + d[0], ny = c[1] + d[1];
                if (nx < 0 || nx >= mw || ny < 0 || ny >= mh) continue;
                int idx = ny * mw + nx;
                if (!filled[idx] && !wallGrid[idx]) {
                    filled[idx] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }

        return new FloodResult(filled, mw, mh, filledCount);
    }

    public record FloodResult(boolean[] mask, int maskW, int maskH, int filledCells) {}

    // ── 메인 실행 ────────────────────────────────────────────────────────────
    public static Result recommend(WifiEnvironment env, int canvasW, int canvasH,
                                   Params params, Consumer<String> statusCallback) {
        if (statusCallback == null) statusCallback = s -> {};

        List<Wall> walls = new ArrayList<>(env.getWalls());
        boolean[] mask = params.coverageMask;
        int mw = params.maskW;
        int mh = params.maskH;

        // 마스크 셀 크기 (다이얼로그에서 전달받은 정확한 값 사용)
        int cellSize = (mask != null) ? Math.max(1, params.maskCellSize) : 1;

        // ── 1. 후보 위치 생성 ────────────────────────────────────────────────
        statusCallback.accept("후보 위치 생성 중...");
        List<Point2D> candidates = buildCandidateGrid(canvasW, canvasH,
                params.gridStepPx, walls, mask, mw, mh, cellSize);
        if (candidates.isEmpty()) {
            return new Result(List.of(), 0, -1, "유효한 후보 위치가 없습니다.", null, 0, 0, 0, 0);
        }

        // 전체 wall-clock 측정 시작 (실제 체감 시간 — 병렬 처리 이득 포함)
        long wallStartNanos = System.nanoTime();

        List<Point2D> measurePoints = buildMeasureGrid(canvasW, canvasH,
                params.sampleStepPx, mask, mw, mh, cellSize);

        statusCallback.accept(String.format("후보 %d개, 측정점 %d개",
                candidates.size(), measurePoints.size()));

        // ── 2. Phase 1: 그리디 Ray-cast ──────────────────────────────────────
        List<Point2D> chosen = new ArrayList<>();
        Set<Integer> coveredSet = new HashSet<>();

        AP templateAp = new AP();
        templateAp.heightM = 2.5;
        templateAp.name = "Rec";

        // DPM 누적 측정 카운터 (params.useDpmEvaluation일 때만 누적)
        java.util.concurrent.atomic.AtomicLong dpmExp = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.concurrent.atomic.AtomicLong dpmPru = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.concurrent.atomic.AtomicLong dpmMs  = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.concurrent.atomic.AtomicInteger dpmCnt = new java.util.concurrent.atomic.AtomicInteger(0);

        final double scaleMPerPx = env.getScaleMPerPx();
        // 최소 분리 거리: 후보 격자 간격의 4배 (AP들이 너무 붙지 않도록)
        final double minApSepPx = params.gridStepPx * 4.0;

        for (int apIdx = 0; apIdx < params.apCount; apIdx++) {
            int apNum = apIdx + 1;
            statusCallback.accept("AP " + apNum + "/" + params.apCount + " 최적 위치 탐색 중...");

            // ── 이미 배치된 AP 근처 후보 제거 (중복 배치 방지) ──────────────
            List<Point2D> availableCandidates = candidates;
            if (!chosen.isEmpty()) {
                List<Point2D> filtered = new ArrayList<>();
                for (Point2D c : candidates) {
                    boolean tooClose = false;
                    for (Point2D p : chosen) {
                        if (p.distance(c) < minApSepPx) { tooClose = true; break; }
                    }
                    if (!tooClose) filtered.add(c);
                }
                if (!filtered.isEmpty()) availableCandidates = filtered;
            }

            WifiEnvironment tempEnv = cloneEnvWithAps(env, chosen, templateAp);
            CandidateScore best = evaluateCandidatesParallel(
                    tempEnv, availableCandidates, measurePoints, params, coveredSet,
                    canvasW, canvasH, walls, dpmExp, dpmPru, dpmMs, dpmCnt);

            if (best == null) break;
            chosen.add(new Point2D(best.px, best.py));

            // ── DPM 기반 커버리지 업데이트 (평가 모델과 일관성 유지) ──────────
            // Legacy sampleRssiAt 대신 DPM Dijkstra를 사용해 커버된 포인트를 정확히 추적.
            // 이 일관성이 없으면 coveredSet이 과대 추정되어 2번째 AP가 무의미한 위치에 배치됨.
            if (params.useDpmEvaluation && Double.isFinite(scaleMPerPx) && scaleMPerPx > 0) {
                updateCoveredSetDpm(chosen.get(chosen.size() - 1),
                        canvasW, canvasH, scaleMPerPx, walls,
                        measurePoints, params, coveredSet,
                        dpmExp, dpmPru, dpmMs, dpmCnt);
            } else {
                WifiEnvironment withNew = cloneEnvWithAps(env, chosen, templateAp);
                updateCoveredSet(withNew, measurePoints, params.targetRssiDbm, coveredSet);
            }
        }

        double rayCoverage = measurePoints.isEmpty() ? 0
                : 100.0 * coveredSet.size() / measurePoints.size();

        // ── 3. Phase 2: FDTD 검증 + 피드백 루프 ──────────────────────────────
        // 조건: 커버율 80% 이상 AND 균일도 50% 이상이면 수용
        double fdtdCoverage = -1;
        FdtdSignalStats finalStats = null;
        if (params.useFdtd && !chosen.isEmpty()) {
            int maxRefinements = 3;
            double acceptableCoverageRatio = 0.80;
            double acceptableUniformity = 50.0;

            for (int round = 0; round <= maxRefinements; round++) {
                String label = round == 0 ? "파동 시뮬레이션으로 검증 중..."
                        : String.format("FDTD 피드백 %d차 — AP 위치 미세조정 중...", round);
                statusCallback.accept(label);

                FdtdValidationResult vr = runFdtdValidation(env, chosen, templateAp,
                        canvasW, canvasH, measurePoints, params, statusCallback);
                fdtdCoverage = vr.coveragePercent;
                finalStats = vr.stats;

                if (fdtdCoverage < 0) break;

                boolean coverageOk = fdtdCoverage >= rayCoverage * acceptableCoverageRatio;
                boolean uniformityOk = finalStats == null
                        || finalStats.uniformityPercent() >= acceptableUniformity;

                if ((coverageOk && uniformityOk) || round == maxRefinements) break;

                // 피드백 사유 표시
                String reason = !coverageOk
                        ? String.format("커버율 %.0f%% 부족", fdtdCoverage)
                        : String.format("균일도 %.0f%% 부족", finalStats.uniformityPercent());
                statusCallback.accept(reason + " — AP 위치 조정 중...");

                // 미커버 + 약신호 지점 모두를 포함하여 AP를 이동
                List<Integer> weakIndices = collectWeakIndices(vr, finalStats);
                chosen = refineApPositions(chosen, measurePoints, weakIndices,
                        mask, mw, mh, cellSize, walls, params.gridStepPx);
            }
        }

        String summary = buildSummary(rayCoverage, fdtdCoverage, finalStats);
        statusCallback.accept("완료! " + summary.split("\n")[0]);

        // wall-clock 시간 (실제 체감, 병렬 이득 포함)
        long wallMs = (System.nanoTime() - wallStartNanos) / 1_000_000L;

        // 발표용 측정값 — stderr + UI로 무조건 출력 (진단 포함)
        long ex = dpmExp.get(), pr = dpmPru.get(), ms = dpmMs.get();
        int  cn = dpmCnt.get();
        double pruRatio = (ex + pr) > 0 ? 100.0 * pr / (ex + pr) : 0.0;
        System.err.printf(
            "[ApRecommender DPM] greedy=%s  threshold=%.1f dB  scale=%.4f m/px  DPM호출=%d%n" +
            "                    expanded=%,d  pruned=%,d (%.1f%%)%n" +
            "                    DPM 누적 CPU=%,d ms   전체 wall-clock=%,d ms%n",
            params.dpmGreedyEnabled, params.pruningThresholdDb,
            env.getScaleMPerPx(), cn, ex, pr, pruRatio, ms, wallMs);
        System.err.flush();
        // 요약 텍스트 끝에 측정값 부착 (다이얼로그에 표시됨)
        if (params.useDpmEvaluation) {
            summary = summary + String.format(
                "%n[DPM A*] greedy=%s  호출=%d  expanded=%,d  pruned=%,d (%.1f%%)%n" +
                "         DPM 누적=%,dms  전체 wall=%,dms",
                params.dpmGreedyEnabled ? "ON" : "OFF",
                cn, ex, pr, pruRatio, ms, wallMs);
        }

        return new Result(chosen, rayCoverage, fdtdCoverage, summary, finalStats,
                dpmExp.get(), dpmPru.get(), dpmMs.get(), dpmCnt.get());
    }

    // ── 후보 그리드 (마스크 필터) ────────────────────────────────────────────
    private static List<Point2D> buildCandidateGrid(int w, int h, int step,
                                                    List<Wall> walls,
                                                    boolean[] mask, int mw, int mh, int cellSize) {
        List<Point2D> pts = new ArrayList<>();
        for (int x = step; x < w - step; x += step) {
            for (int y = step; y < h - step; y += step) {
                if (mask != null) {
                    int mx = x / cellSize, my = y / cellSize;
                    if (mx < 0 || mx >= mw || my < 0 || my >= mh) continue;
                    if (!mask[my * mw + mx]) continue;
                }
                if (!isOnWall(x, y, walls, 6.0)) {
                    pts.add(new Point2D(x, y));
                }
            }
        }
        return pts;
    }

    private static List<Point2D> buildMeasureGrid(int w, int h, int step,
                                                  boolean[] mask, int mw, int mh, int cellSize) {
        List<Point2D> pts = new ArrayList<>();
        for (int x = step; x < w - step; x += step) {
            for (int y = step; y < h - step; y += step) {
                if (mask != null) {
                    int mx = x / cellSize, my = y / cellSize;
                    if (mx < 0 || mx >= mw || my < 0 || my >= mh) continue;
                    if (!mask[my * mw + mx]) continue;
                }
                pts.add(new Point2D(x, y));
            }
        }
        return pts;
    }

    // ── 후보 병렬 평가 ──────────────────────────────────────────────────────
    //
    // 점수 = 커버 수 × 1000
    //      + 최약 신호 보너스 (min RSSI가 높을수록 좋음)
    //      + 균일도 보너스 (표준편차가 낮을수록 좋음)
    //      + 평균 RSSI
    //
    // → 커버 수가 같으면 "최약 지점이 강하고 균일한" 배치가 선택됨
    //
    private static CandidateScore evaluateCandidatesParallel(
            WifiEnvironment baseEnv, List<Point2D> candidates,
            List<Point2D> measurePoints, Params params,
            Set<Integer> alreadyCovered,
            int canvasW, int canvasH, List<Wall> walls,
            java.util.concurrent.atomic.AtomicLong dpmExp,
            java.util.concurrent.atomic.AtomicLong dpmPru,
            java.util.concurrent.atomic.AtomicLong dpmMs,
            java.util.concurrent.atomic.AtomicInteger dpmCnt) {

        ForkJoinPool pool = ForkJoinPool.commonPool();
        List<ForkJoinTask<CandidateScore>> tasks = new ArrayList<>();

        final double scaleMPerPxLocal = baseEnv.getScaleMPerPx();

        for (Point2D cand : candidates) {
            tasks.add(pool.submit(() -> {
                AP testAp = new AP();
                testAp.x = cand.getX();
                testAp.y = cand.getY();
                testAp.heightM = 2.5;
                testAp.name = "Test";

                WifiEnvironment testEnv = cloneEnvForScoring(baseEnv, testAp);

                // DPM 평가: Dijkstra 1회 sweep + 가지치기 (multi-target 최적)
                // 후보 AP에서 1회 sweep으로 모든 측정점 PL이 bestCost[]에 채워짐
                DpmPathGrid dpmGrid = null;
                if (params.useDpmEvaluation && Double.isFinite(scaleMPerPxLocal) && scaleMPerPxLocal > 0) {
                    dpmGrid = new DpmPathGrid(canvasW, canvasH, scaleMPerPxLocal, walls, Band.GHZ_24);
                    if (params.dpmGreedyEnabled) {
                        dpmGrid.setPruningThresholdDb(params.pruningThresholdDb);
                    } else {
                        dpmGrid.setGreedyEnabled(false);
                    }
                    dpmGrid.runDijkstra(testAp.x, testAp.y, 2.4);
                    dpmExp.addAndGet(dpmGrid.getExpandedNodeCount());
                    dpmPru.addAndGet(dpmGrid.getPrunedNodeCount());
                    dpmMs .addAndGet(dpmGrid.getElapsedMs());
                    dpmCnt.incrementAndGet();
                }

                int newCovered = 0;
                double rssiSum = 0;
                double minRssi = 0;  // 미커버 포인트 중 최약값
                double sqSum = 0;
                int uncoveredCount = 0;

                for (int i = 0; i < measurePoints.size(); i++) {
                    if (alreadyCovered.contains(i)) continue;
                    Point2D mp = measurePoints.get(i);
                    double rssi;
                    if (dpmGrid != null) {
                        // DPM 기반 RSSI: 22 dBm EIRP - PL (대표값)
                        double pl = dpmGrid.getPathLossDb(mp.getX(), mp.getY());
                        rssi = 22.0 - pl;
                    } else {
                        // 기존 sampleRssiAt (LEGACY/FDTD 모드용)
                        rssi = testEnv.sampleRssiAt((int) mp.getX(), (int) mp.getY());
                    }
                    rssiSum += rssi;
                    sqSum += rssi * rssi;
                    uncoveredCount++;

                    if (rssi >= params.targetRssiDbm) {
                        newCovered++;
                    }
                    if (uncoveredCount == 1 || rssi < minRssi) {
                        minRssi = rssi;
                    }
                }

                if (uncoveredCount == 0) {
                    return new CandidateScore((int) cand.getX(), (int) cand.getY(), 0);
                }

                double avgRssi = rssiSum / uncoveredCount;
                double variance = (sqSum / uncoveredCount) - (avgRssi * avgRssi);
                double stdDev = Math.sqrt(Math.max(0, variance));

                // 점수 구성:
                // 1) 커버 수 (가장 중요) — ×1000으로 큰 가중치
                // 2) 최약 신호 보너스 — min이 -80이면 0점, -40이면 +40점
                double minBonus = Math.max(0, minRssi + 80);  // -80 기준으로 0~40
                // 3) 균일도 보너스 — stdDev=0이면 +20, stdDev≥20이면 0
                double uniformBonus = Math.max(0, 20.0 - stdDev);
                // 4) 평균 RSSI
                double score = newCovered * 1000.0
                        + minBonus * 10.0    // 최약 신호 올리기 (×10 가중)
                        + uniformBonus * 5.0 // 균일도 (×5 가중)
                        + avgRssi;           // 평균은 미세 조정용

                return new CandidateScore((int) cand.getX(), (int) cand.getY(), score);
            }));
        }

        CandidateScore best = null;
        for (ForkJoinTask<CandidateScore> task : tasks) {
            CandidateScore cs = task.join();
            if (best == null || cs.score > best.score) best = cs;
        }
        return best;
    }

    // ── FDTD 검증 결과 ──────────────────────────────────────────────────────
    private record FdtdValidationResult(double coveragePercent, List<Integer> uncoveredIndices,
                                        FdtdSignalStats stats, double[] perPointRssi) {}

    // ── FDTD 검증 ───────────────────────────────────────────────────────────
    private static FdtdValidationResult runFdtdValidation(WifiEnvironment env, List<Point2D> apPositions,
                                            AP templateAp, int canvasW, int canvasH,
                                            List<Point2D> measurePoints, Params params,
                                            Consumer<String> statusCallback) {
        try {
            WifiEnvironment fdtdEnv = cloneEnvWithAps(env, apPositions, templateAp);
            int cellPx = Math.max(2, Math.min(8,
                    (int) Math.ceil(Math.sqrt((double) canvasW * canvasH / 200_000.0))));

            FdtdWaveSimulator sim = new FdtdWaveSimulator(
                    fdtdEnv, canvasW, canvasH, cellPx, params.fdtdBand);

            int totalSteps = params.fdtdSteps;
            int batchSize = 100;
            for (int s = 0; s < totalSteps; s += batchSize) {
                sim.step(Math.min(batchSize, totalSteps - s));
                statusCallback.accept(String.format("파동 시뮬레이션 %d%%...",
                        (int) (100.0 * Math.min(s + batchSize, totalSteps) / totalSteps)));
            }

            int pml = sim.pmlCells();
            int gNx = sim.gridNx(), gNy = sim.gridNy();

            // AP의 실제 EIRP 사용 (txPower + antennaGain)
            RadioConfig radio = templateAp.radios.get(params.fdtdBand);
            double eirpDbm = (radio != null)
                    ? radio.txPowerDbm + radio.antennaGain
                    : RadioConfig.FIXED_TX_POWER_DBM + RadioConfig.DEFAULT_ANTENNA_GAIN_DBI;

            // AP 근처에서 기준 전력(refPower) 측정
            double refPower = 1.0e-20;
            for (Point2D ap : apPositions) {
                int gx = (int) (ap.getX() / cellPx) + pml;
                int gy = (int) (ap.getY() / cellPx) + pml;
                for (int dx = -3; dx <= 3; dx++)
                    for (int dy = -3; dy <= 3; dy++) {
                        double p = sim.getPowerAt(gx + dx, gy + dy);
                        if (p > refPower) refPower = p;
                    }
            }

            // 각 측정점에서 RSSI 수집 + 커버 판정
            int covered = 0;
            List<Integer> uncovered = new ArrayList<>();
            List<Double> allRssi = new ArrayList<>(measurePoints.size());

            for (int i = 0; i < measurePoints.size(); i++) {
                Point2D mp = measurePoints.get(i);
                int gx = (int) (mp.getX() / cellPx) + pml;
                int gy = (int) (mp.getY() / cellPx) + pml;
                if (gx < 0 || gx >= gNx || gy < 0 || gy >= gNy) {
                    uncovered.add(i);
                    allRssi.add(-100.0);
                    continue;
                }
                double pathLossDb = 10.0 * Math.log10(
                        Math.max(sim.getPowerAt(gx, gy), 1e-30) / refPower);
                double rssiDbm = eirpDbm + pathLossDb;
                allRssi.add(rssiDbm);

                if (rssiDbm >= params.targetRssiDbm) {
                    covered++;
                } else {
                    uncovered.add(i);
                }
            }

            double pct = measurePoints.isEmpty() ? 0 : 100.0 * covered / measurePoints.size();
            FdtdSignalStats stats = computeSignalStats(allRssi);
            double[] rssiArray = allRssi.stream().mapToDouble(Double::doubleValue).toArray();
            return new FdtdValidationResult(pct, uncovered, stats, rssiArray);
        } catch (Exception e) {
            statusCallback.accept("FDTD 검증 실패: " + e.getMessage());
            return new FdtdValidationResult(-1, List.of(), null, new double[0]);
        }
    }

    // ── 약신호 + 미커버 인덱스 수집 ────────────────────────────────────────
    // 미커버 포인트 + 커버됐지만 하위 20% 약신호인 포인트를 합쳐서 반환
    private static List<Integer> collectWeakIndices(FdtdValidationResult vr, FdtdSignalStats stats) {
        Set<Integer> indices = new LinkedHashSet<>(vr.uncoveredIndices);

        if (vr.perPointRssi != null && stats != null) {
            // 하위 20% 문턱: pct5 ~ median 사이에서 약한 쪽 20%
            double weakThreshold = stats.pct5Rssi
                    + (stats.medianRssi - stats.pct5Rssi) * 0.4;
            for (int i = 0; i < vr.perPointRssi.length; i++) {
                if (vr.perPointRssi[i] < weakThreshold) {
                    indices.add(i);
                }
            }
        }

        return new ArrayList<>(indices);
    }

    // ── FDTD 피드백: 약신호 영역 방향으로 AP 위치 미세조정 ─────────────────
    private static List<Point2D> refineApPositions(List<Point2D> currentPositions,
                                                    List<Point2D> measurePoints,
                                                    List<Integer> uncoveredIndices,
                                                    boolean[] mask, int mw, int mh, int cellSize,
                                                    List<Wall> walls, int gridStep) {
        if (uncoveredIndices.isEmpty()) return currentPositions;

        // 미커버 측정점들의 좌표 수집
        List<Point2D> uncoveredPts = new ArrayList<>();
        for (int idx : uncoveredIndices) {
            if (idx >= 0 && idx < measurePoints.size())
                uncoveredPts.add(measurePoints.get(idx));
        }
        if (uncoveredPts.isEmpty()) return currentPositions;

        List<Point2D> refined = new ArrayList<>(currentPositions);

        for (int apIdx = 0; apIdx < refined.size(); apIdx++) {
            Point2D ap = refined.get(apIdx);

            // 이 AP에 가장 가까운 미커버 포인트들의 가중 중심 계산
            double pullX = 0, pullY = 0, totalWeight = 0;
            for (Point2D uc : uncoveredPts) {
                double dist = ap.distance(uc);
                double weight = 1.0 / (dist + 1.0); // 가까울수록 높은 가중치
                pullX += (uc.getX() - ap.getX()) * weight;
                pullY += (uc.getY() - ap.getY()) * weight;
                totalWeight += weight;
            }

            if (totalWeight <= 0) continue;
            pullX /= totalWeight;
            pullY /= totalWeight;

            // 이동량 제한 (gridStep의 2배까지)
            double pullDist = Math.hypot(pullX, pullY);
            double maxShift = gridStep * 2.0;
            if (pullDist > maxShift) {
                double scale = maxShift / pullDist;
                pullX *= scale;
                pullY *= scale;
            }

            int newX = (int) (ap.getX() + pullX);
            int newY = (int) (ap.getY() + pullY);

            // 마스크 범위 내인지 확인
            if (mask != null) {
                int mx = newX / cellSize, my = newY / cellSize;
                if (mx < 0 || mx >= mw || my < 0 || my >= mh || !mask[my * mw + mx]) {
                    continue; // 마스크 밖이면 이동 안 함
                }
            }

            // 벽 위가 아닌지 확인
            if (isOnWall(newX, newY, walls, 6.0)) continue;

            refined.set(apIdx, new Point2D(newX, newY));
        }

        return refined;
    }

    // ── 벽 래스터화 (Bresenham + 두께) ──────────────────────────────────────
    private static void rasterWall(boolean[] grid, int mw, int mh,
                                   int x0, int y0, int x1, int y1, int thick) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;

        while (true) {
            paintDisk(grid, mw, mh, x0, y0, thick);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private static void paintDisk(boolean[] grid, int mw, int mh, int cx, int cy, int r) {
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy <= r * r) {
                    int x = cx + dx, y = cy + dy;
                    if (x >= 0 && x < mw && y >= 0 && y < mh)
                        grid[y * mw + x] = true;
                }
            }
        }
    }

    private static int[] findNearestEmpty(boolean[] grid, int mw, int mh, int sx, int sy) {
        Deque<int[]> q = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        q.add(new int[]{sx, sy});
        visited.add(sy * mw + sx);
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (!grid[c[1] * mw + c[0]]) return c;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int nx = c[0]+d[0], ny = c[1]+d[1];
                if (nx < 0 || nx >= mw || ny < 0 || ny >= mh) continue;
                int idx = ny * mw + nx;
                if (visited.add(idx)) q.add(new int[]{nx, ny});
            }
        }
        return null;
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────
    private static boolean isOnWall(double px, double py, List<Wall> walls, double threshold) {
        for (Wall w : walls) {
            if (w == null) continue;
            if (ptSegDist(px, py, w.x1, w.y1, w.x2, w.y2) < threshold) return true;
        }
        return false;
    }

    private static double ptSegDist(double px, double py,
                                    double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) return Math.hypot(px - ax, py - ay);
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static WifiEnvironment cloneEnvWithAps(WifiEnvironment src, List<Point2D> apPositions, AP template) {
        WifiEnvironment e = new WifiEnvironment();
        e.setScaleMPerPx(src.getScaleMPerPx());
        e.setPathLossN(src.getPathLossN());
        e.setClientHeightM(src.getClientHeightM());
        e.getWalls().addAll(src.getWalls());
        e.getAps().addAll(src.getAps());
        int idx = 1;
        for (Point2D pos : apPositions) {
            AP ap = new AP();
            ap.name = "추천-" + idx++;
            ap.x = pos.getX(); ap.y = pos.getY();
            ap.heightM = template.heightM; ap.enabled = true;
            e.getAps().add(ap);
        }
        return e;
    }

    private static WifiEnvironment cloneEnvForScoring(WifiEnvironment src, AP testAp) {
        WifiEnvironment e = new WifiEnvironment();
        e.setScaleMPerPx(src.getScaleMPerPx());
        e.setPathLossN(src.getPathLossN());
        e.setClientHeightM(src.getClientHeightM());
        e.getWalls().addAll(src.getWalls());
        e.getAps().addAll(src.getAps());
        e.getAps().add(testAp);
        return e;
    }

    private static void updateCoveredSet(WifiEnvironment env, List<Point2D> measurePoints,
                                         double targetRssi, Set<Integer> coveredSet) {
        for (int i = 0; i < measurePoints.size(); i++) {
            if (coveredSet.contains(i)) continue;
            Point2D mp = measurePoints.get(i);
            if (env.sampleRssiAt((int) mp.getX(), (int) mp.getY()) >= targetRssi)
                coveredSet.add(i);
        }
    }

    /**
     * DPM Dijkstra 기반 커버리지 업데이트.
     *
     * <p>greedy 평가 루프에서 후보를 DPM으로 채점했으면 커버리지 추적도 DPM으로 해야
     * 일관성이 유지된다. Legacy {@code sampleRssiAt}을 사용하면 반사·회절 보정으로
     * RSSI가 과대추정되어 2번째 AP가 의미 없는 위치에 배치된다.</p>
     *
     * <p>단일 AP에서 1회 Dijkstra sweep → 모든 측정점의 경로손실을 O(N log N)에 계산.</p>
     */
    private static void updateCoveredSetDpm(Point2D apPos,
                                             int canvasW, int canvasH,
                                             double scaleMPerPx, List<Wall> walls,
                                             List<Point2D> measurePoints, Params params,
                                             Set<Integer> coveredSet,
                                             java.util.concurrent.atomic.AtomicLong dpmExp,
                                             java.util.concurrent.atomic.AtomicLong dpmPru,
                                             java.util.concurrent.atomic.AtomicLong dpmMs,
                                             java.util.concurrent.atomic.AtomicInteger dpmCnt) {
        DpmPathGrid grid = new DpmPathGrid(canvasW, canvasH, scaleMPerPx, walls, Band.GHZ_24);
        if (params.dpmGreedyEnabled) {
            grid.setPruningThresholdDb(params.pruningThresholdDb);
        } else {
            grid.setGreedyEnabled(false);
        }
        grid.runDijkstra(apPos.getX(), apPos.getY(), 2.4);
        dpmExp.addAndGet(grid.getExpandedNodeCount());
        dpmPru.addAndGet(grid.getPrunedNodeCount());
        dpmMs .addAndGet(grid.getElapsedMs());
        dpmCnt.incrementAndGet();

        // EIRP = 22 dBm (txPower 17 + antennaGain 5) — 평가와 동일한 상수
        final double eirpDbm = 22.0;
        for (int i = 0; i < measurePoints.size(); i++) {
            if (coveredSet.contains(i)) continue;
            Point2D mp = measurePoints.get(i);
            double pl = grid.getPathLossDb(mp.getX(), mp.getY());
            double rssi = eirpDbm - pl;
            if (rssi >= params.targetRssiDbm) coveredSet.add(i);
        }
    }

    // ── FDTD 신호 통계 계산 ──────────────────────────────────────────────────
    private static FdtdSignalStats computeSignalStats(List<Double> rssiValues) {
        if (rssiValues.isEmpty()) {
            return new FdtdSignalStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        int n = rssiValues.size();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        int strong = 0, good = 0, weak = 0, dead = 0;

        for (double r : rssiValues) {
            if (r < min) min = r;
            if (r > max) max = r;
            sum += r;
            if (r >= -55) strong++;
            else if (r >= -65) good++;
            else if (r >= -75) weak++;
            else dead++;
        }

        double avg = sum / n;

        // 표준편차
        double variance = 0;
        for (double r : rssiValues) {
            double diff = r - avg;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / n);

        // 중앙값 + 하위 5%
        double[] sorted = rssiValues.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double median = sorted[n / 2];
        int pct5Idx = Math.max(0, (int) (n * 0.05));
        double pct5 = sorted[pct5Idx];

        return new FdtdSignalStats(min, max, avg, median, stdDev, pct5,
                strong, good, weak, dead, n);
    }

    // ── 결과 요약 텍스트 ────────────────────────────────────────────────────
    private static String buildSummary(double rayCoverage, double fdtdCoverage,
                                       FdtdSignalStats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("커버율: %.0f%%", rayCoverage));
        if (fdtdCoverage >= 0) sb.append(String.format(" (FDTD: %.0f%%)", fdtdCoverage));

        if (stats != null) {
            sb.append(String.format("\n평균 %.0f dBm / 최약 %.0f dBm / 균일도 %.0f%%",
                    stats.avgRssi, stats.minRssi, stats.uniformityPercent()));
            sb.append(String.format("\n강함(%d) 양호(%d) 약함(%d) 사각(%d)",
                    stats.strongCount, stats.goodCount, stats.weakCount, stats.deadCount));
        }
        return sb.toString();
    }
}
