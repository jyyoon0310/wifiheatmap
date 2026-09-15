import app.model.*;
import app.engine.DpmPathGrid;
import app.engine.HeatmapGenerator;
import app.engine.FdtdWaveSimulator;
import javafx.scene.image.Image;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * 실측 비교용 헤드리스 하니스.
 * .wifisettings(ZIP/properties) → 환경 재구성 → 8개 지점에서 Legacy/DPM RSSI 측정.
 * FDTD는 별도 처리(상대값 보정 필요).
 */
public class MeasureHarness {

    static Properties loadProps(String path) throws IOException {
        Properties p = new Properties();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(path))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals("settings.properties")) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                    p.load(new InputStreamReader(new ByteArrayInputStream(bos.toByteArray()), "UTF-8"));
                }
            }
        }
        return p;
    }

    static double d(Properties p, String k) { return Double.parseDouble(p.getProperty(k)); }

    public static void main(String[] args) throws Exception {
        String settings = "/Users/jyy/Desktop/hanl.wifisettings";
        Properties p = loadProps(settings);

        WifiEnvironment env = new WifiEnvironment();
        env.setScaleMPerPx(d(p, "scale.m_per_px"));
        env.setPathLossN(Double.parseDouble(p.getProperty("env.path_loss_n", "3.5")));
        env.setClientHeightM(Double.parseDouble(p.getProperty("env.client_height_m", "1.0")));

        // AP 재구성
        int apCount = Integer.parseInt(p.getProperty("ap.count", "0"));
        for (int i = 0; i < apCount; i++) {
            String k = "ap." + i + ".";
            AP ap = new AP();
            ap.name = p.getProperty(k + "name", "AP");
            ap.x = d(p, k + "x"); ap.y = d(p, k + "y");
            ap.heightM = Double.parseDouble(p.getProperty(k + "height_m", "1.0"));
            ap.enabled = Boolean.parseBoolean(p.getProperty(k + "enabled", "true"));
            for (Band b : Band.values()) {
                String rk = k + "radio." + b.name() + ".";
                if (p.getProperty(rk + "enabled") == null) continue;
                RadioConfig rc = ap.radios.get(b);
                rc.enabled = Boolean.parseBoolean(p.getProperty(rk + "enabled"));
                rc.ssid = p.getProperty(rk + "ssid", "");
                rc.txPowerDbm = d(p, rk + "tx_dbm");
                rc.antennaGain = d(p, rk + "gain_dbi");
                rc.channel = Integer.parseInt(p.getProperty(rk + "channel", "1"));
                rc.channelWidth = Integer.parseInt(p.getProperty(rk + "bandwidth_mhz", "20"));
            }
            env.getAps().add(ap);
        }

        // 벽 재구성
        int wallCount = Integer.parseInt(p.getProperty("wall.count", "0"));
        for (int i = 0; i < wallCount; i++) {
            String k = "wall." + i + ".";
            if (p.getProperty(k + "x1") == null) continue;
            Wall w = new Wall();
            w.x1 = d(p, k + "x1"); w.y1 = d(p, k + "y1");
            w.x2 = d(p, k + "x2"); w.y2 = d(p, k + "y2");
            try { w.setMaterial(WallMaterial.valueOf(p.getProperty(k + "material", "CONCRETE_WALL"))); }
            catch (Exception ignore) {}
            w.setAttenuationDb(d(p, k + "att_24_db"), d(p, k + "att_5_db"));
            env.getWalls().add(w);
        }

        System.out.println("AP=" + env.getAps().size() + " walls=" + env.getWalls().size()
                + " scale=" + env.getScaleMPerPx());
        AP ap0 = env.getAps().get(0);
        System.out.printf("AP pos=(%.1f, %.1f)%n", ap0.x, ap0.y);

        // ── 측정 지점 (검출된 빨간점 좌표) ──
        String[] names = {"현관","주방","오른쪽위 침실","드레스옆 화장실","왼쪽 욕실","거실","왼쪽 침실","오른쪽밑 침실"};
        int[][] pts = {{382,174},{468,283},{648,298},{738,338},{326,369},{461,461},{361,493},{689,507}};
        // 실측: [5G, 2.4G]
        double[][] meas = {{-66,-66},{-51,-46},{-66,-58},{-76,-61},{-53,-47},{-40,-45},{-65,-49},{-53,-43}};

        int W = 923, H = 676;
        double scale = env.getScaleMPerPx();
        AP ap = env.getAps().get(0);

        // DPM 그리드 (밴드별: 2.4 / 5)
        DpmPathGrid dpm24 = new DpmPathGrid(W, H, scale, env.getWalls(), Band.GHZ_24);
        dpm24.runDijkstra(ap.x, ap.y, RadioConfig.centerFreqGhz(Band.GHZ_24, ap.radios.get(Band.GHZ_24).channel));
        DpmPathGrid dpm5 = new DpmPathGrid(W, H, scale, env.getWalls(), Band.GHZ_5);
        dpm5.runDijkstra(ap.x, ap.y, RadioConfig.centerFreqGhz(Band.GHZ_5, ap.radios.get(Band.GHZ_5).channel));

        double eirp24 = ap.radios.get(Band.GHZ_24).txPowerDbm + ap.radios.get(Band.GHZ_24).antennaGain;
        double eirp5  = ap.radios.get(Band.GHZ_5).txPowerDbm  + ap.radios.get(Band.GHZ_5).antennaGain;

        // ── 속도 측정 (5GHz 기준, 전체 도면 1회) ──
        long t0, t1;
        // Legacy: 전 지점(8) 합산 시간 → 1지점 평균
        t0 = System.nanoTime();
        for (int rep=0; rep<10; rep++) for (int[] q : pts) legacyRssi(env, q[0], q[1], Band.GHZ_5);
        t1 = System.nanoTime();
        double legacyMsPerPt = (t1-t0)/1e6/(10.0*pts.length);
        // DPM: Dijkstra 1회 전체 sweep 시간
        t0 = System.nanoTime();
        DpmPathGrid dpmT = new DpmPathGrid(W, H, scale, env.getWalls(), Band.GHZ_5);
        dpmT.runDijkstra(ap.x, ap.y, RadioConfig.centerFreqGhz(Band.GHZ_5, ap.radios.get(Band.GHZ_5).channel));
        t1 = System.nanoTime();
        double dpmSweepMs = (t1-t0)/1e6;
        System.out.printf("%n[속도] Legacy %.4f ms/지점 | DPM %.0f ms (전체 1회 sweep, 모든 지점 동시) | FDTD: 아래%n",
                legacyMsPerPt, dpmSweepMs);

        // ── FDTD: 밴드별 전체 장 1회 시뮬 → 각 지점 power 추출 ──
        long f0 = System.nanoTime();
        double[] fdtd5  = runFdtd(env, W, H, Band.GHZ_5,  pts);
        long f1 = System.nanoTime();
        double fdtdMs = (f1-f0)/1e6;
        double[] fdtd24 = runFdtd(env, W, H, Band.GHZ_24, pts);
        System.out.printf("[속도] FDTD %.0f ms (전체 장 1회, 4000스텝, 모든 지점 동시)%n", fdtdMs);

        System.out.println("\n===== 5 GHz =====");
        System.out.printf("%-16s | %6s | %7s | %7s | %7s%n", "지점", "실측", "Legacy", "DPM", "FDTD");
        printBand(env, pts, names, meas, 0, Band.GHZ_5, dpm5, eirp5, fdtd5);

        System.out.println("\n===== 2.4 GHz =====");
        System.out.printf("%-16s | %6s | %7s | %7s | %7s%n", "지점", "실측", "Legacy", "DPM", "FDTD");
        printBand(env, pts, names, meas, 1, Band.GHZ_24, dpm24, eirp24, fdtd24);
    }

    // FDTD 전체 장 1회 시뮬 후 각 지점 power(전기장²) 반환
    static double[] runFdtd(WifiEnvironment env, int W, int H, Band band, int[][] pts) {
        FdtdWaveSimulator sim = new FdtdWaveSimulator(env, W, H, 1, band);
        for (int s = 0; s < 4000; s += 200) sim.step(200);
        double dx = sim.dxMeters(); int pml = sim.pmlCells();
        double scale = env.getScaleMPerPx();
        int nx = sim.gridNx(), ny = sim.gridNy();
        double[] out = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            int gx = (int)Math.round(pts[i][0]*scale/dx) + pml;
            int gy = (int)Math.round(pts[i][1]*scale/dx) + pml;
            gx = Math.max(0, Math.min(nx-1, gx));
            gy = Math.max(0, Math.min(ny-1, gy));
            out[i] = sim.getPowerAt(gx, gy);
        }
        return out;
    }

    static void printBand(WifiEnvironment env, int[][] pts, String[] names, double[][] meas,
                          int measIdx, Band band, DpmPathGrid dpm, double eirp, double[] fdtdPow) {
        // FDTD: 거실(index 5) 기준점에서 실측=모델 되도록 보정 → dBm 환산
        int refIdx = 5;
        double refPow = fdtdPow[refIdx];
        double refMeas = meas[refIdx][measIdx];
        double[] fdtdDbm = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            double rel = (fdtdPow[i] > 0 && refPow > 0) ? 10.0*Math.log10(fdtdPow[i]/refPow) : -99;
            fdtdDbm[i] = refMeas + rel; // 거실 기준 보정된 절대 dBm
        }

        java.util.List<double[]> rows = new ArrayList<>();
        for (int i = 0; i < pts.length; i++) {
            int x = pts[i][0], y = pts[i][1];
            double legacy = legacyRssi(env, x, y, band);
            double dpmRssi = eirp - dpm.getPathLossDb(x, y);
            double m = meas[i][measIdx];
            System.out.printf("%-16s | %6.0f | %7.1f | %7.1f | %7.1f%n", names[i], m, legacy, dpmRssi, fdtdDbm[i]);
            rows.add(new double[]{m, legacy, dpmRssi, fdtdDbm[i]});
        }
        stat("raw", rows);
        // 거실(index 5) 기준 offset 보정 (Legacy/DPM도 동일 기준점)
        double[] base = rows.get(refIdx);
        double offL = base[0]-base[1], offD = base[0]-base[2]; // FDTD는 이미 거실보정됨(off=0)
        java.util.List<double[]> adj = new ArrayList<>();
        for (double[] r : rows) adj.add(new double[]{r[0], r[1]+offL, r[2]+offD, r[3]});
        System.out.printf("  (거실 기준 offset: Legacy %+.1f, DPM %+.1f, FDTD 0.0)%n", offL, offD);
        stat("offset보정", adj);
    }

    static void stat(String label, java.util.List<double[]> rows) {
        double maeL=0,maeD=0,maeF=0,seL=0,seD=0,seF=0; int n=rows.size();
        for (double[] r : rows) {
            maeL+=Math.abs(r[0]-r[1]); maeD+=Math.abs(r[0]-r[2]); maeF+=Math.abs(r[0]-r[3]);
            seL+=(r[0]-r[1])*(r[0]-r[1]); seD+=(r[0]-r[2])*(r[0]-r[2]); seF+=(r[0]-r[3])*(r[0]-r[3]);
        }
        System.out.printf("  [%s] MAE  L %.1f / D %.1f / F %.1f  |  RMSE  L %.1f / D %.1f / F %.1f%n",
                label, maeL/n, maeD/n, maeF/n, Math.sqrt(seL/n), Math.sqrt(seD/n), Math.sqrt(seF/n));
    }

    // Legacy RSSI를 특정 밴드만 골라서 계산
    static double legacyRssi(WifiEnvironment env, int x, int y, Band band) {
        java.util.List<RssiResult> all = env.sampleRssiAllAt(x, y);
        double best = Double.NEGATIVE_INFINITY;
        for (RssiResult r : all) {
            if (r.band == band && r.rssiDbm > best) best = r.rssiDbm;
        }
        return Double.isFinite(best) ? best : Double.NaN;
    }
}
