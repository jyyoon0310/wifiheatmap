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
 *   Phase 1 — Ray-cast 그리디 탐색: 후보 그리드에서 커버리지 점수가 가장 높은 위치를 순차 선택
 *   Phase 2 — FDTD 검증: 상위 후보만 FDTD로 정밀 검증하여 최종 순위 결정
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
            Band fdtdBand          // FDTD 대역 필터
    ) {
        public static Params defaults() {
            return new Params(2, -65.0, 25, 15, true, 300, Band.GHZ_5);
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
        double scale = env.getScaleMPerPx();
        double pln = env.getPathLossN();

        // ── 1. 후보 위치 생성 (벽 위 제외) ──────────────────────────────────
        statusCallback.accept("후보 위치 생성 중...");
        List<Point2D> candidates = buildCandidateGrid(canvasW, canvasH, params.gridStepPx, walls);
        if (candidates.isEmpty()) {
            return new Result(List.of(), 0, -1, "유효한 후보 위치가 없습니다.");
        }

        // ── 2. 측정 포인트 생성 ──────────────────────────────────────────────
        List<Point2D> measurePoints = buildMeasureGrid(canvasW, canvasH, params.sampleStepPx);

        // ── 3. Phase 1: 그리디 Ray-cast 탐색 ────────────────────────────────
        statusCallback.accept("Phase 1: Ray-cast 탐색 (" + candidates.size() + " 후보)...");

        List<Point2D> chosen = new ArrayList<>();
        Set<Integer> coveredSet = new HashSet<>(); // 이미 커버된 측정 포인트 인덱스

        // 임시 AP 템플릿
        AP templateAp = new AP();
        templateAp.heightM = 2.5;
        templateAp.name = "Rec";

        for (int apIdx = 0; apIdx < params.apCount; apIdx++) {
            int apNum = apIdx + 1;
            statusCallback.accept("Phase 1: AP " + apNum + "/" + params.apCount + " 탐색 중...");

            // 기존 선택된 AP들로 이미 커버되는 포인트 갱신
            WifiEnvironment tempEnv = cloneEnvWithAps(env, chosen, templateAp);

            // 각 후보에 대해 새 AP를 추가했을 때의 점수 계산
            CandidateScore best = evaluateCandidatesParallel(
                    tempEnv, candidates, measurePoints, params, coveredSet, apNum);

            if (best == null) break;
            Point2D bestPt = new Point2D(best.px, best.py);
            chosen.add(bestPt);

            // 커버된 포인트 갱신
            WifiEnvironment withNew = cloneEnvWithAps(env, chosen, templateAp);
            updateCoveredSet(withNew, measurePoints, params.targetRssiDbm, coveredSet);
        }

        double rayCoverage = measurePoints.isEmpty() ? 0
                : 100.0 * coveredSet.size() / measurePoints.size();

        // ── 4. Phase 2: FDTD 검증 (옵션) ────────────────────────────────────
        double fdtdCoverage = -1;
        if (params.useFdtd && !chosen.isEmpty()) {
            statusCallback.accept("Phase 2: FDTD 검증 중...");
            fdtdCoverage = runFdtdValidation(env, chosen, templateAp,
                    canvasW, canvasH, measurePoints, params, statusCallback);
        }

        String summary = String.format("추천 AP %d개 | Ray-cast 커버율 %.1f%%",
                chosen.size(), rayCoverage);
        if (fdtdCoverage >= 0) {
            summary += String.format(" | FDTD 커버율 %.1f%%", fdtdCoverage);
        }
        statusCallback.accept(summary);

        return new Result(chosen, rayCoverage, fdtdCoverage, summary);
    }

    // ── 후보 그리드 생성 (벽 위 제외) ────────────────────────────────────────
    private static List<Point2D> buildCandidateGrid(int w, int h, int step,
                                                    List<Wall> walls) {
        int margin = step;
        List<Point2D> pts = new ArrayList<>();
        for (int x = margin; x < w - margin; x += step) {
            for (int y = margin; y < h - margin; y += step) {
                if (!isOnWall(x, y, walls, 6.0)) {
                    pts.add(new Point2D(x, y));
                }
            }
        }
        return pts;
    }

    // ── 측정 포인트 그리드 ───────────────────────────────────────────────────
    private static List<Point2D> buildMeasureGrid(int w, int h, int step) {
        List<Point2D> pts = new ArrayList<>();
        for (int x = step; x < w - step; x += step) {
            for (int y = step; y < h - step; y += step) {
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

        // 후보별로 독립적이므로 병렬화
        ForkJoinPool pool = ForkJoinPool.commonPool();
        List<ForkJoinTask<CandidateScore>> tasks = new ArrayList<>();

        for (Point2D cand : candidates) {
            tasks.add(pool.submit(() -> {
                // 이 후보에 AP를 추가했을 때 새로 커버되는 포인트 수 계산
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

                // 점수: 새로 커버한 수 * 100 + 평균 RSSI (커버 우선, RSSI로 타이브레이크)
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

            // FDTD 셀 크기는 실시간과 동일하게 자동 결정
            int cellPx = Math.max(2, Math.min(8,
                    (int) Math.ceil(Math.sqrt((double) canvasW * canvasH / 200_000.0))));

            FdtdWaveSimulator sim = new FdtdWaveSimulator(
                    fdtdEnv, canvasW, canvasH, cellPx, params.fdtdBand);

            // 시뮬레이션 실행
            int totalSteps = params.fdtdSteps;
            int batchSize = 50;
            for (int s = 0; s < totalSteps; s += batchSize) {
                int stepsThisBatch = Math.min(batchSize, totalSteps - s);
                sim.step(stepsThisBatch);
                statusCallback.accept(String.format("Phase 2: FDTD %d/%d 스텝...",
                        Math.min(s + batchSize, totalSteps), totalSteps));
            }

            // power 격자에서 측정 포인트 샘플링
            int pml = sim.pmlCells();
            int gNx = sim.gridNx();
            int gNy = sim.gridNy();

            // 소스 근처 최대값 찾기 (정규화 기준)
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

            // 커버리지 계산
            int covered = 0;
            for (Point2D mp : measurePoints) {
                int gx = (int) (mp.getX() / cellPx) + pml;
                int gy = (int) (mp.getY() / cellPx) + pml;
                if (gx < 0 || gx >= gNx || gy < 0 || gy >= gNy) continue;

                double power = sim.getPowerAt(gx, gy);
                // power를 dB로 변환 (소스 대비 상대값)
                double db = 10.0 * Math.log10(Math.max(power, 1e-30) / refPower);
                // 상대 dB → 절대 RSSI 근사 (소스 = txPower + gain ≈ 22 dBm)
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

    /** 벽 위 여부 판단 */
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

    /** 기존 환경에 임시 AP들을 추가한 WifiEnvironment 복제 */
    private static WifiEnvironment cloneEnvWithAps(WifiEnvironment src, List<Point2D> apPositions, AP template) {
        WifiEnvironment e = new WifiEnvironment();
        e.setScaleMPerPx(src.getScaleMPerPx());
        e.setPathLossN(src.getPathLossN());
        e.setClientHeightM(src.getClientHeightM());
        e.getWalls().addAll(src.getWalls());
        // 기존 AP 유지
        e.getAps().addAll(src.getAps());
        // 추천 AP 추가
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

    /** 점수 계산용 경량 복제 (기존 AP + 테스트 AP 1개) */
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

    /** 커버된 측정 포인트 집합 갱신 */
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
