package app.engine;

import app.model.RadioConfig;
import app.model.*;
import javafx.geometry.Point2D;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * WifiEnvironment를 기반으로 히트맵 이미지를 생성하는 클래스.
 * - LOS(직진) + 1차 반사 + 1차 회절(코너)을 전력(mW) 합산
 * - 밴드는 2.4/5 중 활성 라디오에 대해 계산 후 "최강 RSSI" 선택
 */
public class HeatmapGenerator {

    private final WifiEnvironment env;
    private final AppState.HeatmapSolverMode solverMode;
    private static final GpuHeatmapSolver GPU_SOLVER = loadGpuSolver();
    private volatile boolean usedGpuLastRun = false;
    private volatile boolean gpuFallbackLastRun = false;

    public HeatmapGenerator(WifiEnvironment env) {
        this(env, AppState.HeatmapSolverMode.CPU);
    }

    public HeatmapGenerator(WifiEnvironment env, AppState.HeatmapSolverMode solverMode) {
        this.env = env;
        this.solverMode = (solverMode == null) ? AppState.HeatmapSolverMode.CPU : solverMode;
    }

    public WritableImage generate(int width,
                                  int height,
                                  int gridStepPx,
                                  double legendMinDbm,
                                  double legendMaxDbm,
                                  int smoothRadiusPx) {
        if (solverMode == AppState.HeatmapSolverMode.GPU) {
            if (GPU_SOLVER != null) {
                WritableImage gpuImage = GPU_SOLVER.generate(
                        env,
                        width,
                        height,
                        gridStepPx,
                        legendMinDbm,
                        legendMaxDbm,
                        smoothRadiusPx
                );
                if (gpuImage != null) {
                    usedGpuLastRun = true;
                    gpuFallbackLastRun = false;
                    return gpuImage;
                }
            }
            usedGpuLastRun = false;
            gpuFallbackLastRun = true;
            return generateCpu(width, height, gridStepPx, legendMinDbm, legendMaxDbm, smoothRadiusPx);
        }

        usedGpuLastRun = false;
        gpuFallbackLastRun = false;
        return generateCpu(width, height, gridStepPx, legendMinDbm, legendMaxDbm, smoothRadiusPx);
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

    private WritableImage generateCpu(int width,
                                      int height,
                                      int gridStepPx,
                                      double legendMinDbm,
                                      double legendMaxDbm,
                                      int smoothRadiusPx) {

        // 환경에서 현재 상태 가져오기
        List<AP> aps = new ArrayList<>(env.getAps());
        List<Wall> walls = new ArrayList<>(env.getWalls());
        double scaleMPerPx = env.getScaleMPerPx();
        double pathLossN = env.getPathLossN();
        double minDistanceM = env.getMinDistanceM();

        // 1) 활성 AP 필터링
        List<AP> enabled = new ArrayList<>();
        for (AP ap : aps) {
            if (ap != null && ap.enabled) enabled.add(ap);
        }

        WritableImage img = new WritableImage(width, height);
        PixelWriter pw = img.getPixelWriter();

        int sub = 3; // 3x3 슈퍼샘플링

        // ── AP별 반사/회절 후보 사전 계산 (AP 위치는 픽셀마다 변하지 않음) ──────────
        // wallCands = walls within REFLECTION_RADIUS_M of AP (per AP, once)
        // cornerCands = corners within DIFFRACTION_RADIUS_M of AP (per AP, once)
        // 픽셀 루프 내에서는 RX-local만 추가 계산하고 merge → O(AP×W×H) → O(W×H)
        Map<AP, List<WifiMath.WallCand>> apWallCands = new IdentityHashMap<>();
        Map<AP, List<WifiMath.CornerCand>> apCornerCands = new IdentityHashMap<>();
        for (AP ap : enabled) {
            Point2D apPt = new Point2D(ap.x, ap.y);
            apWallCands.put(ap, WifiMath.wallsNearPoint(apPt, walls, scaleMPerPx));
            apCornerCands.put(ap, WifiMath.cornersNearPoint(apPt, walls, scaleMPerPx));
        }

        for (int yy = 0; yy < height; yy += gridStepPx) {
            for (int xx = 0; xx < width; xx += gridStepPx) {

                double mwSum = 0.0;
                int samples = 0;

                // 블록 내 3x3 샘플
                for (int sy = 0; sy < sub; sy++) {
                    for (int sx = 0; sx < sub; sx++) {
                        int px = Math.min(width - 1, xx + (sx * gridStepPx + gridStepPx / 2) / sub);
                        int py = Math.min(height - 1, yy + (sy * gridStepPx + gridStepPx / 2) / sub);

                        double strongest = -1e9;

                        // RX-local 후보: 이 샘플 포인트 기준으로 한 번만 계산
                        Point2D rxPt = new Point2D(px, py);
                        List<WifiMath.WallCand> rxWallCands =
                                WifiMath.wallsNearPoint(rxPt, walls, scaleMPerPx);
                        List<WifiMath.CornerCand> rxCornerCands =
                                WifiMath.cornersNearPoint(rxPt, walls, scaleMPerPx);

                        for (AP ap : enabled) {
                            Point2D apPt = new Point2D(ap.x, ap.y);

                            // 1) 3D 거리(m): 2D 수평거리 + 높이차
                            double d2dM = apPt.distance(rxPt) * scaleMPerPx;
                            double dzM = ap.heightM - env.getClientHeightM();
                            double dM = Math.sqrt(d2dM * d2dM + dzM * dzM);
                            dM = Math.max(dM, minDistanceM);

                            double bestRssi = -1e9;

                            // ===== 반사/회절 후보 병합 (AP-local + RX-local) =====
                            List<WifiMath.WallCand> wallCands =
                                    WifiMath.mergeWallCands(apWallCands.get(ap), rxWallCands);
                            List<WifiMath.CornerCand> cornerCands =
                                    WifiMath.mergeCornerCands(apCornerCands.get(ap), rxCornerCands,
                                            walls, apPt, rxPt);

                            for (Band b : Band.values()) {
                                RadioConfig rc = ap.radios.get(b);
                                if (rc == null || !rc.enabled) continue;
                                double freqGhz = rc.centerFreqGhz();
                                double bwPenaltyDb = rc.bandwidthPenaltyDb();

                                // 2) 직선상 벽 감쇠 (밴드별 2.4/5 적용)
                                double wallLoss = WifiMath.wallLossAlong(ap.x, ap.y, px, py, walls, b);

                                double bandMw = 0.0;

                                // 1) LOS
                                double baseLossLos = WifiMath.pathLossDb(dM, freqGhz, pathLossN);
                                double rssiLos = rc.txPowerDbm + rc.antennaGain - (baseLossLos + wallLoss + bwPenaltyDb);
                                bandMw += Math.pow(10.0, rssiLos / 10.0);

                                double losM = dM;

                                // 2) 1차 반사
                                for (WifiMath.WallCand wc : wallCands) {
                                    Wall w = wc.wall;
                                    WallMaterial mat = (w == null) ? null : w.getMaterial();
                                    Point2D reflPt = WifiMath.reflectionPoint(apPt, rxPt, w);
                                    if (reflPt == null) continue;
                                    double cosI = WifiMath.incidenceCosine(apPt, reflPt, w);
                                    double reflLossDb = WifiMath.fresnelReflectionLossDb(mat, freqGhz, cosI);

                                    WifiMath.Path p = WifiMath.buildSingleBounceReflection(
                                            apPt, rxPt, w, walls, scaleMPerPx, reflLossDb, b);
                                    if (p == null) continue;
                                    if (p.lengthMeters > losM * WifiMath.REFLECTION_LOS_RATIO) continue;

                                    double baseLossRefl = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
                                    double rssiRefl = rc.txPowerDbm + rc.antennaGain
                                            - (baseLossRefl + p.wallLossDb + p.extraLossDb + bwPenaltyDb);
                                    if (rssiRefl < rssiLos - WifiMath.SECONDARY_CUTOFF_DB) continue;
                                    bandMw += Math.pow(10.0, rssiRefl / 10.0);
                                }

                                // 3) 1차 회절(코너)
                                for (WifiMath.CornerCand cc : cornerCands) {
                                    Point2D corner = cc.corner;
                                    double lenM = (apPt.distance(corner) + corner.distance(rxPt)) * scaleMPerPx;
                                    if (lenM > losM * WifiMath.DIFFRACTION_LOS_RATIO) continue;

                                    double d1M = apPt.distance(corner) * scaleMPerPx;
                                    double d2M = corner.distance(rxPt) * scaleMPerPx;
                                    if (d1M <= 0.0 || d2M <= 0.0) continue;

                                    // 코너가 LOS 구간 밖이면 제외
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

                                    double diffLossDb = WifiMath.knifeEdgeLossDb(hM, d1M, d2M, freqGhz);
                                    if (diffLossDb <= 0.0) continue;

                                    WifiMath.Path p = WifiMath.buildSingleCornerDiffraction(
                                            apPt, rxPt, corner, walls, scaleMPerPx, diffLossDb, b, cc.wall);
                                    if (p == null) continue;

                                    double baseLossDiff = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
                                    double rssiDiff = rc.txPowerDbm + rc.antennaGain
                                            - (baseLossDiff + p.wallLossDb + p.extraLossDb + bwPenaltyDb);
                                    if (rssiDiff < rssiLos - WifiMath.SECONDARY_CUTOFF_DB) continue;
                                    bandMw += Math.pow(10.0, rssiDiff / 10.0);
                                }

                                if (bandMw > 0.0) {
                                    double bandRssi = 10.0 * Math.log10(bandMw);
                                    if (bandRssi > bestRssi) bestRssi = bandRssi;
                                }
                            }

                            if (bestRssi > strongest) strongest = bestRssi;
                        }

                        if (strongest > -1e9) {
                            mwSum += Math.pow(10.0, strongest / 10.0);
                            samples++;
                        }
                    }
                }

                if (samples > 0) {
                    double avgDbm = 10.0 * Math.log10(mwSum / samples);
                    Color c = WifiMath.rssiToColor(avgDbm, legendMinDbm, legendMaxDbm);
                    WifiMath.fillBlock(pw, xx, yy, gridStepPx, gridStepPx, width, height, c);
                }
            }
        }

        // 스무딩
        if (smoothRadiusPx > 0) {
            return WifiMath.boxBlur(img, smoothRadiusPx);
        }

        return img;
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
