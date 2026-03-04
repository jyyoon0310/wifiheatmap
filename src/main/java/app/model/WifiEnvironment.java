package app.model;

import app.engine.WifiMath;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WifiEnvironment {

    private final ObservableList<AP> aps = FXCollections.observableArrayList();
    private final ObservableList<Wall> walls = FXCollections.observableArrayList();

    private double scaleMPerPx = Double.NaN;
    private double pathLossN = 3.5;
    private double clientHeightM = 1.0;

    // 근접 최소거리: 10 cm 고정
    private static final double MIN_DISTANCE_M = 0.10;

    // ===== Debug path (overlay) tuning (match HeatmapGenerator) =====
    private static final int MAX_REFLECTION_WALLS = 12;
    private static final double REFLECTION_RADIUS_M = 15.0;
    private static final double REFLECTION_LOS_RATIO_CUTOFF = 2.5;

    private static final int MAX_DIFFRACTION_CORNERS = 16;
    private static final double DIFFRACTION_RADIUS_M = 12.0;
    private static final double DIFFRACTION_LOS_RATIO_CUTOFF = 2.8;
    private static final double DEBUG_SECONDARY_MARGIN_DB = 6.0;
    private static final double SECONDARY_PATH_RELATIVE_CUTOFF_DB = 10.0;

    public ObservableList<AP> getAps() { return aps; }
    public ObservableList<Wall> getWalls() { return walls; }

    public double getScaleMPerPx() { return scaleMPerPx; }
    public void setScaleMPerPx(double scaleMPerPx) { this.scaleMPerPx = scaleMPerPx; }

    public double getPathLossN() { return pathLossN; }
    public void setPathLossN(double pathLossN) { this.pathLossN = pathLossN; }
    public double getClientHeightM() { return clientHeightM; }
    public void setClientHeightM(double clientHeightM) { this.clientHeightM = clientHeightM; }

    public double getMinDistanceM() { return MIN_DISTANCE_M; }

    /** ✅ 호버용: 해당 지점에서 수신되는 (SSID, Band)별 RSSI 리스트 */
    public List<RssiResult> sampleRssiAllAt(int px, int py) {
        if (Double.isNaN(scaleMPerPx) || aps.isEmpty()) return List.of();

        List<RssiResult> out = new ArrayList<>();

        for (AP ap : aps) {
            if (!ap.enabled) continue;

            for (Band b : Band.values()) {
                RadioConfig rc = ap.radios.get(b);
                if (rc == null || !rc.enabled) continue;

                // SSID가 비어있으면 스킵(원하면 빈 값도 표시 가능)
                if (rc.ssid == null || rc.ssid.isBlank()) continue;

                // 1) AP까지 거리(m) + 3D
                Point2D apPt = new Point2D(ap.x, ap.y);
                Point2D rxPt = new Point2D(px, py);
                double d2dM = apPt.distance(rxPt) * scaleMPerPx;
                double dzM = ap.heightM - clientHeightM;
                double dM = Math.sqrt(d2dM * d2dM + dzM * dzM);
                dM = Math.max(dM, MIN_DISTANCE_M);

                // 2) LOS + 1차 반사 + 1차 회절 전력 합산
                double bandMw = 0.0;
                double freqGhz = rc.centerFreqGhz();
                double bwPenaltyDb = rc.bandwidthPenaltyDb();

                double wallLoss = WifiMath.wallLossAlong(ap.x, ap.y, px, py, walls, b);

                double baseLossLos = WifiMath.pathLossDb(dM, freqGhz, pathLossN);
                double rssiLos = rc.txPowerDbm + rc.antennaGain - (baseLossLos + wallLoss + bwPenaltyDb);
                bandMw += Math.pow(10.0, rssiLos / 10.0);

                // 반사(모든 벽 시도)
                for (Wall w : walls) {
                    if (w == null) continue;
                    WallMaterial mat = w.getMaterial();
                    Point2D reflPt = WifiMath.reflectionPoint(apPt, rxPt, w);
                    if (reflPt == null) continue;
                    double cosI = WifiMath.incidenceCosine(apPt, reflPt, w);
                    double reflLossDb = WifiMath.fresnelReflectionLossDb(mat, freqGhz, cosI);

                    WifiMath.Path p = WifiMath.buildSingleBounceReflection(apPt, rxPt, w, walls, scaleMPerPx, reflLossDb, b);
                    if (p == null) continue;
                    if (p.lengthMeters > dM * 2.5) continue;

                    double baseLossRefl = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
                    double rssiRefl = rc.txPowerDbm + rc.antennaGain
                            - (baseLossRefl + p.wallLossDb + p.extraLossDb + bwPenaltyDb);
                    if (rssiRefl < rssiLos - SECONDARY_PATH_RELATIVE_CUTOFF_DB) continue;
                    bandMw += Math.pow(10.0, rssiRefl / 10.0);
                }

                // 회절(코너 = 벽 끝점)
                for (Wall w : walls) {
                    if (w == null) continue;
                    Point2D c1 = new Point2D(w.x1, w.y1);
                    Point2D c2 = new Point2D(w.x2, w.y2);

                    bandMw += diffractionContribution(apPt, rxPt, w, c1, dM, freqGhz, rc, bwPenaltyDb, b, rssiLos);
                    bandMw += diffractionContribution(apPt, rxPt, w, c2, dM, freqGhz, rc, bwPenaltyDb, b, rssiLos);
                }

                double rssi = (bandMw > 0.0) ? (10.0 * Math.log10(bandMw)) : -1e9;

                out.add(new RssiResult(ap.name, rc.ssid, b, rssi));
            }
        }

        out.sort(Comparator.comparingDouble((RssiResult r) -> r.rssiDbm).reversed());
        return out;
    }

    private double diffractionContribution(Point2D apPt,
                                           Point2D rxPt,
                                           Wall ownerWall,
                                           Point2D corner,
                                           double losM,
                                           double freqGhz,
                                           RadioConfig rc,
                                           double bwPenaltyDb,
                                           Band band,
                                           double rssiLos) {
        double d1M = apPt.distance(corner) * scaleMPerPx;
        double d2M = corner.distance(rxPt) * scaleMPerPx;
        if (d1M <= 0.0 || d2M <= 0.0) return 0.0;

        // 코너가 LOS 구간 밖이면 제외
        double abx = rxPt.getX() - apPt.getX();
        double aby = rxPt.getY() - apPt.getY();
        double ab2 = abx * abx + aby * aby;
        if (ab2 < 1e-9) return 0.0;
        double t = ((corner.getX() - apPt.getX()) * abx + (corner.getY() - apPt.getY()) * aby) / ab2;
        if (t <= 0.0 || t >= 1.0) return 0.0;

        double projX = apPt.getX() + abx * t;
        double projY = apPt.getY() + aby * t;
        double hM = corner.distance(projX, projY) * scaleMPerPx;

        double tolPx = WifiMath.pathIntersectionTolPx();
        if (WifiMath.isSegmentBlocked(apPt, corner, walls, ownerWall, corner, tolPx)) return 0.0;
        if (WifiMath.isSegmentBlocked(corner, rxPt, walls, ownerWall, corner, tolPx)) return 0.0;

        double diffLossDb = WifiMath.knifeEdgeLossDb(hM, d1M, d2M, freqGhz);
        if (diffLossDb <= 0.0) return 0.0;

        WifiMath.Path p = WifiMath.buildSingleCornerDiffraction(apPt, rxPt, corner, walls, scaleMPerPx, diffLossDb, band);
        if (p == null) return 0.0;
        if (p.lengthMeters > losM * 2.8) return 0.0;

        double baseLossDiff = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
        double rssiDiff = rc.txPowerDbm + rc.antennaGain
                - (baseLossDiff + p.wallLossDb + p.extraLossDb + bwPenaltyDb);
        if (rssiDiff < rssiLos - SECONDARY_PATH_RELATIVE_CUTOFF_DB) return 0.0;
        return Math.pow(10.0, rssiDiff / 10.0);
    }

    /** ✅ 기존 유지: “최강 RSSI” (히트맵/레전드 포인터용) */
    public double sampleRssiAt(int px, int py) {
        List<RssiResult> list = sampleRssiAllAt(px, py);
        if (list.isEmpty()) return Double.NaN;
        return list.get(0).rssiDbm;
    }

    /** 디버그용: 마우스 위치에서 최강 AP/밴드의 LOS/반사/회절 경로 */
    public List<PropagationPath> computeBestPathsAt(int px, int py) {
        if (Double.isNaN(scaleMPerPx) || aps.isEmpty()) return List.of();

        Point2D rxPt = new Point2D(px, py);
        double bestBandRssi = -1e9;
        PropagationPath bestLos = null;
        PropagationPath bestRefl = null;
        PropagationPath bestDiff = null;
        double bestLosRssi = -1e9;
        double bestReflRssi = -1e9;
        double bestDiffRssi = -1e9;

        for (AP ap : aps) {
            if (ap == null || !ap.enabled) continue;
            Point2D apPt = new Point2D(ap.x, ap.y);

            for (Band b : Band.values()) {
                RadioConfig rc = ap.radios.get(b);
                if (rc == null || !rc.enabled) continue;
                if (rc.ssid == null || rc.ssid.isBlank()) continue;

                // 1) 거리(m)
                double d2dM = apPt.distance(rxPt) * scaleMPerPx;
                double dzM = ap.heightM - clientHeightM;
                double dM = Math.sqrt(d2dM * d2dM + dzM * dzM);
                dM = Math.max(dM, MIN_DISTANCE_M);

                double freqGhz = rc.centerFreqGhz();
                double bwPenaltyDb = rc.bandwidthPenaltyDb();

                double bandMw = 0.0;

                // LOS
                double wallLoss = WifiMath.wallLossAlong(ap.x, ap.y, px, py, walls, b);
                double baseLossLos = WifiMath.pathLossDb(dM, freqGhz, pathLossN);
                double rssiLos = rc.txPowerDbm + rc.antennaGain - (baseLossLos + wallLoss + bwPenaltyDb);
                bandMw += Math.pow(10.0, rssiLos / 10.0);

                PropagationPath losPath = null;
                if (!WifiMath.isSegmentBlocked(apPt, rxPt, walls, null, null, WifiMath.pathIntersectionTolPx())) {
                    losPath = new PropagationPath(PropagationPath.Type.LOS, apPt, rxPt, null);
                }

                // ===== Reflection candidates (same as HeatmapGenerator) =====
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
                    if (minM <= REFLECTION_RADIUS_M) wallCands.add(new WallCand(w, minM));
                }
                wallCands.sort(Comparator.comparingDouble(a -> a.score));
                if (wallCands.size() > MAX_REFLECTION_WALLS) {
                    wallCands = wallCands.subList(0, MAX_REFLECTION_WALLS);
                }

                double localBestReflRssi = -1e9;
                PropagationPath reflPath = null;
                for (WallCand wc : wallCands) {
                    Wall w = wc.wall;
                    WallMaterial mat = (w == null) ? null : w.getMaterial();
                    Point2D reflPt = WifiMath.reflectionPoint(apPt, rxPt, w);
                    if (reflPt == null) continue;
                    double cosI = WifiMath.incidenceCosine(apPt, reflPt, w);
                    double reflLossDb = WifiMath.fresnelReflectionLossDb(mat, freqGhz, cosI);

                    WifiMath.Path p = WifiMath.buildSingleBounceReflection(
                            apPt, rxPt, w, walls, scaleMPerPx, reflLossDb, b);
                    if (p == null) continue;
                    if (p.lengthMeters > dM * REFLECTION_LOS_RATIO_CUTOFF) continue;

                    double baseLossRefl = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
                    double rssiRefl = rc.txPowerDbm + rc.antennaGain
                            - (baseLossRefl + p.wallLossDb + p.extraLossDb + bwPenaltyDb);
                    if (rssiRefl < rssiLos - SECONDARY_PATH_RELATIVE_CUTOFF_DB) continue;
                    bandMw += Math.pow(10.0, rssiRefl / 10.0);

                    if (rssiRefl > localBestReflRssi) {
                        localBestReflRssi = rssiRefl;
                        reflPath = new PropagationPath(PropagationPath.Type.REFLECTION, apPt, p.via, rxPt);
                    }
                }

                // ===== Diffraction candidates (same as HeatmapGenerator) =====
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

                double localBestDiffRssi = -1e9;
                PropagationPath diffPath = null;
                for (CornerCand cc : cornerCands) {
                    Point2D corner = cc.corner;
                    double lenM = (apPt.distance(corner) + corner.distance(rxPt)) * scaleMPerPx;
                    if (lenM > dM * DIFFRACTION_LOS_RATIO_CUTOFF) continue;

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
                            apPt, rxPt, corner, walls, scaleMPerPx, diffLossDb, b);
                    if (p == null) continue;

                    double baseLossDiff = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
                    double rssiDiff = rc.txPowerDbm + rc.antennaGain
                            - (baseLossDiff + p.wallLossDb + p.extraLossDb + bwPenaltyDb);
                    if (rssiDiff < rssiLos - SECONDARY_PATH_RELATIVE_CUTOFF_DB) continue;
                    bandMw += Math.pow(10.0, rssiDiff / 10.0);

                    if (rssiDiff > localBestDiffRssi) {
                        localBestDiffRssi = rssiDiff;
                        diffPath = new PropagationPath(PropagationPath.Type.DIFFRACTION, apPt, corner, rxPt);
                    }
                }

                if (bandMw > 0.0) {
                    double bandRssi = 10.0 * Math.log10(bandMw);
                    if (bandRssi > bestBandRssi) {
                        bestBandRssi = bandRssi;
                        bestLos = losPath;
                        bestRefl = reflPath;
                        bestDiff = diffPath;
                        bestLosRssi = rssiLos;
                        bestReflRssi = localBestReflRssi;
                        bestDiffRssi = localBestDiffRssi;
                    }
                }
            }
        }

        List<PropagationPath> out = new ArrayList<>();
        if (bestLos != null) {
            out.add(bestLos);
            if (bestRefl != null && bestReflRssi >= bestLosRssi - DEBUG_SECONDARY_MARGIN_DB) out.add(bestRefl);
            if (bestDiff != null && bestDiffRssi >= bestLosRssi - DEBUG_SECONDARY_MARGIN_DB) out.add(bestDiff);
            return out;
        }

        // LOS가 막힌 경우에는 반사/회절 중 더 강한 경로 하나만 표시
        if (bestRefl != null || bestDiff != null) {
            if (bestReflRssi >= bestDiffRssi && bestRefl != null) out.add(bestRefl);
            else if (bestDiff != null) out.add(bestDiff);
        }
        return out;
    }

    private static final class WallCand {
        final Wall wall;
        final double score;
        WallCand(Wall wall, double score) { this.wall = wall; this.score = score; }
    }

    private static final class CornerCand {
        final Wall wall;
        final Point2D corner;
        final double score;
        CornerCand(Wall wall, Point2D corner, double score) { this.wall = wall; this.corner = corner; this.score = score; }
    }
}
