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
            int maskW, int maskH      // mask 해상도
    ) {
        public static Params defaults() {
            return new Params(2, -65.0, 25, 15, true, 300, Band.GHZ_5, null, 0, 0);
        }
    }

    // ── 결과 ─────────────────────────────────────────────────────────────────
    public record Result(
            List<Point2D> positions,
            double coveragePercent,
            double fdtdCoveragePercent,
            String summary
    ) {}

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

        // cellSize 역산 (mask → canvas 변환용)
        int cellSize = (mask != null && mw > 0) ? Math.max(1, canvasW / mw) : 1;

        // ── 1. 후보 위치 생성 ────────────────────────────────────────────────
        statusCallback.accept("후보 위치 생성 중...");
        List<Point2D> candidates = buildCandidateGrid(canvasW, canvasH,
                params.gridStepPx, walls, mask, mw, mh, cellSize);
        if (candidates.isEmpty()) {
            return new Result(List.of(), 0, -1, "유효한 후보 위치가 없습니다.");
        }

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

        for (int apIdx = 0; apIdx < params.apCount; apIdx++) {
            int apNum = apIdx + 1;
            statusCallback.accept("AP " + apNum + "/" + params.apCount + " 최적 위치 탐색 중...");

            WifiEnvironment tempEnv = cloneEnvWithAps(env, chosen, templateAp);
            CandidateScore best = evaluateCandidatesParallel(
                    tempEnv, candidates, measurePoints, params, coveredSet);

            if (best == null) break;
            chosen.add(new Point2D(best.px, best.py));

            WifiEnvironment withNew = cloneEnvWithAps(env, chosen, templateAp);
            updateCoveredSet(withNew, measurePoints, params.targetRssiDbm, coveredSet);
        }

        double rayCoverage = measurePoints.isEmpty() ? 0
                : 100.0 * coveredSet.size() / measurePoints.size();

        // ── 3. Phase 2: FDTD 검증 (옵션) ────────────────────────────────────
        double fdtdCoverage = -1;
        if (params.useFdtd && !chosen.isEmpty()) {
            statusCallback.accept("파동 시뮬레이션으로 검증 중...");
            fdtdCoverage = runFdtdValidation(env, chosen, templateAp,
                    canvasW, canvasH, measurePoints, params, statusCallback);
        }

        String summary = String.format("커버율: %.0f%%", rayCoverage);
        if (fdtdCoverage >= 0) summary += String.format(" (FDTD: %.0f%%)", fdtdCoverage);
        statusCallback.accept("완료! " + summary);

        return new Result(chosen, rayCoverage, fdtdCoverage, summary);
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
    private static CandidateScore evaluateCandidatesParallel(
            WifiEnvironment baseEnv, List<Point2D> candidates,
            List<Point2D> measurePoints, Params params,
            Set<Integer> alreadyCovered) {

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
                    if (rssi >= params.targetRssiDbm) newCovered++;
                    rssiSum += rssi;
                }

                double avgRssi = measurePoints.isEmpty() ? -100 : rssiSum / measurePoints.size();
                return new CandidateScore((int) cand.getX(), (int) cand.getY(),
                        newCovered * 100.0 + avgRssi);
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
                sim.step(Math.min(batchSize, totalSteps - s));
                statusCallback.accept(String.format("파동 시뮬레이션 %d%%...",
                        (int) (100.0 * Math.min(s + batchSize, totalSteps) / totalSteps)));
            }

            int pml = sim.pmlCells();
            int gNx = sim.gridNx(), gNy = sim.gridNy();

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

            int covered = 0;
            for (Point2D mp : measurePoints) {
                int gx = (int) (mp.getX() / cellPx) + pml;
                int gy = (int) (mp.getY() / cellPx) + pml;
                if (gx < 0 || gx >= gNx || gy < 0 || gy >= gNy) continue;
                double db = 10.0 * Math.log10(Math.max(sim.getPowerAt(gx, gy), 1e-30) / refPower);
                if (22.0 + db >= params.targetRssiDbm) covered++;
            }

            return measurePoints.isEmpty() ? 0 : 100.0 * covered / measurePoints.size();
        } catch (Exception e) {
            statusCallback.accept("FDTD 검증 실패: " + e.getMessage());
            return -1;
        }
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
}
