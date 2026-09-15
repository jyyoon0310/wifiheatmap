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
 *
 * 두 종류의 소비자가 있다:
 * <ul>
 *   <li>안드로이드 {@code :engine} 모듈의 GoldenParityTest — 동일 시나리오를 재계산해
 *       데스크톱↔안드로이드 수치 포팅 정확성을 검증한다.
 *       → {@code golden_dpm.txt} / {@code golden_legacy.txt} 만 사용한다.</li>
 *   <li>데스크톱 {@code GoldenRegressionTest} — 엔진 리팩터링 전후 동작 보존을 검증한다.
 *       → 위 2개 + complex 시나리오 3개를 모두 사용한다.</li>
 * </ul>
 *
 * 실행:
 * <pre>
 *   ./gradlew goldenDump                 # src/test/resources/golden 에 기록
 *   ./gradlew goldenDump -Pdir=&lt;경로&gt;    # 지정 경로에 기록
 * </pre>
 *
 * ⚠️ 기본 시나리오 정의({@link #scenario()} / {@link #STEP} / {@link #W} / {@link #H})는
 *    안드로이드 GoldenParityTest 와 반드시 동일해야 한다. 바꾸면 양쪽이 함께 깨진다.
 * ⚠️ 엔진 동작을 의도적으로 바꿨을 때만 재생성할 것. 리팩터링(동작 보존) 시에는
 *    재생성하지 말고 기존 골든으로 검증해야 의미가 있다.
 */
public final class GoldenDump {

    private GoldenDump() {}

    // ── 기본 시나리오 파라미터 (안드로이드 GoldenParityTest 와 동일하게 유지) ──
    public static final int W = 200, H = 200, STEP = 16;
    public static final double SCALE = 0.03, PLN = 3.5;

    // ── complex 시나리오 파라미터 (데스크톱 전용) ──
    // 벽이 많고 재질이 다양하며 복도 구조를 포함한다. DPM 의 16방향 엣지 비용·
    // 재질별 방향전환 손실·도파로 사전계산을 모두 지나가도록 설계했다.
    public static final int CW = 420, CH = 300, CSTEP = 12;
    public static final double CSCALE = 0.03, CPLN = 3.5;

    /** 기본 시나리오: AP 2개 + 벽 2개. 안드로이드와 공유하는 최소 시나리오. */
    public static WifiEnvironment scenario() {
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

    /**
     * complex 시나리오: 복도 + 방 5개 + 재질 6종 + AP 3개.
     *
     * 의도적으로 포함한 것들:
     * <ul>
     *   <li><b>복도</b> — y=120~150 사이 폭 30px(0.9 m) 통로. 도파로 이득 경로를 탄다.</li>
     *   <li><b>문 틈</b> — 벽 사이를 띄워 좁은 개구부를 만든다. 지배경로가 이 틈으로
     *       우회하는지(= 격자 해상도가 문을 탐지하는지) 검증한다.</li>
     *   <li><b>재질 6종</b> — 콘크리트/석고/유리/문/벽돌/엘리베이터. 재질별
     *       {@code cornerLossDb90}, {@code reflectionLossDb}, 밴드별 감쇠가 모두 갈린다.</li>
     *   <li><b>사선 벽</b> — 축 정렬이 아닌 벽. 16방향 엣지 교차 판정을 흔든다.</li>
     * </ul>
     */
    public static WifiEnvironment scenarioComplex() {
        WifiEnvironment env = new WifiEnvironment();
        env.setScaleMPerPx(CSCALE);
        env.setPathLossN(CPLN);

        AP a1 = new AP();
        a1.name = "C1";
        a1.x = 60;  a1.y = 60;
        env.getAps().add(a1);

        AP a2 = new AP();
        a2.name = "C2";
        a2.x = 350; a2.y = 80;
        env.getAps().add(a2);

        AP a3 = new AP();
        a3.name = "C3";
        a3.x = 200; a3.y = 250;
        env.getAps().add(a3);

        List<Wall> w = env.getWalls();

        // 외벽 (콘크리트) — 아래쪽은 복도 진입을 위해 일부러 닫는다
        w.add(new Wall(10,  10,  410, 10,  WallMaterial.CONCRETE_WALL));
        w.add(new Wall(10,  10,  10,  290, WallMaterial.CONCRETE_WALL));
        w.add(new Wall(410, 10,  410, 290, WallMaterial.CONCRETE_WALL));
        w.add(new Wall(10,  290, 410, 290, WallMaterial.CONCRETE_WALL));

        // 복도 벽 (y=120 / y=150) — 중간에 문 틈을 둔다
        w.add(new Wall(10,  120, 150, 120, WallMaterial.CONCRETE_WALL));
        w.add(new Wall(190, 120, 410, 120, WallMaterial.CONCRETE_WALL)); // 150~190 = 개구부
        w.add(new Wall(10,  150, 260, 150, WallMaterial.CONCRETE_WALL));
        w.add(new Wall(300, 150, 410, 150, WallMaterial.CONCRETE_WALL)); // 260~300 = 개구부

        // 방 구획 (석고보드)
        w.add(new Wall(140, 10,  140, 120, WallMaterial.DRY_WALL));
        w.add(new Wall(270, 10,  270, 120, WallMaterial.DRY_WALL));
        w.add(new Wall(120, 150, 120, 290, WallMaterial.DRY_WALL));
        w.add(new Wall(300, 150, 300, 290, WallMaterial.DRY_WALL));

        // 창문 (유리) / 문 / 벽돌 / 엘리베이터 샤프트
        w.add(new Wall(40,  10,  110, 10,  WallMaterial.WINDOW));
        w.add(new Wall(140, 60,  140, 90,  WallMaterial.DOOR));
        w.add(new Wall(300, 200, 380, 200, WallMaterial.BRICK_WALL));
        w.add(new Wall(340, 30,  400, 30,  WallMaterial.ELEVATOR_SHAFT));

        // 사선 벽 — 축 정렬이 아닌 교차 판정 검증
        w.add(new Wall(160, 190, 250, 260, WallMaterial.DRY_WALL));

        return env;
    }

    // ── 격자 산출 ────────────────────────────────────────────────────────────

    /**
     * DPM: {@code generateDpm} 내부 핵심 로직(서브샘플/블러 제외)으로
     * 각 격자점의 최강 RSSI 를 산출한다.
     */
    public static double[] dpmGrid(WifiEnvironment env, int width, int height, int step) {
        Map<AP, Map<Band, DpmPathGrid>> grids = buildDpmGrids(env, width, height);

        List<Double> out = new ArrayList<>();
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                double strongest = -1e9;
                for (AP ap : env.getAps()) {
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

    /**
     * DPM 경로손실 원본 격자 — (AP, 밴드)별 {@code getPathLossDb} 를 그대로 기록한다.
     *
     * {@link #dpmGrid} 는 AP·밴드에 대해 max 를 취하므로 일부 차이가 가려질 수 있다.
     * 이 덤프는 max 없이 전부 기록하므로 {@code DpmPathGrid} 변경에 가장 민감하다 —
     * 엣지 비용 테이블화 같은 내부 리팩터링의 동작 보존 검증에 쓴다.
     */
    public static double[] dpmPathLossGrid(WifiEnvironment env, int width, int height, int step) {
        Map<AP, Map<Band, DpmPathGrid>> grids = buildDpmGrids(env, width, height);

        List<Double> out = new ArrayList<>();
        for (AP ap : env.getAps()) {
            Map<Band, DpmPathGrid> bandGrids = grids.get(ap);
            if (bandGrids == null) continue;
            for (Band b : Band.values()) {
                DpmPathGrid g = bandGrids.get(b);
                if (g == null) continue;
                for (int y = 0; y < height; y += step) {
                    for (int x = 0; x < width; x += step) {
                        out.add(g.getPathLossDb(x, y));
                    }
                }
            }
        }
        return toArray(out);
    }

    /** Legacy: 반사·회절·벽감쇠 포함 {@code sampleRssiAt} 격자. */
    public static double[] legacyGrid(WifiEnvironment env, int width, int height, int step) {
        List<Double> out = new ArrayList<>();
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                out.add(env.sampleRssiAt(x, y));
            }
        }
        return toArray(out);
    }

    // ── 기본 시나리오 고정 진입점 (안드로이드와 공유하는 정의) ──────────────
    public static double[] dpmGrid(WifiEnvironment env)    { return dpmGrid(env, W, H, STEP); }
    public static double[] legacyGrid(WifiEnvironment env) { return legacyGrid(env, W, H, STEP); }

    // ── 내부 헬퍼 ───────────────────────────────────────────────────────────

    private static Map<AP, Map<Band, DpmPathGrid>> buildDpmGrids(WifiEnvironment env,
                                                                 int width, int height) {
        List<Wall> walls = new ArrayList<>(env.getWalls());
        double scale = env.getScaleMPerPx();

        Map<AP, Map<Band, DpmPathGrid>> grids = new IdentityHashMap<>();
        for (AP ap : env.getAps()) {
            if (ap == null || !ap.enabled) continue;
            Map<Band, DpmPathGrid> bandGrids = new EnumMap<>(Band.class);
            for (Band b : Band.values()) {
                RadioConfig rc = ap.radios.get(b);
                if (rc == null || !rc.enabled) continue;
                DpmPathGrid g = new DpmPathGrid(width, height, scale, walls, b);
                g.runDijkstra(ap.x, ap.y, rc.centerFreqGhz());
                bandGrids.put(b, g);
            }
            if (!bandGrids.isEmpty()) grids.put(ap, bandGrids);
        }
        return grids;
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

        // 기본 시나리오 — 안드로이드 GoldenParityTest 와 공유. 정의를 바꾸지 말 것.
        write(dir + "/golden_dpm.txt",    dpmGrid(scenario()));
        write(dir + "/golden_legacy.txt", legacyGrid(scenario()));

        // complex 시나리오 — 데스크톱 리팩터링 회귀 검증용
        WifiEnvironment cx = scenarioComplex();
        write(dir + "/golden_dpm_complex.txt",    dpmGrid(cx, CW, CH, CSTEP));
        write(dir + "/golden_legacy_complex.txt", legacyGrid(cx, CW, CH, CSTEP));
        write(dir + "/golden_dpm_pathloss.txt",   dpmPathLossGrid(cx, CW, CH, CSTEP));

        System.out.println("[GoldenDump] wrote 5 golden files → " + dir);
    }
}
