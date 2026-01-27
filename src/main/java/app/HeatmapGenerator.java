package app;

import javafx.geometry.Point2D;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WifiEnvironment를 기반으로 히트맵 이미지를 생성하는 클래스.
 * - LOS(직진) + 1차 반사 + 1차 회절(코너)을 전력(mW) 합산
 * - 밴드는 2.4/5 중 활성 라디오에 대해 계산 후 "최강 RSSI" 선택
 */
public class HeatmapGenerator {

    private final WifiEnvironment env;

    // ===== Reflection(1-bounce) 튜닝 =====
    private static final int MAX_REFLECTION_WALLS = 12;
    private static final double REFLECTION_RADIUS_M = 15.0;
    private static final double REFLECTION_LOS_RATIO_CUTOFF = 2.5;

    // ===== Diffraction(코너) 튜닝 =====
    private static final int MAX_DIFFRACTION_CORNERS = 16;
    private static final double DIFFRACTION_RADIUS_M = 12.0;
    private static final double DIFFRACTION_LOS_RATIO_CUTOFF = 2.8;

    private static final class WallCand {
        final Wall wall;
        final double score; // 작을수록 더 가까움
        WallCand(Wall wall, double score) { this.wall = wall; this.score = score; }
    }

    private static final class CornerCand {
        final Point2D corner;
        final double score; // 작을수록 더 가까움
        CornerCand(Point2D corner, double score) { this.corner = corner; this.score = score; }
    }

    public HeatmapGenerator(WifiEnvironment env) {
        this.env = env;
    }

    public WritableImage generate(int width,
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

                        for (AP ap : enabled) {
                            Point2D apPt = new Point2D(ap.x, ap.y);
                            Point2D rxPt = new Point2D(px, py);

                            // 1) 거리(m)
                            double dM = apPt.distance(rxPt) * scaleMPerPx;
                            dM = Math.max(dM, minDistanceM);

                            double bestRssi = -1e9;

                            // ===== 반사 후보 벽을 가까운 것 위주로 제한 (성능 보호) =====
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

                            // ===== 회절 코너 후보 생성(거리 랭킹 + 중복 제거 + 간단 필터) =====
                            List<CornerCand> cornerRank = new ArrayList<>();
                            for (Wall w : walls) {
                                if (w == null) continue;
                                Point2D c1 = new Point2D(w.x1, w.y1);
                                Point2D c2 = new Point2D(w.x2, w.y2);

                                double c1Score = Math.min(apPt.distance(c1) * scaleMPerPx, rxPt.distance(c1) * scaleMPerPx);
                                double c2Score = Math.min(apPt.distance(c2) * scaleMPerPx, rxPt.distance(c2) * scaleMPerPx);
                                if (c1Score <= DIFFRACTION_RADIUS_M) cornerRank.add(new CornerCand(c1, c1Score));
                                if (c2Score <= DIFFRACTION_RADIUS_M) cornerRank.add(new CornerCand(c2, c2Score));
                            }

                            cornerRank.sort(Comparator.comparingDouble(a -> a.score));

                            Set<Long> seen = new HashSet<>();
                            List<Point2D> cornerCands = new ArrayList<>();
                            for (CornerCand cc : cornerRank) {
                                int qx = (int) Math.round(cc.corner.getX());
                                int qy = (int) Math.round(cc.corner.getY());
                                long key = (((long) qx) << 32) ^ (qy & 0xffffffffL);
                                if (!seen.add(key)) continue;

                                int cross1 = WifiMath.wallCrossCount(apPt.getX(), apPt.getY(), cc.corner.getX(), cc.corner.getY(), walls, null);
                                int cross2 = WifiMath.wallCrossCount(cc.corner.getX(), cc.corner.getY(), rxPt.getX(), rxPt.getY(), walls, null);
                                if (cross1 >= 3 && cross2 >= 3) continue;

                                cornerCands.add(cc.corner);
                                if (cornerCands.size() >= MAX_DIFFRACTION_CORNERS) break;
                            }

                            for (Band b : Band.values()) {
                                RadioConfig rc = ap.radios.get(b);
                                if (rc == null || !rc.enabled) continue;

                                // 2) 직선상 벽 감쇠 (밴드별 2.4/5 적용)
                                double wallLoss = WifiMath.wallLossAlong(ap.x, ap.y, px, py, walls, b);

                                double bandMw = 0.0;

                                // 1) LOS
                                double baseLossLos = WifiMath.pathLossDb(dM, b.freqGhz, pathLossN);
                                double rssiLos = rc.txPowerDbm + rc.antennaGain - (baseLossLos + wallLoss);
                                bandMw += Math.pow(10.0, rssiLos / 10.0);

                                double losM = dM;

                                // 2) 1차 반사
                                for (WallCand wc : wallCands) {
                                    Wall w = wc.wall;
                                    double reflLossDb = 8.0;
                                    WallMaterial mat = (w == null) ? null : w.getMaterial();
                                    if (mat != null) {
                                        reflLossDb = mat.reflectionLossDb();
                                    }

                                    WifiMath.Path p = WifiMath.buildSingleBounceReflection(
                                            apPt, rxPt, w, walls, scaleMPerPx, reflLossDb, b);
                                    if (p == null) continue;
                                    if (p.lengthMeters > losM * REFLECTION_LOS_RATIO_CUTOFF) continue;

                                    double baseLossRefl = WifiMath.pathLossDb(p.lengthMeters, b.freqGhz, pathLossN);
                                    double rssiRefl = rc.txPowerDbm + rc.antennaGain
                                            - (baseLossRefl + p.wallLossDb + p.extraLossDb);
                                    bandMw += Math.pow(10.0, rssiRefl / 10.0);
                                }

                                // 3) 1차 회절(코너)
                                for (Point2D corner : cornerCands) {
                                    double lenM = (apPt.distance(corner) + corner.distance(rxPt)) * scaleMPerPx;
                                    if (lenM > losM * DIFFRACTION_LOS_RATIO_CUTOFF) continue;

                                    Point2D v1 = apPt.subtract(corner);
                                    Point2D v2 = rxPt.subtract(corner);
                                    double theta = WifiMath.angleDeg(v1, v2);
                                    double t = Math.max(0.0, Math.min(120.0, theta));

                                    double diffLossDb = 6.0 + 0.10 * t;
                                    double ratio = Math.max(1.0, lenM / losM);
                                    diffLossDb += 10.0 * Math.log10(ratio);

                                    WifiMath.Path p = WifiMath.buildSingleCornerDiffraction(
                                            apPt, rxPt, corner, walls, scaleMPerPx, diffLossDb, b);
                                    if (p == null) continue;

                                    double baseLossDiff = WifiMath.pathLossDb(p.lengthMeters, b.freqGhz, pathLossN);
                                    double rssiDiff = rc.txPowerDbm + rc.antennaGain
                                            - (baseLossDiff + p.wallLossDb + p.extraLossDb);
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
}