package app.engine;

import app.model.RadioConfig;
import app.model.*;
import javafx.geometry.Point2D;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * WifiEnvironment를 기반으로 히트맵 이미지를 생성하는 클래스.
 * - LOS(직진) + 1차 반사 + 1차 회절(코너)을 전력(mW) 합산
 * - 밴드는 2.4/5 중 활성 라디오에 대해 계산 후 "최강 RSSI" 선택
 */
public class HeatmapGenerator {

    /** 백그라운드 스레드에서 계산한 결과 (int[] ARGB 버퍼).
     *  WritableImage는 JavaFX Application Thread에서만 생성해야 하므로,
     *  호출자가 이 결과를 받아 FX Thread에서 이미지로 변환한다. */
    public static final class HeatmapResult {
        public final int[] argbPixels;
        public final int width;
        public final int height;
        public final int smoothRadiusPx;
        public final boolean usedGpu;
        public final boolean gpuFallback;

        public HeatmapResult(int[] argbPixels, int width, int height, int smoothRadiusPx,
                             boolean usedGpu, boolean gpuFallback) {
            this.argbPixels = argbPixels;
            this.width = width;
            this.height = height;
            this.smoothRadiusPx = smoothRadiusPx;
            this.usedGpu = usedGpu;
            this.gpuFallback = gpuFallback;
        }
    }

    private final WifiEnvironment env;
    private static final GpuHeatmapSolver GPU_SOLVER = loadGpuSolver();
    private boolean usedGpuLastRun = false;
    private boolean gpuFallbackLastRun = false;

    // ===== Reflection(1-bounce) 튜닝 =====
    private static final int MAX_REFLECTION_WALLS = 12;
    private static final double REFLECTION_RADIUS_M = 15.0;
    private static final double REFLECTION_LOS_RATIO_CUTOFF = 2.5;

    // ===== Diffraction(코너) 튜닝 =====
    private static final int MAX_DIFFRACTION_CORNERS = 16;
    private static final double DIFFRACTION_RADIUS_M = 18.0;  // 12→18m: 발코니 코너까지 닿도록 확대
    private static final double DIFFRACTION_LOS_RATIO_CUTOFF = 2.8;
    private static final double SECONDARY_PATH_RELATIVE_CUTOFF_DB = 10.0;
    // 그늘 영역에서 반사/회절이 완전히 잘리지 않도록 절대 하한선 설정
    // LOS가 매우 약한 경우에도 -100 dBm 이상인 반사/회절은 합산에 참여
    private static final double MIN_SECONDARY_RSSI_DBM = -100.0;

    // ===== 2차 회절(corner → corner) 튜닝 =====
    // 복도를 돌아 드레스룸/발코니 같은 깊은 그늘 영역에 신호를 전달하는 경로
    private static final int MAX_DOUBLE_DIFFRACTION_PAIRS = 8;  // 계산량 제어 (쌍의 수)
    // 2차 회절 전용 코너풀: 1차(18m)보다 넓은 반경으로 발코니 끝 코너까지 포함
    private static final int MAX_DOUBLE_DIFFRACTION_CORNERS = 24;
    private static final double DOUBLE_DIFFRACTION_CORNER_RADIUS_M = 22.0; // 코너풀 탐색 반경
    private static final double DOUBLE_DIFFRACTION_LOS_RATIO_CUTOFF = 3.5; // 직선 대비 최대 경로 배율

    // ===== Adaptive Sampling 튜닝 =====
    /** 인접 그리드 꼭짓점 간 dBm 차이가 이 값을 초과하면 세분화 */
    private static final double REFINE_THRESHOLD_DB = 6.0;
    /** 최대 세분화 깊이 (0=코스 그리드만, 3=최소 셀 = gridStep/8) */
    private static final int MAX_REFINE_DEPTH = 3;

    private static final class WallCand {
        final Wall wall;
        final double score; // 작을수록 더 가까움
        WallCand(Wall wall, double score) { this.wall = wall; this.score = score; }
    }

    private static final class CornerCand {
        final Wall wall;
        final Point2D corner;
        final double score; // 작을수록 더 가까움
        CornerCand(Wall wall, Point2D corner, double score) { this.wall = wall; this.corner = corner; this.score = score; }
    }

    public HeatmapGenerator(WifiEnvironment env) {
        this.env = env;
    }

    public HeatmapResult generate(int width,
                                   int height,
                                   int gridStepPx,
                                   double legendMinDbm,
                                   double legendMaxDbm,
                                   int smoothRadiusPx,
                                   Consumer<Double> progressCallback) {
        // 현재 히트맵은 CPU 경로만 사용한다.
        usedGpuLastRun = false;
        gpuFallbackLastRun = false;
        return generateCpu(width, height, gridStepPx, legendMinDbm, legendMaxDbm, smoothRadiusPx, progressCallback);
    }

    public boolean usedGpuLastRun() {
        return usedGpuLastRun;
    }

    public boolean gpuFallbackLastRun() {
        return gpuFallbackLastRun;
    }

    public static boolean isGpuAvailable() {
        return GPU_SOLVER != null;
    }

    private HeatmapResult generateCpu(int width,
                                      int height,
                                      int gridStepPx,
                                      double legendMinDbm,
                                      double legendMaxDbm,
                                      int smoothRadiusPx,
                                      Consumer<Double> progressCallback) {

        // 환경에서 현재 상태 가져오기 (lambda 캡처를 위해 로컬 변수로 추출)
        List<AP> aps = new ArrayList<>(env.getAps());
        List<Wall> walls = new ArrayList<>(env.getWalls());
        double scaleMPerPx = env.getScaleMPerPx();
        double pathLossN = env.getPathLossN();
        double minDistanceM = env.getMinDistanceM();
        double clientHeightM = env.getClientHeightM();

        // 1) 활성 AP 필터링
        List<AP> enabled = new ArrayList<>();
        for (AP ap : aps) {
            if (ap != null && ap.enabled) enabled.add(ap);
        }

        // ====================================================================
        //  Phase 1: 코스 그리드 샘플링 — 꼭짓점 단위로 dBm 값 계산
        // ====================================================================
        int gw = (int) Math.ceil((double) width / gridStepPx) + 1;  // 꼭짓점 수 (가로)
        int gh = (int) Math.ceil((double) height / gridStepPx) + 1; // 꼭짓점 수 (세로)

        // 코스 그리드 dBm 값 (병렬 계산)
        double[] coarseDbm = new double[gw * gh];
        int totalCoarse = gw * gh;
        AtomicInteger completedCoarse = new AtomicInteger(0);
        int coarseProgressInterval = Math.max(1, totalCoarse / 10);

        IntStream.range(0, totalCoarse).parallel().forEach(idx -> {
            int gx = idx % gw;
            int gy = idx / gw;
            int px = Math.min(width - 1, gx * gridStepPx);
            int py = Math.min(height - 1, gy * gridStepPx);
            coarseDbm[idx] = computeRssiAt(px, py, enabled, walls, scaleMPerPx, pathLossN, minDistanceM, clientHeightM);

            // 진행률: Phase 1 = 0~40%
            int done = completedCoarse.incrementAndGet();
            if (progressCallback != null && done % coarseProgressInterval == 0) {
                progressCallback.accept(0.4 * done / totalCoarse);
            }
        });

        // ====================================================================
        //  Phase 2: 적응형 세분화 — gradient 큰 셀만 재귀적으로 세분화
        // ====================================================================
        // 세분화 샘플은 ConcurrentHashMap에 저장 (불규칙 격자)
        Map<Long, Double> sampleMap = new ConcurrentHashMap<>(totalCoarse * 2);
        // 코스 그리드 결과를 sampleMap에 등록
        for (int idx = 0; idx < totalCoarse; idx++) {
            int gx = idx % gw;
            int gy = idx / gw;
            int px = Math.min(width - 1, gx * gridStepPx);
            int py = Math.min(height - 1, gy * gridStepPx);
            sampleMap.put(packCoord(px, py), coarseDbm[idx]);
        }

        // 세분화 대상 셀 수집 (병렬 안전을 위해 먼저 리스트 생성)
        record RefineCell(int x0, int y0, int x1, int y1, int depth) {}
        List<RefineCell> refineCells = new ArrayList<>();
        for (int gy = 0; gy < gh - 1; gy++) {
            for (int gx = 0; gx < gw - 1; gx++) {
                double tl = coarseDbm[gy * gw + gx];
                double tr = coarseDbm[gy * gw + gx + 1];
                double bl = coarseDbm[(gy + 1) * gw + gx];
                double br = coarseDbm[(gy + 1) * gw + gx + 1];
                double maxDiff = Math.max(Math.max(Math.abs(tl - tr), Math.abs(tl - bl)),
                                 Math.max(Math.max(Math.abs(tr - br), Math.abs(bl - br)),
                                 Math.max(Math.abs(tl - br), Math.abs(tr - bl))));
                if (maxDiff > REFINE_THRESHOLD_DB) {
                    int px0 = Math.min(width - 1, gx * gridStepPx);
                    int py0 = Math.min(height - 1, gy * gridStepPx);
                    int px1 = Math.min(width - 1, (gx + 1) * gridStepPx);
                    int py1 = Math.min(height - 1, (gy + 1) * gridStepPx);
                    refineCells.add(new RefineCell(px0, py0, px1, py1, 1));
                }
            }
        }

        // 재귀적 세분화 (BFS, 최대 MAX_REFINE_DEPTH 깊이)
        while (!refineCells.isEmpty()) {
            List<RefineCell> nextLevel = new ArrayList<>();
            // 세분화 셀들의 중간점 계산을 병렬로 수행
            refineCells.parallelStream().forEach(cell -> {
                int mx = (cell.x0 + cell.x1) / 2;
                int my = (cell.y0 + cell.y1) / 2;
                if (mx == cell.x0 || my == cell.y0) return; // 더 이상 세분화 불가

                // 5개 중간점 계산 (십자형): center, top-mid, bottom-mid, left-mid, right-mid
                int[][] midPoints = {{mx, my}, {mx, cell.y0}, {mx, cell.y1}, {cell.x0, my}, {cell.x1, my}};
                for (int[] mp : midPoints) {
                    long key = packCoord(mp[0], mp[1]);
                    if (!sampleMap.containsKey(key)) {
                        double dbm = computeRssiAt(mp[0], mp[1], enabled, walls, scaleMPerPx, pathLossN, minDistanceM, clientHeightM);
                        sampleMap.put(key, dbm);
                    }
                }
            });

            if (refineCells.get(0).depth >= MAX_REFINE_DEPTH) break;

            // 다음 레벨 세분화 대상 수집
            for (RefineCell cell : refineCells) {
                int mx = (cell.x0 + cell.x1) / 2;
                int my = (cell.y0 + cell.y1) / 2;
                if (mx == cell.x0 || my == cell.y0) continue;

                // 4개 서브셀 검사
                int[][] subCells = {
                    {cell.x0, cell.y0, mx, my},
                    {mx, cell.y0, cell.x1, my},
                    {cell.x0, my, mx, cell.y1},
                    {mx, my, cell.x1, cell.y1}
                };
                for (int[] sc : subCells) {
                    Double vTL = sampleMap.get(packCoord(sc[0], sc[1]));
                    Double vTR = sampleMap.get(packCoord(sc[2], sc[1]));
                    Double vBL = sampleMap.get(packCoord(sc[0], sc[3]));
                    Double vBR = sampleMap.get(packCoord(sc[2], sc[3]));
                    if (vTL == null || vTR == null || vBL == null || vBR == null) continue;
                    double maxDiff = Math.max(Math.max(Math.abs(vTL - vTR), Math.abs(vTL - vBL)),
                                     Math.max(Math.max(Math.abs(vTR - vBR), Math.abs(vBL - vBR)),
                                     Math.max(Math.abs(vTL - vBR), Math.abs(vTR - vBL))));
                    if (maxDiff > REFINE_THRESHOLD_DB) {
                        nextLevel.add(new RefineCell(sc[0], sc[1], sc[2], sc[3], cell.depth + 1));
                    }
                }
            }
            refineCells = nextLevel;
        }

        // 진행률: Phase 2 완료 = 50%
        if (progressCallback != null) {
            progressCallback.accept(0.5);
        }

        // ====================================================================
        //  Phase 3: Bicubic 보간 → 픽셀별 dBm → 색상 변환
        // ====================================================================
        int[] argbPixels = new int[width * height];
        int totalRows = height;
        AtomicInteger completedRows = new AtomicInteger(0);
        int rowProgressInterval = Math.max(1, totalRows / 20);

        IntStream.range(0, height).parallel().forEach(py -> {
            for (int px = 0; px < width; px++) {
                // 이 픽셀이 속한 코스 그리드 셀 찾기
                int gx = px / gridStepPx;
                int gy = py / gridStepPx;
                gx = Math.min(gx, gw - 2);
                gy = Math.min(gy, gh - 2);

                // 코스 그리드 꼭짓점 좌표
                int cx0 = Math.min(width - 1, gx * gridStepPx);
                int cy0 = Math.min(height - 1, gy * gridStepPx);
                int cx1 = Math.min(width - 1, (gx + 1) * gridStepPx);
                int cy1 = Math.min(height - 1, (gy + 1) * gridStepPx);

                // 이 셀에 세분화된 샘플이 있는지 확인 (중간점)
                int mx = (cx0 + cx1) / 2;
                int my = (cy0 + cy1) / 2;
                boolean hasRefinement = sampleMap.containsKey(packCoord(mx, my));

                double dbm;
                if (hasRefinement) {
                    // 세분화된 영역: 해당 서브셀에서 Bilinear 보간
                    // 서브셀 결정
                    int subX0, subY0, subX1, subY1;
                    if (px <= mx) { subX0 = cx0; subX1 = mx; }
                    else          { subX0 = mx;  subX1 = cx1; }
                    if (py <= my) { subY0 = cy0; subY1 = my; }
                    else          { subY0 = my;  subY1 = cy1; }

                    // 4개 서브셀 꼭짓점 값 조회
                    double v00 = sampleOrNearest(sampleMap, subX0, subY0, coarseDbm, gw, gh, gridStepPx, width, height);
                    double v10 = sampleOrNearest(sampleMap, subX1, subY0, coarseDbm, gw, gh, gridStepPx, width, height);
                    double v01 = sampleOrNearest(sampleMap, subX0, subY1, coarseDbm, gw, gh, gridStepPx, width, height);
                    double v11 = sampleOrNearest(sampleMap, subX1, subY1, coarseDbm, gw, gh, gridStepPx, width, height);

                    double tx = (subX1 > subX0) ? (double)(px - subX0) / (subX1 - subX0) : 0.0;
                    double ty = (subY1 > subY0) ? (double)(py - subY0) / (subY1 - subY0) : 0.0;
                    tx = Math.max(0.0, Math.min(1.0, tx));
                    ty = Math.max(0.0, Math.min(1.0, ty));
                    dbm = bilinearInterp(v00, v10, v01, v11, tx, ty);
                } else {
                    // 코스 그리드: Bicubic (Catmull-Rom) 보간
                    // 4×4 이웃 꼭짓점 값 수집
                    double[][] grid4x4 = new double[4][4];
                    for (int dy = -1; dy <= 2; dy++) {
                        for (int dx = -1; dx <= 2; dx++) {
                            int sgx = Math.max(0, Math.min(gw - 1, gx + dx));
                            int sgy = Math.max(0, Math.min(gh - 1, gy + dy));
                            grid4x4[dy + 1][dx + 1] = coarseDbm[sgy * gw + sgx];
                        }
                    }

                    double tx = (cx1 > cx0) ? (double)(px - cx0) / (cx1 - cx0) : 0.0;
                    double ty = (cy1 > cy0) ? (double)(py - cy0) / (cy1 - cy0) : 0.0;
                    tx = Math.max(0.0, Math.min(1.0, tx));
                    ty = Math.max(0.0, Math.min(1.0, ty));
                    dbm = WifiMath.bicubicCatmullRom(grid4x4, tx, ty);
                }

                Color c = WifiMath.rssiToColor(dbm, legendMinDbm, legendMaxDbm);
                argbPixels[py * width + px] = colorToArgb(c);
            }

            // 진행률: Phase 3 = 50~100%
            int done = completedRows.incrementAndGet();
            if (progressCallback != null && done % rowProgressInterval == 0) {
                progressCallback.accept(0.5 + 0.5 * done / totalRows);
            }
        });

        // 최종 100% 콜백
        if (progressCallback != null) {
            progressCallback.accept(1.0);
        }

        // WritableImage는 JavaFX Thread에서만 생성 가능 → int[] 버퍼만 반환
        // smoothRadiusPx는 0으로 전달 (Bicubic 보간이 boxBlur를 대체)
        return new HeatmapResult(argbPixels, width, height, 0,
                usedGpuLastRun, gpuFallbackLastRun);
    }

    /** sampleMap에서 값을 조회하고, 없으면 코스 그리드에서 최근접 값 반환 */
    private static double sampleOrNearest(Map<Long, Double> sampleMap, int px, int py,
                                           double[] coarseDbm, int gw, int gh,
                                           int gridStepPx, int width, int height) {
        Double v = sampleMap.get(packCoord(px, py));
        if (v != null) return v;
        // 코스 그리드에서 가장 가까운 꼭짓점 조회
        int gx = Math.max(0, Math.min(gw - 1, Math.round((float) px / gridStepPx)));
        int gy = Math.max(0, Math.min(gh - 1, Math.round((float) py / gridStepPx)));
        return coarseDbm[gy * gw + gx];
    }

    /** Bilinear 보간 */
    private static double bilinearInterp(double v00, double v10, double v01, double v11, double tx, double ty) {
        double top = v00 + (v10 - v00) * tx;
        double bot = v01 + (v11 - v01) * tx;
        return top + (bot - top) * ty;
    }

    /** 좌표를 long 키로 변환 (ConcurrentHashMap용) */
    private static long packCoord(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /**
     * 단일 픽셀 위치 (px, py)에서의 strongest RSSI(dBm)를 계산.
     * 기존 generateCpu() 내부 루프에서 추출한 로직.
     */
    private double computeRssiAt(int px, int py,
                                  List<AP> enabled, List<Wall> walls,
                                  double scaleMPerPx, double pathLossN,
                                  double minDistanceM, double clientHeightM) {
        double strongest = -1e9;

        for (AP ap : enabled) {
            Point2D apPt = new Point2D(ap.x, ap.y);
            Point2D rxPt = new Point2D(px, py);

            // 1) 3D 거리(m): 2D 수평거리 + 높이차
            double d2dM = apPt.distance(rxPt) * scaleMPerPx;
            double dzM = ap.heightM - clientHeightM;
            double dM = Math.sqrt(d2dM * d2dM + dzM * dzM);
            dM = Math.max(dM, minDistanceM);

            double bestRssi = -1e9;

            // ===== 반사 후보 벽 =====
            List<WallCand> wallCands = new ArrayList<>();
            for (Wall w : walls) {
                if (w == null) continue;
                Point2D w1 = new Point2D(w.x1, w.y1);
                Point2D w2 = new Point2D(w.x2, w.y2);
                Point2D cpAp = WifiMath.closestPointOnSegment(apPt, w1, w2);
                Point2D cpRx = WifiMath.closestPointOnSegment(rxPt, w1, w2);
                double dApM = apPt.distance(cpAp) * scaleMPerPx;
                double dRxM = rxPt.distance(cpRx) * scaleMPerPx;
                double minM = Math.min(dApM, dRxM);
                if (minM <= REFLECTION_RADIUS_M) {
                    wallCands.add(new WallCand(w, minM));
                }
            }
            wallCands.sort(Comparator.comparingDouble(a -> a.score));
            if (wallCands.size() > MAX_REFLECTION_WALLS) {
                wallCands = wallCands.subList(0, MAX_REFLECTION_WALLS);
            }

            // ===== 회절 코너 후보 =====
            List<CornerCand> cornerRank = new ArrayList<>();
            for (Wall w : walls) {
                if (w == null) continue;
                Point2D c1 = new Point2D(w.x1, w.y1);
                Point2D c2 = new Point2D(w.x2, w.y2);
                double c1Score = Math.min(apPt.distance(c1) * scaleMPerPx, rxPt.distance(c1) * scaleMPerPx);
                double c2Score = Math.min(apPt.distance(c2) * scaleMPerPx, rxPt.distance(c2) * scaleMPerPx);
                if (c1Score <= DIFFRACTION_RADIUS_M) cornerRank.add(new CornerCand(w, c1, c1Score));
                if (c2Score <= DIFFRACTION_RADIUS_M) cornerRank.add(new CornerCand(w, c2, c2Score));
            }
            cornerRank.sort(Comparator.comparingDouble(a -> a.score));

            Set<Long> seen = new HashSet<>();
            List<CornerCand> cornerCands = new ArrayList<>();
            for (CornerCand cc : cornerRank) {
                int qx = (int) Math.round(cc.corner.getX());
                int qy = (int) Math.round(cc.corner.getY());
                long key = (((long) qx) << 32) ^ (qy & 0xffffffffL);
                if (!seen.add(key)) continue;
                int cross1 = WifiMath.wallCrossCount(apPt.getX(), apPt.getY(), cc.corner.getX(), cc.corner.getY(), walls, null);
                int cross2 = WifiMath.wallCrossCount(cc.corner.getX(), cc.corner.getY(), rxPt.getX(), rxPt.getY(), walls, null);
                if (cross1 >= 3 && cross2 >= 3) continue;
                cornerCands.add(cc);
                if (cornerCands.size() >= MAX_DIFFRACTION_CORNERS) break;
            }

            // ===== 2차 회절 코너풀 =====
            List<CornerCand> cornerCands2 = new ArrayList<>();
            {
                List<CornerCand> rank2 = new ArrayList<>();
                for (Wall w2 : walls) {
                    if (w2 == null) continue;
                    Point2D p2c1 = new Point2D(w2.x1, w2.y1);
                    Point2D p2c2 = new Point2D(w2.x2, w2.y2);
                    double s1 = Math.min(apPt.distance(p2c1) * scaleMPerPx, rxPt.distance(p2c1) * scaleMPerPx);
                    double s2 = Math.min(apPt.distance(p2c2) * scaleMPerPx, rxPt.distance(p2c2) * scaleMPerPx);
                    if (s1 <= DOUBLE_DIFFRACTION_CORNER_RADIUS_M) rank2.add(new CornerCand(w2, p2c1, s1));
                    if (s2 <= DOUBLE_DIFFRACTION_CORNER_RADIUS_M) rank2.add(new CornerCand(w2, p2c2, s2));
                }
                rank2.sort(Comparator.comparingDouble(a -> a.score));
                Set<Long> seen2 = new HashSet<>();
                for (CornerCand cc2cand : rank2) {
                    int qx2 = (int) Math.round(cc2cand.corner.getX());
                    int qy2 = (int) Math.round(cc2cand.corner.getY());
                    long key2 = (((long) qx2) << 32) ^ (qy2 & 0xffffffffL);
                    if (!seen2.add(key2)) continue;
                    cornerCands2.add(cc2cand);
                    if (cornerCands2.size() >= MAX_DOUBLE_DIFFRACTION_CORNERS) break;
                }
            }

            // ===== Band별 RSSI 계산 =====
            Band[] allBands = Band.values();
            double[] losWallLossPerBand = WifiMath.wallLossAlongAllBands(ap.x, ap.y, px, py, walls, allBands);

            for (Band b : allBands) {
                int bandIdx = b.ordinal();
                RadioConfig rc = ap.radios.get(b);
                if (rc == null || !rc.enabled) continue;
                double freqGhz = rc.centerFreqGhz();
                double bwPenaltyDb = 0.0;
                double wallLoss = losWallLossPerBand[bandIdx];
                double bandMw = 0.0;

                // LOS
                double baseLossLos = WifiMath.pathLossDb(dM, freqGhz, pathLossN);
                double nearCompLos = WifiMath.nearFieldBandCompensationDb(dM, freqGhz);
                boolean losLikely = wallLoss <= 1.0e-6;
                double bfGainLos = WifiMath.beamformingGainDb(b, dM, losLikely);
                double rssiLos = rc.txPowerDbm + rc.antennaGain + nearCompLos + bfGainLos
                        - (baseLossLos + wallLoss + bwPenaltyDb);
                bandMw += Math.pow(10.0, rssiLos / 10.0);
                double losM = dM;

                // 1차 반사
                for (WallCand wc : wallCands) {
                    Wall w = wc.wall;
                    WallMaterial mat = (w == null) ? null : w.getMaterial();
                    Point2D reflPt = WifiMath.reflectionPoint(apPt, rxPt, w);
                    if (reflPt == null) continue;
                    double cosI = WifiMath.incidenceCosine(apPt, reflPt, w);
                    double reflLossDb = WifiMath.fresnelReflectionLossDb(mat, freqGhz, cosI);
                    WifiMath.Path p = WifiMath.buildSingleBounceReflection(apPt, rxPt, w, walls, scaleMPerPx, reflLossDb, b);
                    if (p == null) continue;
                    if (p.lengthMeters > losM * REFLECTION_LOS_RATIO_CUTOFF) continue;
                    double baseLossRefl = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
                    double nearCompRefl = WifiMath.nearFieldBandCompensationDb(p.lengthMeters, freqGhz);
                    double rssiRefl = rc.txPowerDbm + rc.antennaGain + nearCompRefl
                            - (baseLossRefl + p.wallLossDb + p.extraLossDb + bwPenaltyDb);
                    double reflCutoff = Math.min(rssiLos - SECONDARY_PATH_RELATIVE_CUTOFF_DB, MIN_SECONDARY_RSSI_DBM);
                    if (rssiRefl < reflCutoff) continue;
                    bandMw += Math.pow(10.0, rssiRefl / 10.0);
                }

                // 1차 회절
                for (CornerCand cc : cornerCands) {
                    Point2D corner = cc.corner;
                    double lenM = (apPt.distance(corner) + corner.distance(rxPt)) * scaleMPerPx;
                    if (lenM > losM * DIFFRACTION_LOS_RATIO_CUTOFF) continue;
                    double d1M = apPt.distance(corner) * scaleMPerPx;
                    double d2M = corner.distance(rxPt) * scaleMPerPx;
                    if (d1M <= 0.0 || d2M <= 0.0) continue;
                    double abx = rxPt.getX() - apPt.getX();
                    double aby = rxPt.getY() - apPt.getY();
                    double ab2 = abx * abx + aby * aby;
                    if (ab2 < 1e-9) continue;
                    double t = ((corner.getX() - apPt.getX()) * abx + (corner.getY() - apPt.getY()) * aby) / ab2;
                    if (t <= 0.0 || t >= 1.0) continue;
                    double projX = apPt.getX() + abx * t;
                    double projY = apPt.getY() + aby * t;
                    double hM = corner.distance(projX, projY) * scaleMPerPx;
                    double tolPx = WifiMath.pathIntersectionTolPx();
                    if (WifiMath.isSegmentBlocked(apPt, corner, walls, cc.wall, corner, tolPx)) continue;
                    if (WifiMath.isSegmentBlocked(corner, rxPt, walls, cc.wall, corner, tolPx)) continue;
                    // 하이브리드 회절 모델: 가까운 코너는 UTD, 먼 코너는 Knife-Edge
                    double diffLossDb;
                    if (d1M + d2M < 2.0 * DIFFRACTION_RADIUS_M) {
                        // UTD wedge diffraction (실내 직각 코너: n=1.5)
                        diffLossDb = WifiMath.utdWedgeDiffractionLossDb(hM, d1M, d2M, freqGhz, 1.5);
                    } else {
                        diffLossDb = WifiMath.knifeEdgeLossDb(hM, d1M, d2M, freqGhz);
                    }
                    if (diffLossDb <= 0.0) continue;
                    WifiMath.Path p = WifiMath.buildSingleCornerDiffraction(apPt, rxPt, corner, walls, scaleMPerPx, diffLossDb, b);
                    if (p == null) continue;
                    double baseLossDiff = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
                    double nearCompDiff = WifiMath.nearFieldBandCompensationDb(p.lengthMeters, freqGhz);
                    double rssiDiff = rc.txPowerDbm + rc.antennaGain + nearCompDiff
                            - (baseLossDiff + p.wallLossDb + p.extraLossDb + bwPenaltyDb);
                    double diffCutoff = Math.min(rssiLos - SECONDARY_PATH_RELATIVE_CUTOFF_DB, MIN_SECONDARY_RSSI_DBM);
                    if (rssiDiff < diffCutoff) continue;
                    bandMw += Math.pow(10.0, rssiDiff / 10.0);
                }

                // 2차 회절
                int pairsAdded = 0;
                outer2diff:
                for (int i = 0; i < cornerCands2.size() && pairsAdded < MAX_DOUBLE_DIFFRACTION_PAIRS; i++) {
                    CornerCand cc1 = cornerCands2.get(i);
                    Point2D c1 = cc1.corner;
                    if (apPt.distance(c1) * scaleMPerPx > DOUBLE_DIFFRACTION_CORNER_RADIUS_M) continue;
                    for (int j = 0; j < cornerCands2.size(); j++) {
                        if (i == j) continue;
                        CornerCand cc2 = cornerCands2.get(j);
                        Point2D c2 = cc2.corner;
                        if (rxPt.distance(c2) * scaleMPerPx > DOUBLE_DIFFRACTION_CORNER_RADIUS_M) continue;
                        if (c1.distance(c2) * scaleMPerPx < 0.5) continue;
                        double totalLenM = (apPt.distance(c1) + c1.distance(c2) + c2.distance(rxPt)) * scaleMPerPx;
                        if (totalLenM > losM * DOUBLE_DIFFRACTION_LOS_RATIO_CUTOFF) continue;
                        double tolPx = WifiMath.pathIntersectionTolPx();
                        if (WifiMath.isSegmentBlocked(apPt, c1, walls, cc1.wall, c1, tolPx)) continue;
                        if (WifiMath.isSegmentBlocked(c1, c2, walls, null, null, tolPx)) continue;
                        if (WifiMath.isSegmentBlocked(c2, rxPt, walls, cc2.wall, c2, tolPx)) continue;
                        double d1aM = apPt.distance(c1) * scaleMPerPx;
                        double d1bM = c1.distance(c2) * scaleMPerPx;
                        if (d1aM <= 0.0 || d1bM <= 0.0) continue;
                        double ax1 = c2.getX() - apPt.getX(), ay1 = c2.getY() - apPt.getY();
                        double ab1_2 = ax1 * ax1 + ay1 * ay1;
                        if (ab1_2 < 1e-9) continue;
                        double t1 = ((c1.getX() - apPt.getX()) * ax1 + (c1.getY() - apPt.getY()) * ay1) / ab1_2;
                        double proj1X = apPt.getX() + ax1 * t1;
                        double proj1Y = apPt.getY() + ay1 * t1;
                        double hM1 = c1.distance(proj1X, proj1Y) * scaleMPerPx;
                        double diffLoss1;
                        if (d1aM + d1bM < 2.0 * DIFFRACTION_RADIUS_M) {
                            diffLoss1 = WifiMath.utdWedgeDiffractionLossDb(hM1, d1aM, d1bM, freqGhz, 1.5);
                        } else {
                            diffLoss1 = WifiMath.knifeEdgeLossDb(hM1, d1aM, d1bM, freqGhz);
                        }
                        if (diffLoss1 <= 0.0) continue;
                        double d2aM = c1.distance(c2) * scaleMPerPx;
                        double d2bM = c2.distance(rxPt) * scaleMPerPx;
                        if (d2aM <= 0.0 || d2bM <= 0.0) continue;
                        double ax2 = rxPt.getX() - c1.getX(), ay2 = rxPt.getY() - c1.getY();
                        double ab2_2 = ax2 * ax2 + ay2 * ay2;
                        if (ab2_2 < 1e-9) continue;
                        double t2 = ((c2.getX() - c1.getX()) * ax2 + (c2.getY() - c1.getY()) * ay2) / ab2_2;
                        double proj2X = c1.getX() + ax2 * t2;
                        double proj2Y = c1.getY() + ay2 * t2;
                        double hM2 = c2.distance(proj2X, proj2Y) * scaleMPerPx;
                        double diffLoss2;
                        if (d2aM + d2bM < 2.0 * DIFFRACTION_RADIUS_M) {
                            diffLoss2 = WifiMath.utdWedgeDiffractionLossDb(hM2, d2aM, d2bM, freqGhz, 1.5);
                        } else {
                            diffLoss2 = WifiMath.knifeEdgeLossDb(hM2, d2aM, d2bM, freqGhz);
                        }
                        if (diffLoss2 <= 0.0) continue;
                        double totalDiffLossDb = diffLoss1 + diffLoss2;
                        WifiMath.Path p = WifiMath.buildDoubleCornerDiffraction(apPt, rxPt, c1, c2, walls, scaleMPerPx, totalDiffLossDb, b);
                        if (p == null) continue;
                        double baseLossD2 = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
                        double nearCompD2 = WifiMath.nearFieldBandCompensationDb(p.lengthMeters, freqGhz);
                        double rssiD2 = rc.txPowerDbm + rc.antennaGain + nearCompD2
                                - (baseLossD2 + p.wallLossDb + p.extraLossDb + bwPenaltyDb);
                        double d2Cutoff = Math.min(rssiLos - SECONDARY_PATH_RELATIVE_CUTOFF_DB, MIN_SECONDARY_RSSI_DBM);
                        if (rssiD2 < d2Cutoff) continue;
                        bandMw += Math.pow(10.0, rssiD2 / 10.0);
                        pairsAdded++;
                        if (pairsAdded >= MAX_DOUBLE_DIFFRACTION_PAIRS) break outer2diff;
                    }
                }

                if (bandMw > 0.0) {
                    double bandRssi = 10.0 * Math.log10(bandMw);
                    if (bandRssi > bestRssi) bestRssi = bandRssi;
                }
            }

            if (bestRssi > strongest) strongest = bestRssi;
        }

        return strongest;
    }

    /** Color → ARGB int 변환 */
    private static int colorToArgb(Color c) {
        int a = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getOpacity())) * 255.0);
        int r = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getRed())) * 255.0);
        int g = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getGreen())) * 255.0);
        int b = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getBlue())) * 255.0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static GpuHeatmapSolver loadGpuSolver() {
        try {
            for (GpuHeatmapSolver solver : ServiceLoader.load(GpuHeatmapSolver.class)) {
                if (solver != null && solver.isAvailable()) {
                    return solver;
                }
            }
        } catch (Throwable ignored) {
            // optional backend - unavailable
        }
        return null;
    }
}
