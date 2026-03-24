package app.engine;

import app.model.AP;
import app.model.Band;
import app.model.RadioConfig;
import app.model.Wall;
import app.model.WallMaterial;
import app.model.WifiEnvironment;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.IntConsumer;

/**
 * 실시간 파동장(유한차분) 시각화용 경량 시뮬레이터.
 * - 정확한 전자기 FDTD 전체 구현은 아니며, UI에서 "전파 퍼짐 양상"을 확인하기 위한 근사 모델이다.
 *
 * 최적화:
 * - 1D 평탄화 배열 (double[] 연속 메모리 → 캐시 지역성, JIT 벡터화)
 * - ForkJoinPool 행 단위 병렬화 (stepOnce, accumulatePower, renderFrame)
 * - PixelWriter.setPixels() 벌크 쓰기 (per-pixel setArgb 제거)
 */
public class FdtdWaveSimulator {
    private static final double C_MPS = 299_792_458.0;
    private static final double DEFAULT_SCALE_M_PER_PX = 0.01;
    private static final double VISUAL_WAVELENGTH_SCALE = 24.0;
    private static final double BASE_C2 = 0.18;
    private static final double BASE_DAMPING = 0.997;
    private static final double MIN_WALL_C2 = 0.02;
    private static final double POWER_EMA_ALPHA = 0.92;
    private static final double DISPLAY_FLOOR_RATIO = 4.0e-6;
    private static final double DISPLAY_MIN_DB = -52.0;
    private static final double DISPLAY_MAX_DB = 0.0;
    private static final double POWER_DETAIL_BLEND = 0.22;

    // ForkJoinPool 병렬화 임계값
    private static final int MIN_PARALLEL_ROWS = 32;
    private static final int MIN_CHUNK = 8;

    private final int widthPx;
    private final int heightPx;
    private final int cellPx;
    private final int gridW;
    private final int gridH;
    private final Band displayBandFilter;
    private final double scaleMPerPx;
    private final double cellSizeM;
    private final double timeStepNs;

    // 1D 평탄화 배열: index = y * gridW + x
    private double[] prev;
    private double[] curr;
    private double[] next;
    private final double[] powerEma;
    private final double[] c2;
    private final double[] damping;

    private final List<Source> sources = new ArrayList<>();

    private final WritableImage frame;
    private final PixelWriter framePw;
    private final int[] argbBuffer; // 벌크 픽셀 쓰기용 버퍼

    // FX 스레드와 분리된 전용 풀 (FX 스레드 풀 경합 방지)
    private final ForkJoinPool pool;

    private long stepCount = 0L;
    private double timeNs = 0.0;
    private double visNorm = 0.15;

    private static final class Source {
        final int gx;
        final int gy;
        final double amp;
        final double omega;
        double phase;

        Source(int gx, int gy, double amp, double omega, double phase) {
            this.gx = gx;
            this.gy = gy;
            this.amp = amp;
            this.omega = omega;
            this.phase = phase;
        }
    }

    public FdtdWaveSimulator(WifiEnvironment env, int widthPx, int heightPx, int cellPx) {
        this(env, widthPx, heightPx, cellPx, null);
    }

    public FdtdWaveSimulator(WifiEnvironment env, int widthPx, int heightPx, int cellPx, Band displayBandFilter) {
        this.widthPx = Math.max(1, widthPx);
        this.heightPx = Math.max(1, heightPx);
        this.cellPx = Math.max(2, cellPx);
        this.gridW = Math.max(3, (int) Math.ceil(this.widthPx / (double) this.cellPx));
        this.gridH = Math.max(3, (int) Math.ceil(this.heightPx / (double) this.cellPx));
        this.displayBandFilter = displayBandFilter;
        double envScale = (env == null) ? Double.NaN : env.getScaleMPerPx();
        this.scaleMPerPx = (Double.isFinite(envScale) && envScale > 1.0e-9) ? envScale : DEFAULT_SCALE_M_PER_PX;
        this.cellSizeM = this.cellPx * this.scaleMPerPx;
        double dt = (Math.sqrt(BASE_C2) * this.cellSizeM / C_MPS) * 1.0e9;
        this.timeStepNs = (Double.isFinite(dt) && dt > 0.0) ? dt : 1.0;

        int n = gridW * gridH;
        this.prev = new double[n];
        this.curr = new double[n];
        this.next = new double[n];
        this.powerEma = new double[n];
        this.c2 = new double[n];
        this.damping = new double[n];

        this.frame = new WritableImage(this.widthPx, this.heightPx);
        this.framePw = frame.getPixelWriter();
        this.argbBuffer = new int[this.widthPx * this.heightPx];

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        this.pool = new ForkJoinPool(threads);

        Arrays.fill(c2, BASE_C2);
        Arrays.fill(damping, BASE_DAMPING);

        buildFromEnvironment(env);
    }

    public int widthPx() { return widthPx; }
    public int heightPx() { return heightPx; }
    public int cellPx() { return cellPx; }
    public long stepCount() { return stepCount; }
    public double timeNs() { return timeNs; }

    public void reset() {
        Arrays.fill(prev, 0.0);
        Arrays.fill(curr, 0.0);
        Arrays.fill(next, 0.0);
        Arrays.fill(powerEma, 0.0);
        for (Source s : sources) s.phase = 0.0;
        stepCount = 0L;
        timeNs = 0.0;
        visNorm = 0.15;
    }

    public void step(int subSteps) {
        int n = Math.max(1, subSteps);
        for (int i = 0; i < n; i++) {
            stepOnce();
        }
    }

    public WritableImage renderFrame() {
        double localMax = 1e-6;
        for (int idx = gridW; idx < (gridH - 1) * gridW; idx++) {
            if (powerEma[idx] > localMax) localMax = powerEma[idx];
        }
        visNorm = Math.max(localMax, visNorm * 0.992);
        visNorm = 0.90 * visNorm + 0.10 * localMax;
        final double norm = Math.max(1e-8, visNorm);
        final double floor = Math.max(norm * DISPLAY_FLOOR_RATIO, 1e-12);

        // 그리드 셀별 색상 계산 → argbBuffer (병렬)
        parallelRows(0, gridH, gy -> {
            int py0 = gy * cellPx;
            if (py0 >= heightPx) return;
            int py1 = Math.min(heightPx, py0 + cellPx);

            for (int gx = 0; gx < gridW; gx++) {
                int px0 = gx * cellPx;
                if (px0 >= widthPx) break;
                int px1 = Math.min(widthPx, px0 + cellPx);

                double pSmooth = smoothedPowerAt(gx, gy);
                double pInst   = currentPowerAt(gx, gy);
                double pMix    = (1.0 - POWER_DETAIL_BLEND) * pSmooth + POWER_DETAIL_BLEND * pInst;
                double pClamped = Math.max(floor, pMix);
                double relDb = 10.0 * Math.log10(pClamped / norm);
                relDb = Math.max(DISPLAY_MIN_DB, Math.min(DISPLAY_MAX_DB, relDb));
                double t = (relDb - DISPLAY_MIN_DB) / (DISPLAY_MAX_DB - DISPLAY_MIN_DB);
                t = Math.pow(Math.max(0.0, Math.min(1.0, t)), 0.82);
                int argb = toArgb(powerColorFromDb(t, relDb));

                for (int py = py0; py < py1; py++) {
                    int rowBase = py * widthPx;
                    for (int px = px0; px < px1; px++) {
                        argbBuffer[rowBase + px] = argb;
                    }
                }
            }
        });

        // 벌크 쓰기: per-pixel setArgb 대신 한 번의 호출
        framePw.setPixels(0, 0, widthPx, heightPx,
                PixelFormat.getIntArgbInstance(), argbBuffer, 0, widthPx);
        return frame;
    }

    private void stepOnce() {
        // ── E-field 업데이트 (행 단위 병렬) ────────────────────────────────────
        parallelRows(1, gridH - 1, y -> {
            int base  = y * gridW;
            int above = (y - 1) * gridW;
            int below = (y + 1) * gridW;
            for (int x = 1; x < gridW - 1; x++) {
                double lap = curr[base + x - 1] + curr[base + x + 1]
                           + curr[above + x]    + curr[below + x]
                           - 4.0 * curr[base + x];
                double v = (2.0 * curr[base + x] - prev[base + x]) + c2[base + x] * lap;
                next[base + x] = v * damping[base + x];
            }
        });

        // ── 1차 Mur 흡수 경계 (sequential: 두 행 + 두 열) ─────────────────────
        for (int x = 0; x < gridW; x++) {
            next[x]                    = next[gridW + x]              * 0.92;
            next[(gridH - 1) * gridW + x] = next[(gridH - 2) * gridW + x] * 0.92;
        }
        for (int y = 0; y < gridH; y++) {
            next[y * gridW]            = next[y * gridW + 1]          * 0.92;
            next[y * gridW + gridW - 1] = next[y * gridW + gridW - 2] * 0.92;
        }

        // ── 소스 주입 (sequential: 소스 수 적음) ───────────────────────────────
        for (Source s : sources) {
            if (s.gx <= 0 || s.gx >= gridW - 1 || s.gy <= 0 || s.gy >= gridH - 1) continue;
            next[s.gy * gridW + s.gx] += s.amp * Math.sin(s.phase);
            s.phase += s.omega;
        }

        // ── 배열 순환 (포인터 스왑, O(1)) ──────────────────────────────────────
        double[] t = prev;
        prev = curr;
        curr = next;
        next = t;

        // ── Power EMA 업데이트 (행 단위 병렬) ──────────────────────────────────
        final double beta = 1.0 - POWER_EMA_ALPHA;
        parallelRows(1, gridH - 1, y -> {
            int base = y * gridW;
            for (int x = 1; x < gridW - 1; x++) {
                int idx = base + x;
                double p = curr[idx] * curr[idx];
                powerEma[idx] = POWER_EMA_ALPHA * powerEma[idx] + beta * p;
            }
        });

        stepCount++;
        timeNs += timeStepNs;
    }

    /**
     * [from, to) 범위의 행을 ForkJoinPool로 병렬 처리한다.
     * 행이 MIN_PARALLEL_ROWS 이하이면 그냥 순차 처리한다.
     */
    private void parallelRows(int from, int to, IntConsumer rowWorker) {
        int total = to - from;
        if (total <= MIN_PARALLEL_ROWS) {
            for (int i = from; i < to; i++) rowWorker.accept(i);
            return;
        }
        int chunkSize = Math.max(MIN_CHUNK, (total + pool.getParallelism() - 1) / pool.getParallelism());
        List<ForkJoinTask<?>> tasks = new ArrayList<>();
        for (int s = from; s < to; s += chunkSize) {
            final int sf = s;
            final int st = Math.min(s + chunkSize, to);
            tasks.add(pool.submit(() -> {
                for (int i = sf; i < st; i++) rowWorker.accept(i);
            }));
        }
        for (ForkJoinTask<?> task : tasks) task.join();
    }

    private void buildFromEnvironment(WifiEnvironment env) {
        Arrays.fill(c2, BASE_C2);
        Arrays.fill(damping, BASE_DAMPING);
        sources.clear();
        if (env == null) return;

        for (Wall w : env.getWalls()) {
            if (w == null) continue;
            applyWall(w);
        }

        for (AP ap : env.getAps()) {
            if (ap == null || !ap.enabled) continue;
            for (Band b : Band.values()) {
                if (displayBandFilter != null && b != displayBandFilter) continue;
                RadioConfig rc = ap.radios.get(b);
                if (rc == null || !rc.enabled) continue;
                Source s = buildSource(ap, b, rc);
                if (s != null) sources.add(s);
            }
        }
    }

    private Source buildSource(AP ap, Band band, RadioConfig rc) {
        int gx = pxToGridX(ap.x);
        int gy = pxToGridY(ap.y);
        double eirpLikeDb = rc.txPowerDbm + rc.antennaGain - rc.bandwidthPenaltyDb();
        double amp = 0.35 + clamp01((eirpLikeDb - 6.0) / 28.0) * 1.05;

        double f = rc.centerFreqGhz();
        double lambdaM = (C_MPS / 1.0e9) / Math.max(0.1, f);
        double visualLambdaM = lambdaM * VISUAL_WAVELENGTH_SCALE;
        double lambdaCells = Math.max(2.0, visualLambdaM / Math.max(1.0e-6, cellSizeM));
        double omega = (2.0 * Math.PI * Math.sqrt(BASE_C2)) / lambdaCells;
        omega = Math.max(0.04, Math.min(1.25, omega));

        // AP 인덱스 기반 위상: 동일한 이름의 AP가 여러 개여도 독립적인 위상을 갖도록 한다.
        int apIdx = sources.size();
        int bandOrd = (band == null) ? 0 : band.ordinal();
        double phase = ((apIdx * 7 + bandOrd * 3) % 16) / 16.0 * Math.PI * 2.0;

        return new Source(gx, gy, amp, omega, phase);
    }

    private void applyWall(Wall w) {
        double db = wallAttenuationDbForSolver(w, displayBandFilter);
        double ampTrans = Math.pow(10.0, -db / 20.0);

        WallMaterial mat = w.getMaterial();
        boolean penetrable = (mat == WallMaterial.DOOR || mat == WallMaterial.WINDOW);
        if (mat == WallMaterial.DOOR) {
            ampTrans = Math.max(ampTrans, 0.70);
        } else if (mat == WallMaterial.WINDOW) {
            ampTrans = Math.max(ampTrans, 0.75);
        }

        double wallC2;
        double wallDamp;
        if (penetrable) {
            wallC2  = Math.max(MIN_WALL_C2, BASE_C2 * (0.40 + ampTrans * 0.45));
            wallDamp = Math.max(0.82, Math.min(0.995, 0.84 + ampTrans * 0.14));
        } else {
            wallC2  = Math.max(MIN_WALL_C2, BASE_C2 * (0.20 + ampTrans * 0.45));
            wallDamp = Math.max(0.72, Math.min(0.99,  0.82 + ampTrans * 0.16));
        }

        int x0 = pxToGridX(w.x1);
        int y0 = pxToGridY(w.y1);
        int x1 = pxToGridX(w.x2);
        int y1 = pxToGridY(w.y2);

        rasterLine(x0, y0, x1, y1, (x, y) -> {
            applyWallCell(x, y, wallC2, wallDamp);
            if (!penetrable) {
                applyWallCell(x + 1, y, wallC2, wallDamp);
                applyWallCell(x - 1, y, wallC2, wallDamp);
                applyWallCell(x, y + 1, wallC2, wallDamp);
                applyWallCell(x, y - 1, wallC2, wallDamp);
            }
        });
    }

    private void applyWallCell(int gx, int gy, double wallC2, double wallDamp) {
        if (gx < 0 || gy < 0 || gx >= gridW || gy >= gridH) return;
        int idx = gy * gridW + gx;
        c2[idx]      = Math.min(c2[idx],      wallC2);
        damping[idx] = Math.min(damping[idx], wallDamp);
    }

    private double wallAttenuationDbForSolver(Wall w, Band band) {
        if (w == null) return 0.0;
        if (band != null) return attenuationDbForBand(w, band);
        double a24 = attenuationDbForBand(w, Band.GHZ_24);
        double a5  = attenuationDbForBand(w, Band.GHZ_5);
        double a6  = attenuationDbForBand(w, Band.GHZ_6);
        return (a24 + a5 + a6) / 3.0;
    }

    private static double attenuationDbForBand(Wall w, Band band) {
        if (w == null || band == null) return 0.0;
        if (band == Band.GHZ_24) return Math.max(0.0, w.attenuationDb24);
        if (band == Band.GHZ_5)  return Math.max(0.0, w.attenuationDb5);
        double a5 = Math.max(0.0, w.attenuationDb5);
        return Math.max(a5 + 1.0, a5 + 20.0 * Math.log10(6.0 / 5.0));
    }

    private int pxToGridX(double px) {
        return Math.max(0, Math.min(gridW - 1, (int) Math.round(px / cellPx)));
    }

    private int pxToGridY(double py) {
        return Math.max(0, Math.min(gridH - 1, (int) Math.round(py / cellPx)));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int toArgb(Color c) {
        int a = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getOpacity())) * 255.0);
        int r = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getRed()))     * 255.0);
        int g = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getGreen()))   * 255.0);
        int b = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getBlue()))    * 255.0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static Color powerColorFromDb(double t, double relDb) {
        double u = Math.max(0.0, Math.min(1.0, t));
        if (u <= 0.0) return Color.rgb(10, 8, 28, 0.40);

        Color c;
        if      (u < 0.10) c = lerp(Color.rgb(10,  8,  28, 0.40), Color.rgb(25,  20,  78, 0.52), u / 0.10);
        else if (u < 0.22) c = lerp(Color.rgb(25, 20,  78, 0.52), Color.rgb(32,  52, 170, 0.60), (u - 0.10) / 0.12);
        else if (u < 0.38) c = lerp(Color.rgb(32, 52, 170, 0.60), Color.rgb(43, 122, 255, 0.68), (u - 0.22) / 0.16);
        else if (u < 0.54) c = lerp(Color.rgb(43,122, 255, 0.68), Color.rgb(64, 232, 255, 0.76), (u - 0.38) / 0.16);
        else if (u < 0.68) c = lerp(Color.rgb(64,232, 255, 0.76), Color.rgb(80, 255,  96, 0.82), (u - 0.54) / 0.14);
        else if (u < 0.82) c = lerp(Color.rgb(80,255,  96, 0.82), Color.rgb(230,255,  56, 0.86), (u - 0.68) / 0.14);
        else if (u < 0.92) c = lerp(Color.rgb(230,255,  56, 0.86), Color.rgb(255,168,  20, 0.90), (u - 0.82) / 0.10);
        else               c = lerp(Color.rgb(255,168,  20, 0.90), Color.rgb(153,  0,   0, 0.94), (u - 0.92) / 0.08);

        double alphaBoost = 0.04 * Math.max(0.0, Math.min(1.0, (relDb + 10.0) / 10.0));
        double alpha = Math.max(0.48, Math.min(0.96, c.getOpacity() + alphaBoost));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private double smoothedPowerAt(int gx, int gy) {
        int x0 = Math.max(0, gx - 1);
        int x1 = Math.min(gridW - 1, gx + 1);
        int y0 = Math.max(0, gy - 1);
        int y1 = Math.min(gridH - 1, gy + 1);

        double sum = 0.0, wsum = 0.0;
        for (int y = y0; y <= y1; y++) {
            int base = y * gridW;
            for (int x = x0; x <= x1; x++) {
                int md = Math.abs(x - gx) + Math.abs(y - gy);
                double w = (md == 0) ? 4.0 : (md == 1 ? 2.0 : 1.0);
                sum  += powerEma[base + x] * w;
                wsum += w;
            }
        }
        return (wsum <= 0.0) ? 0.0 : (sum / wsum);
    }

    private double currentPowerAt(int gx, int gy) {
        int x = Math.max(0, Math.min(gridW - 1, gx));
        int y = Math.max(0, Math.min(gridH - 1, gy));
        double v = curr[y * gridW + x];
        return v * v;
    }

    private static Color lerp(Color a, Color b, double t) {
        double u = Math.max(0.0, Math.min(1.0, t));
        return new Color(
                a.getRed()     + (b.getRed()     - a.getRed())     * u,
                a.getGreen()   + (b.getGreen()   - a.getGreen())   * u,
                a.getBlue()    + (b.getBlue()    - a.getBlue())    * u,
                a.getOpacity() + (b.getOpacity() - a.getOpacity()) * u
        );
    }

    @FunctionalInterface
    private interface CellConsumer {
        void accept(int x, int y);
    }

    private static void rasterLine(int x0, int y0, int x1, int y1, CellConsumer consumer) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        while (true) {
            consumer.accept(x, y);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }
}
