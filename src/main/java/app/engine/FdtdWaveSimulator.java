package app.engine;

import app.engine.fdtd.FdtdConfig;
import app.engine.fdtd.FdtdMaterialGrid;
import app.engine.fdtd.FdtdMaterialGridBuilder;
import app.engine.fdtd.FdtdReferenceMode;
import app.engine.fdtd.FdtdWallPreset;
import app.model.AP;
import app.model.Band;
import app.model.RadioConfig;
import app.model.Wall;
import app.model.WifiEnvironment;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.IntConsumer;

/**
 * Real-time TEz FDTD solver overlay.
 *
 * 최적화:
 * - 1D 평탄화 배열 (캐시 지역성, JIT 자동 벡터화)
 * - ForkJoinPool 행 단위 병렬화 (updateH/E/power)
 * - int[] argb 버퍼 + setPixels() 벌크 쓰기 (per-pixel setArgb 제거)
 */
public class FdtdWaveSimulator {
    private static final double EPS0 = 8.854_187_812_8e-12;
    private static final double MU0 = 1.256_637_062_12e-6;
    private static final double C0 = 299_792_458.0;
    private static final double DEFAULT_SCALE_M_PER_PX = 0.01;

    private static final double POWER_EMA_ALPHA = 0.85;
    private static final double DISPLAY_MIN_DB = -92.0;
    private static final double DISPLAY_MAX_DB = 0.0;
    private static final double DISPLAY_FLOOR_RATIO = 1.0e-11;
    private static final double DISPLAY_FLOOR_DB = -104.0;
    private static final double DISPLAY_ALPHA_CUTOFF_DB = -82.0;
    private static final double AIR_SIGMA = 5.0e-7;
    private static final int SOURCE_RAMP_CYCLES = 5;
    private static final double SOURCE_FREQ_LIMIT_FACTOR = 0.085;
    private static final int[] SRC_OX = {0, 1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] SRC_OY = {0, 0, 0, 1, -1, 1, -1, 1, -1};
    private static final double[] SRC_W = {0.36, 0.12, 0.12, 0.12, 0.12, 0.04, 0.04, 0.04, 0.04};
    private static final int NORM_SOURCE_EXCLUDE_R = 2;
    private static final int MIN_PML_CELLS = 8;
    private static final int MAX_PML_CELLS = 20;
    private static final double MIN_DX_M = 0.004;
    private static final double MAX_DX_M = 0.25;
    private static final double MAX_REALTIME_CELLS = 450_000.0;

    // 병렬화 임계값
    private static final int MIN_PARALLEL_ROWS = 32;
    private static final int MIN_CHUNK_ROWS = 8;

    private final int widthPx;
    private final int heightPx;
    private final int cellPx;
    private final Band displayBandFilter;
    private final double scaleMPerPx;
    private final double refFreqGhz;
    private final double dxMeters;
    private final double invDx;      // 1.0 / dxMeters (곱셈 최적화)
    private final double dtSeconds;
    private final double timeStepNs;

    private final FdtdMaterialGrid grid;
    private final int nx;
    private final int ny;
    private final int nym1;          // ny - 1 (Hx stride)
    private final int nxm1;          // nx - 1 (Hy first dim)
    private final int pml;

    // 1D 평탄화 Yee fields
    private final double[] ez;       // [nx * ny]
    private final double[] ezx;      // [nx * ny]
    private final double[] ezy;      // [nx * ny]
    private final double[] hx;       // [nx * nym1]
    private final double[] hy;       // [nxm1 * ny]

    // PML profiles
    private final double[] sigmaEx;
    private final double[] sigmaEy;
    private final double[] sigmaMx;
    private final double[] sigmaMy;

    // 1D 평탄화 update coefficients
    private final double[] cEx1;     // [nx * ny]
    private final double[] cEx2;     // [nx * ny]
    private final double[] cEy1;     // [nx * ny]
    private final double[] cEy2;     // [nx * ny]
    private final double[] bHx;      // [nx * nym1]
    private final double[] cHx;      // [nx * nym1]
    private final double[] bHy;      // [nxm1 * ny]
    private final double[] cHy;      // [nxm1 * ny]

    private final double[] powerEma; // [nx * ny]
    private final List<Source> sources = new ArrayList<>();

    private final WritableImage frame;
    private final int[] argbBuffer;  // 벌크 픽셀 버퍼

    // 전용 ForkJoinPool (FX thread 경합 방지)
    private final ForkJoinPool pool;

    private long stepCount = 0L;
    private double timeNs = 0.0;
    private double visNorm = 1.0e-6;
    private String lastVisualDebugSummary = "debug: (solver idle)";

    private static final class Source {
        final int gx;
        final int gy;
        final Band band;
        final double amplitude;
        final double omegaDt;
        final int rampSteps;
        double phase;

        Source(int gx, int gy, Band band, double amplitude, double omegaDt, int rampSteps, double phase) {
            this.gx = gx;
            this.gy = gy;
            this.band = band;
            this.amplitude = amplitude;
            this.omegaDt = omegaDt;
            this.rampSteps = rampSteps;
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
        this.displayBandFilter = displayBandFilter;

        double envScale = (env == null) ? Double.NaN : env.getScaleMPerPx();
        this.scaleMPerPx = (Double.isFinite(envScale) && envScale > 1.0e-9) ? envScale : DEFAULT_SCALE_M_PER_PX;
        this.refFreqGhz = selectReferenceFrequencyGhz(env, displayBandFilter);
        this.dxMeters = chooseDxMeters(this.widthPx, this.heightPx, this.scaleMPerPx, this.cellPx, this.refFreqGhz);
        this.invDx = 1.0 / this.dxMeters;
        this.dtSeconds = 0.90 * this.dxMeters / (C0 * Math.sqrt(2.0));
        this.timeStepNs = this.dtSeconds * 1.0e9;

        int pmlCells = choosePmlCells(this.widthPx, this.heightPx, this.cellPx);
        FdtdConfig cfg = new FdtdConfig(
                (displayBandFilter == null) ? Band.GHZ_24 : displayBandFilter,
                this.refFreqGhz * 1.0e9,
                this.dxMeters,
                2000,
                pmlCells,
                20.0e-9,
                1.0,
                20,
                FdtdReferenceMode.AP_NEAR_RING,
                1.0,
                FdtdWallPreset.FROM_WALL,
                false,
                false
        );
        List<Wall> walls = (env == null) ? List.of() : new ArrayList<>(env.getWalls());
        this.grid = FdtdMaterialGridBuilder.build(this.widthPx, this.heightPx, this.scaleMPerPx, cfg, walls);
        this.nx = grid.nx;
        this.ny = grid.ny;
        this.nym1 = Math.max(1, ny - 1);
        this.nxm1 = Math.max(1, nx - 1);
        this.pml = grid.pmlCells;

        // 1D 평탄화 배열 할당
        this.ez = new double[nx * ny];
        this.ezx = new double[nx * ny];
        this.ezy = new double[nx * ny];
        this.hx = new double[nx * nym1];
        this.hy = new double[nxm1 * ny];

        this.sigmaEx = new double[nx];
        this.sigmaEy = new double[ny];
        this.sigmaMx = new double[nx];
        this.sigmaMy = new double[ny];

        this.cEx1 = new double[nx * ny];
        this.cEx2 = new double[nx * ny];
        this.cEy1 = new double[nx * ny];
        this.cEy2 = new double[nx * ny];
        this.bHx = new double[nx * nym1];
        this.cHx = new double[nx * nym1];
        this.bHy = new double[nxm1 * ny];
        this.cHy = new double[nxm1 * ny];
        this.powerEma = new double[nx * ny];

        this.frame = new WritableImage(this.widthPx, this.heightPx);
        this.argbBuffer = new int[this.widthPx * this.heightPx];

        // 전용 ForkJoinPool
        int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        this.pool = new ForkJoinPool(nThreads);

        buildPmlProfiles();
        buildCoefficients();
        buildSources(env);

        System.out.printf(
                "[SolverV2] refFreq=%.3fGHz, scale=%.6f m/px, dx=%.4fm, dt=%.4fns, grid=%dx%d (map=%dx%d, pml=%d), sources=%d, threads=%d%n",
                refFreqGhz, scaleMPerPx, dxMeters, timeStepNs, nx, ny, grid.mapNx, grid.mapNy, pml, sources.size(), nThreads
        );
    }

    // ─── inline index helpers ──────────────────────────
    private int eIdx(int i, int j) { return i * ny + j; }
    private int hxIdx(int i, int j) { return i * nym1 + j; }
    private int hyIdx(int i, int j) { return i * ny + j; }

    // ─── public API (변경 없음) ────────────────────────
    public int widthPx() { return widthPx; }
    public int heightPx() { return heightPx; }
    public int cellPx() { return cellPx; }
    public long stepCount() { return stepCount; }
    public double timeNs() { return timeNs; }
    public double dxMeters() { return dxMeters; }
    public double dtSeconds() { return dtSeconds; }
    public int pmlCells() { return pml; }
    public Band displayBandFilter() { return displayBandFilter; }
    public int sourceCount() { return sources.size(); }
    public int gridNx() { return nx; }
    public int gridNy() { return ny; }

    /** FDTD power EMA 격자 직접 접근 (AP추천 FDTD 검증용). */
    public double getPowerAt(int gx, int gy) {
        if (gx < 0 || gx >= nx || gy < 0 || gy >= ny) return 0.0;
        double v = powerEma[eIdx(gx, gy)];
        return Double.isFinite(v) ? v : 0.0;
    }
    public double courantNumber() { return C0 * dtSeconds * Math.sqrt(2.0) / dxMeters; }
    public String visualDebugSummary() { return lastVisualDebugSummary; }

    public void reset() {
        clearFieldState();
        for (Source s : sources) s.phase = 0.0;
    }

    public void refreshSources(WifiEnvironment env) {
        buildSources(env);
        clearFieldState();
    }

    public record MaterialStats(int airCells, int wallCells, int doorCells, int windowCells) {}

    public MaterialStats materialStats() {
        int air = 0, wall = 0, door = 0, window = 0;
        for (int mx = 0; mx < grid.mapNx; mx++) {
            int gx = mx + pml;
            for (int my = 0; my < grid.mapNy; my++) {
                int gy = my + pml;
                int code = grid.materialCode[gx][gy];
                switch (code) { case 1 -> wall++; case 2 -> door++; case 3 -> window++; default -> air++; }
            }
        }
        return new MaterialStats(air, wall, door, window);
    }

    public String diagnosticsSummary() {
        MaterialStats ms = materialStats();
        return String.format(
                "[SolverV2] dx=%.4fm dt=%.4fns CFL=%.4f pml=%d src=%d materials(air=%d wall=%d door=%d window=%d)",
                dxMeters, timeStepNs, courantNumber(), pml, sourceCount(),
                ms.airCells(), ms.wallCells(), ms.doorCells(), ms.windowCells()
        );
    }

    // ─── Shutdown ──────────────────────────────────────
    public void shutdown() {
        pool.shutdownNow();
    }

    // ─── Step ──────────────────────────────────────────
    public void step(int subSteps) {
        int n = Math.max(1, subSteps);
        for (int i = 0; i < n; i++) stepOnce();
    }

    private void stepOnce() {
        updateH();
        updateE();
        injectSources();
        accumulatePower();
        stepCount++;
        timeNs += timeStepNs;
    }

    // ─── H update (병렬) ───────────────────────────────
    private void updateH() {
        // Hx: 각 행 i 독립 (읽기: ez[i,j], ez[i,j+1])
        parallelRows(0, nx, i -> {
            int eBase = i * ny;
            int hBase = i * nym1;
            for (int j = 0; j < nym1; j++) {
                double dEdy = (ez[eBase + j + 1] - ez[eBase + j]) * invDx;
                double next = bHx[hBase + j] * hx[hBase + j] - cHx[hBase + j] * dEdy;
                hx[hBase + j] = Double.isFinite(next) ? next : 0.0;
            }
        });

        // Hy: 각 행 i 독립 (읽기: ez[i,j], ez[i+1,j])
        parallelRows(0, nxm1, i -> {
            int eBase = i * ny;
            int eNextBase = (i + 1) * ny;
            int hBase = i * ny;
            for (int j = 0; j < ny; j++) {
                double dEdx = (ez[eNextBase + j] - ez[eBase + j]) * invDx;
                double next = bHy[hBase + j] * hy[hBase + j] + cHy[hBase + j] * dEdx;
                hy[hBase + j] = Double.isFinite(next) ? next : 0.0;
            }
        });
    }

    // ─── E update (병렬) ───────────────────────────────
    private void updateE() {
        parallelRows(1, nx - 1, i -> {
            int eBase = i * ny;
            int hyBase = i * ny;
            int hyPrevBase = (i - 1) * ny;
            int hxBase = i * nym1;
            for (int j = 1; j < ny - 1; j++) {
                double curlHy = (hy[hyBase + j] - hy[hyPrevBase + j]) * invDx;
                double curlHx = (hx[hxBase + j] - hx[hxBase + j - 1]) * invDx;

                double ex = cEx1[eBase + j] * ezx[eBase + j] + cEx2[eBase + j] * curlHy;
                double ey = cEy1[eBase + j] * ezy[eBase + j] - cEy2[eBase + j] * curlHx;
                if (!Double.isFinite(ex)) ex = 0.0;
                if (!Double.isFinite(ey)) ey = 0.0;
                ezx[eBase + j] = ex;
                ezy[eBase + j] = ey;
                double sum = ex + ey;
                ez[eBase + j] = Double.isFinite(sum) ? sum : 0.0;
            }
        });
    }

    // ─── Source injection (순차) ────────────────────────
    private void injectSources() {
        for (Source s : sources) {
            if (s.gx <= 0 || s.gx >= nx - 1 || s.gy <= 0 || s.gy >= ny - 1) continue;

            double ramp = 1.0;
            if (stepCount < s.rampSteps) {
                double u = clampDouble(stepCount / (double) s.rampSteps, 0.0, 1.0);
                ramp = Math.sin(0.5 * Math.PI * u);
                ramp *= ramp;
            }

            double src = s.amplitude * Math.sin(s.phase) * ramp;
            for (int k = 0; k < SRC_OX.length; k++) {
                int gx = s.gx + SRC_OX[k];
                int gy = s.gy + SRC_OY[k];
                if (gx <= 0 || gx >= nx - 1 || gy <= 0 || gy >= ny - 1) continue;
                double v = src * SRC_W[k];
                int id = eIdx(gx, gy);
                ezx[id] += 0.5 * v;
                ezy[id] += 0.5 * v;
                ez[id] = ezx[id] + ezy[id];
            }
            s.phase += s.omegaDt;
        }
    }

    // ─── Power EMA (병렬) ──────────────────────────────
    private void accumulatePower() {
        double beta = 1.0 - POWER_EMA_ALPHA;
        parallelRows(1, nx - 1, i -> {
            int base = i * ny;
            for (int j = 1; j < ny - 1; j++) {
                int id = base + j;
                double e = ez[id];
                if (!Double.isFinite(e)) {
                    e = 0.0;
                    ez[id] = 0.0;
                    ezx[id] = 0.0;
                    ezy[id] = 0.0;
                }
                double p = e * e;
                if (!Double.isFinite(p)) p = 0.0;
                double prev = powerEma[id];
                if (!Double.isFinite(prev) || prev < 0.0) prev = 0.0;
                double next = POWER_EMA_ALPHA * prev + beta * p;
                powerEma[id] = Double.isFinite(next) ? next : 0.0;
            }
        });
    }

    // ─── renderFrame (벌크 픽셀 쓰기) ──────────────────
    public WritableImage renderFrame() {
        double localMax = 1.0e-12;
        double localMaxOutsideSource = 1.0e-12;
        double localSumOutsideSource = 0.0;
        int localCountOutsideSource = 0;
        for (int mx = 0; mx < grid.mapNx; mx++) {
            int gx = mx + pml;
            for (int my = 0; my < grid.mapNy; my++) {
                int gy = my + pml;
                double v = powerEma[eIdx(gx, gy)];
                if (!Double.isFinite(v) || v < 0.0) {
                    v = 0.0;
                    powerEma[eIdx(gx, gy)] = 0.0;
                }
                localMax = Math.max(localMax, v);
                if (!isSourceCoreCell(gx, gy)) {
                    localMaxOutsideSource = Math.max(localMaxOutsideSource, v);
                    localSumOutsideSource += v;
                    localCountOutsideSource++;
                }
            }
        }

        if (!Double.isFinite(localMax) || localMax <= 0.0) localMax = 1.0e-12;
        if (!Double.isFinite(localMaxOutsideSource) || localMaxOutsideSource <= 0.0) localMaxOutsideSource = localMax;

        double localMeanOutsideSource = localSumOutsideSource / Math.max(1, localCountOutsideSource);
        if (!Double.isFinite(localMeanOutsideSource) || localMeanOutsideSource <= 0.0)
            localMeanOutsideSource = localMaxOutsideSource * 0.05;

        double targetNorm = Math.max(1.0e-12, localMaxOutsideSource);
        if (!Double.isFinite(visNorm) || visNorm <= 0.0) visNorm = targetNorm;
        visNorm = 0.92 * visNorm + 0.08 * targetNorm;
        visNorm = clampDouble(visNorm, targetNorm * 0.50, targetNorm * 2.20);
        double norm = Math.max(1.0e-12, visNorm);
        double floorFromDb = norm * Math.pow(10.0, DISPLAY_FLOOR_DB / 10.0);
        double floor = Math.max(Math.max(floorFromDb, norm * DISPLAY_FLOOR_RATIO), 1.0e-20);
        double alphaCutoffPower = norm * Math.pow(10.0, DISPLAY_ALPHA_CUTOFF_DB / 10.0);

        double pxPerCellX = widthPx / (double) grid.mapNx;
        double pxPerCellY = heightPx / (double) grid.mapNy;
        int totalCells = Math.max(1, grid.mapNx * grid.mapNy);

        // 병렬 ARGB 계산 → argbBuffer
        final double fNorm = norm;
        final double fFloor = floor;
        final double fAlphaCutoff = alphaCutoffPower;
        final double fLocalMax = localMax;
        final double fLocalMaxOut = localMaxOutsideSource;
        final double fLocalMeanOut = localMeanOutsideSource;

        // 행별 visible cell 카운트 (per-thread 누적 후 합산)
        int[] visPerRow = new int[grid.mapNx];
        parallelRows(0, grid.mapNx, mx -> {
            int gx = mx + pml;
            int px0 = clamp((int) Math.floor(mx * pxPerCellX), 0, widthPx - 1);
            int px1 = clamp((int) Math.ceil((mx + 1) * pxPerCellX), px0 + 1, widthPx);
            int rowVis = 0;
            for (int my = 0; my < grid.mapNy; my++) {
                int gy = my + pml;
                int py0 = clamp((int) Math.floor(my * pxPerCellY), 0, heightPx - 1);
                int py1 = clamp((int) Math.ceil((my + 1) * pxPerCellY), py0 + 1, heightPx);

                double rawP = powerEma[eIdx(gx, gy)];
                if (!Double.isFinite(rawP) || rawP < 0.0) rawP = 0.0;
                double p = Math.max(fFloor, rawP);
                double ratio = p / fNorm;
                if (!Double.isFinite(ratio) || ratio <= 0.0) ratio = fFloor / fNorm;
                double relDb = 10.0 * Math.log10(ratio);
                if (!Double.isFinite(relDb)) relDb = DISPLAY_MIN_DB;
                relDb = clampDouble(relDb, DISPLAY_MIN_DB, DISPLAY_MAX_DB);
                double t = (relDb - DISPLAY_MIN_DB) / (DISPLAY_MAX_DB - DISPLAY_MIN_DB);
                if (!Double.isFinite(t)) t = 0.0;
                t = Math.pow(clampDouble(t, 0.0, 1.0), 0.82);
                int argb = toArgb(powerColorFromDb(t, relDb));
                if (rawP >= fAlphaCutoff) rowVis++;

                // argbBuffer에 직접 쓰기
                for (int py = py0; py < py1; py++) {
                    int rowOff = py * widthPx;
                    Arrays.fill(argbBuffer, rowOff + px0, rowOff + px1, argb);
                }
            }
            visPerRow[mx] = rowVis;
        });

        // 벌크 setPixels
        frame.getPixelWriter().setPixels(0, 0, widthPx, heightPx,
                PixelFormat.getIntArgbInstance(), argbBuffer, 0, widthPx);

        int visibleCells = 0;
        for (int v : visPerRow) visibleCells += v;
        double coverage = 100.0 * visibleCells / totalCells;
        lastVisualDebugSummary = String.format(
                "debug max=%.3e outMax=%.3e mean=%.3e norm=%.3e floor=%.3e vis=%.1f%%",
                fLocalMax, fLocalMaxOut, fLocalMeanOut, fNorm, fFloor, coverage
        );

        return frame;
    }

    // ─── 병렬 행 실행 유틸리티 ─────────────────────────
    private void parallelRows(int from, int to, IntConsumer rowWorker) {
        int totalRows = to - from;
        if (totalRows <= MIN_PARALLEL_ROWS || pool.getParallelism() <= 1) {
            for (int i = from; i < to; i++) rowWorker.accept(i);
            return;
        }
        int parallelism = pool.getParallelism();
        int chunkSize = Math.max(MIN_CHUNK_ROWS, (totalRows + parallelism - 1) / parallelism);
        List<ForkJoinTask<?>> tasks = new ArrayList<>();
        for (int s = from; s < to; s += chunkSize) {
            int sf = s, st = Math.min(s + chunkSize, to);
            tasks.add(pool.submit(() -> {
                for (int i = sf; i < st; i++) rowWorker.accept(i);
            }));
        }
        for (ForkJoinTask<?> t : tasks) t.join();
    }

    // ─── 초기화 ────────────────────────────────────────
    private void clearFieldState() {
        Arrays.fill(ez, 0.0);
        Arrays.fill(ezx, 0.0);
        Arrays.fill(ezy, 0.0);
        Arrays.fill(hx, 0.0);
        Arrays.fill(hy, 0.0);
        Arrays.fill(powerEma, 0.0);
        stepCount = 0L;
        timeNs = 0.0;
        visNorm = 1.0e-6;
        lastVisualDebugSummary = "debug: field reset";
    }

    private void buildPmlProfiles() {
        double m = 3.5;
        double r0 = (pml <= 8) ? 1.0e-3 : (pml <= 12) ? 1.0e-5 : 1.0e-6;
        double sigmaEmax = -((m + 1.0) * Math.log(r0)) * (EPS0 * C0) / (2.0 * Math.max(1, pml) * dxMeters);
        double sigmaMmax = sigmaEmax * (MU0 / EPS0);

        for (int i = 0; i < nx; i++) {
            int dist = Math.min(i, nx - 1 - i);
            double ratio = (dist < pml) ? ((pml - dist) / (double) pml) : 0.0;
            double g = Math.pow(ratio, m);
            sigmaEx[i] = sigmaEmax * g;
            sigmaMx[i] = sigmaMmax * g;
        }
        for (int j = 0; j < ny; j++) {
            int dist = Math.min(j, ny - 1 - j);
            double ratio = (dist < pml) ? ((pml - dist) / (double) pml) : 0.0;
            double g = Math.pow(ratio, m);
            sigmaEy[j] = sigmaEmax * g;
            sigmaMy[j] = sigmaMmax * g;
        }
    }

    private void buildCoefficients() {
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                int id = eIdx(i, j);
                double eps = EPS0 * Math.max(1.0e-6, grid.epsR[i][j]);
                double sigmaMat = Math.max(0.0, grid.sigma[i][j]) + AIR_SIGMA;

                double sx = (sigmaMat + sigmaEx[i]) * dtSeconds / (2.0 * eps);
                cEx1[id] = (1.0 - sx) / (1.0 + sx);
                cEx2[id] = (dtSeconds / eps) / (1.0 + sx);

                double sy = (sigmaMat + sigmaEy[j]) * dtSeconds / (2.0 * eps);
                cEy1[id] = (1.0 - sy) / (1.0 + sy);
                cEy2[id] = (dtSeconds / eps) / (1.0 + sy);
            }
        }

        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nym1; j++) {
                int hid = hxIdx(i, j);
                double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i][j + 1]);
                double mu = MU0 * Math.max(1.0e-6, muRavg);
                double s = sigmaMy[j] * dtSeconds / (2.0 * mu);
                bHx[hid] = (1.0 - s) / (1.0 + s);
                cHx[hid] = (dtSeconds / mu) / (1.0 + s);
            }
        }

        for (int i = 0; i < nxm1; i++) {
            for (int j = 0; j < ny; j++) {
                int hid = hyIdx(i, j);
                double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i + 1][j]);
                double mu = MU0 * Math.max(1.0e-6, muRavg);
                double s = sigmaMx[i] * dtSeconds / (2.0 * mu);
                bHy[hid] = (1.0 - s) / (1.0 + s);
                cHy[hid] = (dtSeconds / mu) / (1.0 + s);
            }
        }
    }

    private void buildSources(WifiEnvironment env) {
        sources.clear();
        if (env == null) return;

        int clampedFreqCount = 0;
        for (AP ap : env.getAps()) {
            if (ap == null || !ap.enabled) continue;
            for (Band band : Band.values()) {
                if (displayBandFilter != null && band != displayBandFilter) continue;
                RadioConfig rc = ap.radios.get(band);
                if (rc == null || !rc.enabled) continue;

                int gx = clamp(grid.toGridX(ap.x), 1, nx - 2);
                int gy = clamp(grid.toGridY(ap.y), 1, ny - 2);
                double fHz = Math.max(1.0, rc.centerFreqGhz() * 1.0e9);
                double fNyquistSafe = SOURCE_FREQ_LIMIT_FACTOR / dtSeconds;
                double fEff = Math.min(fHz, fNyquistSafe);
                if (fEff < fHz) clampedFreqCount++;

                double omegaDt = 2.0 * Math.PI * fEff * dtSeconds;
                int periodSteps = Math.max(1, (int) Math.round((1.0 / fEff) / dtSeconds));
                int rampSteps = Math.max(periodSteps * SOURCE_RAMP_CYCLES, 20);
                double phase = initialPhase(ap, band);
                double amp = sourceAmplitudeFromEirpDbm(rc.txPowerDbm + rc.antennaGain);
                sources.add(new Source(gx, gy, band, amp, omegaDt, rampSteps, phase));
            }
        }

        if (clampedFreqCount > 0) {
            System.out.printf(
                    "[SolverV2] %d source(s) frequency clamped by grid Nyquist (dt=%.4fns). Increase resolution for full-band fidelity.%n",
                    clampedFreqCount, timeStepNs
            );
        }
    }

    private boolean isSourceCoreCell(int gx, int gy) {
        int r2 = NORM_SOURCE_EXCLUDE_R * NORM_SOURCE_EXCLUDE_R;
        for (Source s : sources) {
            int dx = gx - s.gx;
            int dy = gy - s.gy;
            if (dx * dx + dy * dy <= r2) return true;
        }
        return false;
    }

    // ─── 정적 유틸리티 (변경 없음) ────────────────────
    private static double selectReferenceFrequencyGhz(WifiEnvironment env, Band bandFilter) {
        if (bandFilter == Band.GHZ_24) return 2.437;
        if (bandFilter == Band.GHZ_5) return 5.180;
        if (bandFilter == Band.GHZ_6) return 6.110;
        if (env == null) return 2.437;
        double sum = 0.0;
        int count = 0;
        for (AP ap : env.getAps()) {
            if (ap == null || !ap.enabled) continue;
            for (Band b : Band.values()) {
                RadioConfig rc = ap.radios.get(b);
                if (rc == null || !rc.enabled) continue;
                sum += Math.max(2.0, rc.centerFreqGhz());
                count++;
            }
        }
        return (count <= 0) ? 2.437 : (sum / count);
    }

    private static double chooseDxMeters(int widthPx, int heightPx, double scaleMPerPx, int cellPx, double refFreqGhz) {
        double baseDx = Math.max(1.0e-6, cellPx * scaleMPerPx);
        double lambda = C0 / Math.max(1.0, refFreqGhz * 1.0e9);
        double targetDx = Math.max(MIN_DX_M, lambda / 8.0);
        double dx = Math.min(baseDx, targetDx);
        double mapWidthM = Math.max(1.0e-6, widthPx * scaleMPerPx);
        double mapHeightM = Math.max(1.0e-6, heightPx * scaleMPerPx);
        double cells = (mapWidthM / dx) * (mapHeightM / dx);
        if (cells > MAX_REALTIME_CELLS) {
            double grow = Math.sqrt(cells / MAX_REALTIME_CELLS);
            dx *= grow;
        }
        return clampDouble(dx, MIN_DX_M, MAX_DX_M);
    }

    private static int choosePmlCells(int widthPx, int heightPx, int cellPx) {
        int gw = Math.max(4, (int) Math.ceil(widthPx / (double) Math.max(2, cellPx)));
        int gh = Math.max(4, (int) Math.ceil(heightPx / (double) Math.max(2, cellPx)));
        int bySize = Math.max(MIN_PML_CELLS, Math.min(MAX_PML_CELLS, Math.min(gw, gh) / 10));
        return clamp(bySize, MIN_PML_CELLS, MAX_PML_CELLS);
    }

    private static double sourceAmplitudeFromEirpDbm(double eirpDbm) {
        double t = clampDouble((eirpDbm - 5.0) / 30.0, 0.0, 1.0);
        return 10.0 * (0.35 + 1.45 * t);
    }

    private static double initialPhase(AP ap, Band band) {
        int h = (ap == null || ap.name == null) ? 0 : ap.name.hashCode();
        int b = (band == null) ? 0 : band.ordinal() * 7919;
        int v = h * 31 + b;
        return ((v & 0xffff) / 65535.0) * Math.PI * 2.0;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampDouble(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int toArgb(Color c) {
        int a = (int) Math.round(clampDouble(c.getOpacity(), 0.0, 1.0) * 255.0);
        int r = (int) Math.round(clampDouble(c.getRed(), 0.0, 1.0) * 255.0);
        int g = (int) Math.round(clampDouble(c.getGreen(), 0.0, 1.0) * 255.0);
        int b = (int) Math.round(clampDouble(c.getBlue(), 0.0, 1.0) * 255.0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static Color powerColorFromDb(double t, double relDb) {
        double u = clampDouble(t, 0.0, 1.0);
        if (u <= 0.0 || relDb <= DISPLAY_ALPHA_CUTOFF_DB) return Color.rgb(10, 8, 28, 0.0);

        Color c;
        if (u < 0.10) {
            c = lerp(Color.rgb(10, 8, 28, 0.40), Color.rgb(25, 20, 78, 0.52), u / 0.10);
        } else if (u < 0.22) {
            c = lerp(Color.rgb(25, 20, 78, 0.52), Color.rgb(32, 52, 170, 0.60), (u - 0.10) / 0.12);
        } else if (u < 0.38) {
            c = lerp(Color.rgb(32, 52, 170, 0.60), Color.rgb(43, 122, 255, 0.68), (u - 0.22) / 0.16);
        } else if (u < 0.54) {
            c = lerp(Color.rgb(43, 122, 255, 0.68), Color.rgb(64, 232, 255, 0.76), (u - 0.38) / 0.16);
        } else if (u < 0.68) {
            c = lerp(Color.rgb(64, 232, 255, 0.76), Color.rgb(80, 255, 96, 0.82), (u - 0.54) / 0.14);
        } else if (u < 0.82) {
            c = lerp(Color.rgb(80, 255, 96, 0.82), Color.rgb(230, 255, 56, 0.86), (u - 0.68) / 0.14);
        } else if (u < 0.92) {
            c = lerp(Color.rgb(230, 255, 56, 0.86), Color.rgb(255, 168, 20, 0.90), (u - 0.82) / 0.10);
        } else {
            c = lerp(Color.rgb(255, 168, 20, 0.90), Color.rgb(153, 0, 0, 0.94), (u - 0.92) / 0.08);
        }

        double visibility = clampDouble((relDb - DISPLAY_ALPHA_CUTOFF_DB) / 30.0, 0.0, 1.0);
        double alpha = clampDouble((0.06 + c.getOpacity() * 0.84) * Math.pow(visibility, 0.70), 0.0, 0.92);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static Color lerp(Color a, Color b, double t) {
        double u = clampDouble(t, 0.0, 1.0);
        return new Color(
                a.getRed() + (b.getRed() - a.getRed()) * u,
                a.getGreen() + (b.getGreen() - a.getGreen()) * u,
                a.getBlue() + (b.getBlue() - a.getBlue()) * u,
                a.getOpacity() + (b.getOpacity() - a.getOpacity()) * u
        );
    }
}
