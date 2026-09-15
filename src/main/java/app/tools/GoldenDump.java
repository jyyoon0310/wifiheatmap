package app.tools;

import app.engine.DpmPathGrid;
import app.model.AP;
import app.model.Band;
import app.model.RadioConfig;
import app.model.Wall;
import app.model.WallMaterial;
import app.model.WifiEnvironment;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 골든 회귀 테스트용 기준 데이터 덤퍼 (데스크톱 엔진 기준).
 *
 * 고정 시나리오에 대해 DPM 지배경로 RSSI 격자와 Legacy(sampleRssiAt) RSSI 격자를
 * 정확한 비트 표현(Double.toHexString)으로 파일에 기록한다.
 * 안드로이드 {@code :engine} 모듈의 GoldenParityTest 가 동일 시나리오를 재계산하여
 * 이 파일들과 일치하는지 검증한다 → 데스크톱↔안드로이드 수치 포팅 정확성 보증.
 *
 * 실행: build.gradle 에 임시 JavaExec 태스크로 등록 후
 *   ./gradlew goldenDump --args="android/engine/src/test/resources/golden"
 *
 * ⚠️ 시나리오 정의(scenario / STEP / W / H)는 GoldenParityTest 와 반드시 동일해야 한다.
 */
public final class GoldenDump {

    // ── 고정 시나리오 파라미터 (GoldenParityTest 와 동일하게 유지) ──
    static final int W = 200, H = 200, STEP = 16;
    static final double SCALE = 0.03, PLN = 3.5;

    static WifiEnvironment scenario() {
        WifiEnvironment env = new WifiEnvironment();
        env.setScaleMPerPx(SCALE);
        env.setPathLossN(PLN);

        AP a1 = new AP();
        a1.name = "A1";
        a1.x = 40;
        a1.y = 40;
        env.getAps().add(a1);

        AP a2 = new AP();
        a2.name = "A2";
        a2.x = 150;
        a2.y = 160;
        env.getAps().add(a2);

        env.getWalls().add(new Wall(100, 10, 100, 140, WallMaterial.CONCRETE_WALL));
        env.getWalls().add(new Wall(20, 100, 120, 100, WallMaterial.DRY_WALL));
        return env;
    }

    /** DPM: generateDpm 내부 핵심 로직(서브샘플/블러 제외)으로 각 격자점 최강 RSSI 산출. */
    static double[] dpmGrid(WifiEnvironment env) {
        List<AP> aps = new ArrayList<>(env.getAps());
        List<Wall> walls = new ArrayList<>(env.getWalls());
        double scale = env.getScaleMPerPx();

        Map<AP, Map<Band, DpmPathGrid>> grids = new IdentityHashMap<>();
        for (AP ap : aps) {
            if (ap == null || !ap.enabled) continue;
            Map<Band, DpmPathGrid> bandGrids = new EnumMap<>(Band.class);
            for (Band b : Band.values()) {
                RadioConfig rc = ap.radios.get(b);
                if (rc == null || !rc.enabled) continue;
                DpmPathGrid g = new DpmPathGrid(W, H, scale, walls, b);
                g.runDijkstra(ap.x, ap.y, rc.centerFreqGhz());
                bandGrids.put(b, g);
            }
            if (!bandGrids.isEmpty()) grids.put(ap, bandGrids);
        }

        List<Double> out = new ArrayList<>();
        for (int y = 0; y < H; y += STEP) {
            for (int x = 0; x < W; x += STEP) {
                double strongest = -1e9;
                for (AP ap : aps) {
                    Map<Band, DpmPathGrid> bandGrids = grids.get(ap);
                    if (bandGrids == null) continue;
                    double best = -1e9;
                    for (Band b : Band.values()) {
                        RadioConfig rc = ap.radios.get(b);
                        if (rc == null || !rc.enabled) continue;
                        DpmPathGrid g = bandGrids.get(b);
                        if (g == null) continue;
                        double pl = g.getPathLossDb(x, y);
                        double rssi = rc.txPowerDbm + rc.antennaGain - pl - rc.bandwidthPenaltyDb();
                        if (rssi > best) best = rssi;
                    }
                    if (best > strongest) strongest = best;
                }
                out.add(strongest);
            }
        }
        return toArray(out);
    }

    /** Legacy: 반사·회절·벽감쇠 포함 sampleRssiAt 격자. */
    static double[] legacyGrid(WifiEnvironment env) {
        List<Double> out = new ArrayList<>();
        for (int y = 0; y < H; y += STEP) {
            for (int x = 0; x < W; x += STEP) {
                out.add(env.sampleRssiAt(x, y));
            }
        }
        return toArray(out);
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static void write(String path, double[] vals) throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            for (double v : vals) w.println(Double.toHexString(v));
        }
    }

    public static void main(String[] args) throws Exception {
        String dir = (args.length > 0) ? args[0] : ".";
        write(dir + "/golden_dpm.txt", dpmGrid(scenario()));
        write(dir + "/golden_legacy.txt", legacyGrid(scenario()));
        System.out.println("[GoldenDump] wrote golden_dpm.txt / golden_legacy.txt → " + dir);
    }
}
