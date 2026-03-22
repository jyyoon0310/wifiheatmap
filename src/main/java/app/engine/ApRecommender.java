package app.engine;

import app.model.*;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;

/**
 * AP 위치 추천 엔진.
 *
 * 2단계 접근:
 *   Phase 1 — Ray-cast 그리디 탐색: 후보 그리드에서 커버리지 점수가 가장 높은 위치를 순차 선택
 *   Phase 2 — FDTD 검증: 상위 후보만 FDTD로 정밀 검증하여 최종 순위 결정
 *
 * bounds가 지정되면 후보/측정 포인트 모두 해당 영역 안으로 제한됨.
 */
public class ApRecommender {

    // ── 파라미터 ─────────────────────────────────────────────────────────────
    public record Params(
            int apCount,           // 추천 AP 개수 (1~5)
            double targetRssiDbm,  // 목표 RSSI 임계값 (e.g. -65)
            int gridStepPx,        // 후보 그리드 간격 (px)
            int sampleStepPx,      // 측정 포인트 간격 (px)
            boolean useFdtd,       // Phase 2 FDTD 검증 사용 여부
            int fdtdSteps,         // FDTD 시뮬레이션 스텝 수
            Band fdtdBand,         // FDTD 대역 필터
            Rectangle2D bounds     // 커버 영역 (null이면 전체 캔버스)
    ) {
        public static Params defaults() {
            return new Params(2, -65.0, 25, 15, true, 300, Band.GHZ_5, null);
        }
    }

    // ── 결과 ─────────────────────────────────────────────────────────────────
    public record Result(
            List<Point2D> positions,     // 추천된 AP 위치 (px 좌표)
            double coveragePercent,      // Ray-cast 기반 커버율
            double fdtdCoveragePercent,  // FDTD 기반 커버율 (-1 if unused)
            String summary              // 요약 텍스트
    ) {}

    // ── 후보 점수 ────────────────────────────────────────────────────────────
    private record CandidateScore(int px, int py, double score) {}

    // ── 메인 실행 ────────────────────────────────────────────────────────────
    public static Result recommend(WifiEnvironment env, int canvasW, int canvasH,
                                   Params params, Consumer<String> statusCallback) {
        if (statusCallback == null) statusCallback = s -> {};

        List<Wall> walls = new ArrayList<>(env.getWalls());
        Rectangle2D bounds = params.bounds;

        // ── 1. 후보 위치 생성 (커버 영역 + 벽 위 제외) ──────────────────────
        statusCallback.accept("후보 위치 생성 중...");
        List<Point2D> candidates = buildCandidateGrid(canvasW, canvasH,
                params.gridStepPx, walls, bounds);
        if (candidates.isEmpty()) {
            return new Result(List.of(), 0, -1, "유효한 후보 위치가 없습니다.");
        }

        // ── 2. 측정 포인트 생성 (커버 영역 내) ───────────────────────────────
        List<Point2D> measurePoints = buildMeasureGrid(canvasW, canvasH,
                params.sampleStepPx, bounds);

        statusCallback.accept(String.format("후보 %d개, 측정점 %d개",
                candidates.size(), measurePoints.size()));

        // ── 3. Phase 1: 그리디 Ray-cast 탐색 ────────────────────────────────
        List<Point2D> chosen = new ArrayList<>();
        Set<Integer> coveredSet = new HashSet<>();

        AP templateAp = new AP();
        templateAp.heightM = 2.5;
        templateAp.name = "Rec";

        for (int apIdx = 0; apIdx < params.apCount; apIdx++) {
            int apNum = apIdx + 1;
            statusCallback.accept("AP " + apNum + "/" + params.apCount + " 최적 위치 탐색 중...");

            WifiEnvironment tempEnv = cloneEnvWithAps(env, chosen, templateAp);

            CandidateScore best = evaluateCandidatesParallel(
                    tempEnv, candidates, measurePoints, params, coveredSet, apNum);

            if (best == null) break;
            chosen.add(new Point2D(best.px, best.py));

            WifiEnvironment withNew = cloneEnvWithAps(env, chosen, templateAp);
            updateCoveredSet(withNew, measurePoints, params.targetRssiDbm, coveredSet);
        }

        double rayCoverage = measurePoints.isEmpty() ? 0
                : 100.0 * coveredSet.size() / measurePoints.size();

        // ── 4. Phase 2: FDTD 검증 (옵션) ────────────────────────────────────
        double fdtdCoverage = -1;
        if (params.useFdtd && !chosen.isEmpty()) {
            statusCallback.accept("파동 시뮬레이션으로 검증 중...");
            fdtdCoverage = runFdtdValidation(env, chosen, templateAp,
                    canvasW, canvasH, measurePoints, params, statusCallback);
        }

        String summary = String.format("커버율: %.0f%%", rayCoverage);
        if (fdtdCoverage >= 0) {
            summary += String.format(" (FDTD: %.0f%%)", fdtdCoverage);
        }
        statusCallback.accept("완료! " + summary);

        return new Result(chosen, rayCoverage, fdtdCoverage, summary);
    }

    // ── 후보 그리드 생성 (커버 영역 + 벽 위 제외) ───────────────────────────
    private static List<Point2D> buildCandidateGrid(int w, int h, int step,
                                                    List<Wall> walls,
                                                    Rectangle2D bounds) {
        int x0 = (bounds != null) ? (int) bounds.getMinX() : step;
        int y0 = (bounds != null) ? (int) bounds.getMinY() : step;
        int x1 = (bounds != null) ? (int) bounds.getMaxX() : w - step;
        int y1 = (bounds != null) ? (int) bounds.getMaxY() : h - step;

        // 경계에서 약간 안쪽으로
        x0 = Math.max(x0 + step / 2, step);
        y0 = Math.max(y0 + step / 2, step);

        List<Point2D> pts = new ArrayList<>();
        for (int x = x0; x < x1; x += step) {
            for (int y = y0; y < y1; y += step) {
                if (!isOnWall(x, y, walls, 6.0)) {
                    pts.add(new Point2D(x, y));
                }
            }
        }
        return pts;
    }

    // ── 측정 포인트 그리드 (커버 영역 내) ────────────────────────────────────
    private static List<Point2D> buildMeasureGrid(int w, int h, int step,
                                                  Rectangle2D bounds) {
        int x0 = (bounds != null) ? (int) bounds.getMinX() : step;
        int y0 = (bounds != null) ? (int) bounds.getMinY() : step;
        int x1 = (bounds != null) ? (int) bounds.getMaxX() : w - step;
        int y1 = (bounds != null) ? (int) bounds.getMaxY() : h - step;

        List<Point2D> pts = new ArrayList<>();
        for (int x = x0; x < x1; x += step) {
            for (int y = y0; y < y1; y += step) {
                pts.add(new Point2D(x, y));
            }
        }
        return pts;
    }

    // ── 후보 병렬 평가 ──────────────────────────────────────────────────────
    private static CandidateScore evaluateCandidatesParallel(
            WifiEnvironment baseEnv, List<Point2D> candidates,
            List<Point2D> measurePoints, Params params,
            Set<Integer> alreadyCovered, int apNum) {

        ForkJoinPool pool = ForkJoinPool.commonPool();
        List<ForkJoinTask<CandidateScore>> tasks = new ArrayList<>();

        for (Point2D cand : candidates) {
            tasks.add(pool.submit(() -> {
                AP testAp = new AP();
                testAp.x = cand.getX();
                testAp.y = cand.getY();
                testAp.heightM = 2.5;
                testAp.name = "Test";

                WifiEnvironment testEnv = cloneEnvForScoring(baseEnv, testAp);

                int newCovered = 0;
                double rssiSum = 0;
                for (int i = 0; i < measurePoints.size(); i++) {
                    if (alreadyCovered.contains(i)) continue;
                    Point2D mp = measurePoints.get(i);
                    double rssi = testEnv.sampleRssiAt((int) mp.getX(), (int) mp.getY());
                    if (rssi >= params.targetRssiDbm) {
                        newCovered++;
                    }
                    rssiSum += rssi;
                }

                double avgRssi = measurePoints.isEmpty() ? -100 : rssiSum / measurePoints.size();
                double score = newCovered * 100.0 + avgRssi;
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

    // ── FDTD 검증 ───────────────────────────────────────────────────────────
    private static double runFdtdValidation(WifiEnvironment env, List<Point2D> apPositions,
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
            int batchSize = 50;
            for (int s = 0; s < totalSteps; s += batchSize) {
                int stepsThisBatch = Math.min(batchSize, totalSteps - s);
                sim.step(stepsThisBatch);
                int done = Math.min(s + batchSize, totalSteps);
                statusCallback.accept(String.format("파동 시뮬레이션 %d%%...",
                        (int) (100.0 * done / totalSteps)));
            }

            int pml = sim.pmlCells();
            int gNx = sim.gridNx();
            int gNy = sim.gridNy();

            double refPower = 1.0e-20;
            for (Point2D ap : apPositions) {
                int gx = (int) (ap.getX() / cellPx) + pml;
                int gy = (int) (ap.getY() / cellPx) + pml;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        double p = sim.getPowerAt(gx + dx, gy + dy);
                        if (p > refPower) refPower = p;
                    }
                }
            }

            int covered = 0;
            for (Point2D mp : measurePoints) {
                int gx = (int) (mp.getX() / cellPx) + pml;
                int gy = (int) (mp.getY() / cellPx) + pml;
                if (gx < 0 || gx >= gNx || gy < 0 || gy >= gNy) continue;

                double power = sim.getPowerAt(gx, gy);
                double db = 10.0 * Math.log10(Math.max(power, 1e-30) / refPower);
                double approxRssi = 22.0 + db;
                if (approxRssi >= params.targetRssiDbm) covered++;
            }

            return measurePoints.isEmpty() ? 0
                    : 100.0 * covered / measurePoints.size();

        } catch (Exception e) {
            statusCallback.accept("FDTD 검증 실패: " + e.getMessage());
            return -1;
        }
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────

    private static boolean isOnWall(double px, double py, List<Wall> walls, double threshold) {
        for (Wall w : walls) {
            if (w == null) continue;
            double d = ptSegDist(px, py, w.x1, w.y1, w.x2, w.y2);
            if (d < threshold) return true;
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
            ap.x = pos.getX();
            ap.y = pos.getY();
            ap.heightM = template.heightM;
            ap.enabled = true;
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
            double rssi = env.sampleRssiAt((int) mp.getX(), (int) mp.getY());
            if (rssi >= targetRssi) coveredSet.add(i);
        }
    }
}
