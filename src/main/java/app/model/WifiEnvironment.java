package app.model;

import app.engine.WifiMath;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class WifiEnvironment {

    private final ObservableList<AP> aps = FXCollections.observableArrayList();
    private final ObservableList<Wall> walls = FXCollections.observableArrayList();

    private double scaleMPerPx = Double.NaN;
    private double pathLossN = 3.5;
    private double clientHeightM = 1.0;

    // 근접 최소거리: 10 cm 고정
    private static final double MIN_DISTANCE_M = 0.10;

    // ===== 다중경로 튜닝 (Legacy 기준선 계산용) =====
    private static final double DIFFRACTION_RADIUS_M = 12.0;
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
                // RSSI(전력) 예측에는 BW 패널티를 직접 차감하지 않는다.
                // (BW 패널티는 링크 품질/SNR 추정에서 별도 사용 권장)
                double bwPenaltyDb = 0.0;

                double wallLoss = WifiMath.wallLossAlong(ap.x, ap.y, px, py, walls, b);

                double baseLossLos = WifiMath.pathLossDb(dM, freqGhz, pathLossN);
                double nearCompLos = WifiMath.nearFieldBandCompensationDb(dM, freqGhz);
                boolean losLikely = wallLoss <= 1.0e-6;
                double bfGainLos = WifiMath.beamformingGainDb(b, dM, losLikely);
                double rssiLos = rc.txPowerDbm + rc.antennaGain + nearCompLos + bfGainLos
                        - (baseLossLos + wallLoss + bwPenaltyDb);
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
                    double nearCompRefl = WifiMath.nearFieldBandCompensationDb(p.lengthMeters, freqGhz);
                    double rssiRefl = rc.txPowerDbm + rc.antennaGain + nearCompRefl
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

        // 하이브리드 회절: 가까운 코너는 UTD, 먼 코너는 Knife-Edge
        double diffLossDb;
        if (d1M + d2M < 2.0 * DIFFRACTION_RADIUS_M) {
            diffLossDb = WifiMath.utdWedgeDiffractionLossDb(hM, d1M, d2M, freqGhz, 1.5);
        } else {
            diffLossDb = WifiMath.knifeEdgeLossDb(hM, d1M, d2M, freqGhz);
        }
        if (diffLossDb <= 0.0) return 0.0;

        WifiMath.Path p = WifiMath.buildSingleCornerDiffraction(apPt, rxPt, corner, walls, scaleMPerPx, diffLossDb, band);
        if (p == null) return 0.0;
        if (p.lengthMeters > losM * 2.8) return 0.0;

        double baseLossDiff = WifiMath.pathLossDb(p.lengthMeters, freqGhz, pathLossN);
        double nearCompDiff = WifiMath.nearFieldBandCompensationDb(p.lengthMeters, freqGhz);
        double rssiDiff = rc.txPowerDbm + rc.antennaGain + nearCompDiff
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
}
