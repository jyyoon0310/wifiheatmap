package app;

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
    private double pathLossN = 2.4;

    // 근접 최소거리: 10 cm 고정
    private static final double MIN_DISTANCE_M = 0.10;

    public ObservableList<AP> getAps() { return aps; }
    public ObservableList<Wall> getWalls() { return walls; }

    public double getScaleMPerPx() { return scaleMPerPx; }
    public void setScaleMPerPx(double scaleMPerPx) { this.scaleMPerPx = scaleMPerPx; }

    public double getPathLossN() { return pathLossN; }
    public void setPathLossN(double pathLossN) { this.pathLossN = pathLossN; }

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

                // 1) AP까지 거리(m)
                double dM = new Point2D(ap.x, ap.y).distance(px, py) * scaleMPerPx;
                dM = Math.max(dM, MIN_DISTANCE_M);

                // 2) 거리 기반 경로손실
                double baseLoss = WifiMath.pathLossDb(dM, b.freqGhz, pathLossN);

                // 3) 직선 경로상의 벽 감쇠
                double wallLoss = WifiMath.wallLossAlong(ap.x, ap.y, px, py, walls);

                // 4) RSSI
                double totalLoss = baseLoss + wallLoss;
                double rssi = rc.txPowerDbm + rc.antennaGain - totalLoss;

                out.add(new RssiResult(rc.ssid, b, rssi));
            }
        }

        out.sort(Comparator.comparingDouble((RssiResult r) -> r.rssiDbm).reversed());
        return out;
    }

    /** ✅ 기존 유지: “최강 RSSI” (히트맵/레전드 포인터용) */
    public double sampleRssiAt(int px, int py) {
        List<RssiResult> list = sampleRssiAllAt(px, py);
        if (list.isEmpty()) return Double.NaN;
        return list.get(0).rssiDbm;
    }
}