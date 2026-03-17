package app.solver.v2;

import app.engine.FdtdWaveSimulator;
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
import app.solver.v2.metal.MetalNativeBridge;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;

/**
 * Native Metal backend (macOS) for Solver v2.
 *
 * 실질적인 FDTD update 루프(H/E/Power)는 JNI를 통해 Metal compute kernel로 실행한다.
 * UI/재질/소스/렌더링 파이프라인은 기존 설계와 동일하게 유지한다.
 */
public final class MetalGpuWaveSolver implements GpuWaveSolver {
    private static final double C0 = 299_792_458.0;
    private static final double DISPLAY_ALPHA_CUTOFF_DB = -82.0;
    private static volatile boolean printedUnavailable = false;

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            return false;
        }
        boolean ok = MetalNativeBridge.isNativeAvailable();
        if (!ok && !printedUnavailable) {
            printedUnavailable = true;
            System.out.println("[SolverV2][Metal] Metal native backend를 로드하지 못해 CPU fallback 합니다.");
        }
        return ok;
    }

    @Override
    public Session createSession(WifiEnvironment env,
                                 int widthPx,
                                 int heightPx,
                                 int cellPx,
                                 Band displayBandFilter) {
        try {
            return new SessionImpl(env, widthPx, heightPx, cellPx, displayBandFilter);
        } catch (Throwable ex) {
            System.err.println("[SolverV2][Metal] 세션 초기화 실패: " + ex.getMessage());
            return null;
        }
    }

    private static final class SessionImpl implements GpuWaveSolver.Session {
        private static final double EPS0 = 8.854_187_812_8e-12;
        private static final double MU0 = 1.256_637_062_12e-6;
        private static final double C0 = 299_792_458.0;
        private static final double DEFAULT_SCALE_M_PER_PX = 0.01;

        // Soft Source 전환 후 CPU(FdtdWaveSimulator)와 동일하게 맞춤
        private static final double POWER_EMA_ALPHA = 0.85;  // 0.94→0.85: Soft source 초기 신호 포착
        private static final double DISPLAY_MIN_DB = -92.0;
        private static final double DISPLAY_MAX_DB = 0.0;
        private static final double DISPLAY_FLOOR_RATIO = 1.0e-11;
        private static final double DISPLAY_FLOOR_DB = -104.0;
        private static final int SOURCE_RAMP_CYCLES = 5;  // 20→5: Soft source ramp 초기 소멸 방지
        // f_eff <= SOURCE_FREQ_LIMIT_FACTOR / dt
        // SOURCE_FREQ_LIMIT_FACTOR≈0.085 -> 약 8 cells/λ 확보, 별모양(수치 이방성) 완화
        private static final double SOURCE_FREQ_LIMIT_FACTOR = 0.085;
        private static final int[] SRC_OX = {0, 1, -1, 0, 0, 1, 1, -1, -1};
        private static final int[] SRC_OY = {0, 0, 0, 1, -1, 1, -1, 1, -1};
        private static final float[] SRC_W = {0.36f, 0.12f, 0.12f, 0.12f, 0.12f, 0.04f, 0.04f, 0.04f, 0.04f};
        private static final int NORM_SOURCE_EXCLUDE_R = 2;
        private static final int MIN_PML_CELLS = 8;
        private static final int MAX_PML_CELLS = 20;
        private static final double MIN_DX_M = 0.004;
        private static final double MAX_DX_M = 0.25;
        private static final double MAX_REALTIME_CELLS = 450_000.0;

        private static final Cleaner CLEANER = Cleaner.create();

        private final int widthPx;
        private final int heightPx;
        private final int cellPx;
        private final Band displayBandFilter;
        private final double scaleMPerPx;
        private final double refFreqGhz;
        private final double dxMeters;
        private final double dtSeconds;
        private final double timeStepNs;

        private final FdtdMaterialGrid grid;
        private final int nx;
        private final int ny;
        private final int nym1;
        private final int pml;
        private final int cellCount;
        private final int hxCount;
        private final int hyCount;

        // Metal native handle
        private final NativeCleanup cleanup;
        private final Cleaner.Cleanable cleanable;
        private volatile boolean closed = false;

        // coefficients (flattened)
        private final float[] cEx1;
        private final float[] cEx2;
        private final float[] cEy1;
        private final float[] cEy2;
        private final float[] bHx;
        private final float[] cHx;
        private final float[] bHy;
        private final float[] cHy;
        private final float[] power;

        private final List<Source> sources = new ArrayList<>();
        private int[] sourceX = new int[0];
        private int[] sourceY = new int[0];
        private float[] sourceNow = new float[0];

        private final WritableImage frame;
        private final PixelWriter framePw;
        private final FdtdWaveSimulator.MaterialStats materialStats;

        private long stepCount = 0L;
        private double timeNs = 0.0;
        private double visNorm = 1.0e-6;
        private String lastVisualDebugSummary = "debug: (solver idle)";

        private static final class Source {
            final int gx;
            final int gy;
            final Band band;
            final float amplitude;
            final float omegaDt;
            final int rampSteps;
            float phase;

            Source(int gx, int gy, Band band, float amplitude, float omegaDt, int rampSteps, float phase) {
                this.gx = gx;
                this.gy = gy;
                this.band = band;
                this.amplitude = amplitude;
                this.omegaDt = omegaDt;
                this.rampSteps = rampSteps;
                this.phase = phase;
            }
        }

        private static final class NativeCleanup implements Runnable {
            private long handle;

            NativeCleanup(long handle) {
                this.handle = handle;
            }

            synchronized long handle() {
                return handle;
            }

            @Override
            public synchronized void run() {
                if (handle != 0L) {
                    MetalNativeBridge.destroy(handle);
                    handle = 0L;
                }
            }
        }

        SessionImpl(WifiEnvironment env, int widthPx, int heightPx, int cellPx, Band displayBandFilter) {
            this.widthPx = Math.max(1, widthPx);
            this.heightPx = Math.max(1, heightPx);
            this.cellPx = Math.max(2, cellPx);
            this.displayBandFilter = displayBandFilter;

            double envScale = (env == null) ? Double.NaN : env.getScaleMPerPx();
            this.scaleMPerPx = (Double.isFinite(envScale) && envScale > 1.0e-9) ? envScale : DEFAULT_SCALE_M_PER_PX;
            this.refFreqGhz = selectReferenceFrequencyGhz(env, displayBandFilter);
            this.dxMeters = chooseDxMeters(this.widthPx, this.heightPx, this.scaleMPerPx, this.cellPx, this.refFreqGhz);
            this.dtSeconds = 0.99 * this.dxMeters / (C0 * Math.sqrt(2.0));
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
            this.pml = grid.pmlCells;
            this.cellCount = nx * ny;
            this.hxCount = nx * nym1;
            this.hyCount = Math.max(1, nx - 1) * ny;

            this.cEx1 = new float[cellCount];
            this.cEx2 = new float[cellCount];
            this.cEy1 = new float[cellCount];
            this.cEy2 = new float[cellCount];
            this.bHx = new float[hxCount];
            this.cHx = new float[hxCount];
            this.bHy = new float[hyCount];
            this.cHy = new float[hyCount];
            this.power = new float[cellCount];

            buildCoefficients();
            buildSources(env);

            long handle = MetalNativeBridge.create(
                    nx,
                    ny,
                    (float) (1.0 / dxMeters),
                    (float) POWER_EMA_ALPHA,
                    (float) (1.0 - POWER_EMA_ALPHA),
                    cEx1, cEx2, cEy1, cEy2,
                    bHx, cHx, bHy, cHy
            );
            if (handle == 0L) {
                throw new IllegalStateException("Metal native session 생성 실패");
            }
            this.cleanup = new NativeCleanup(handle);
            this.cleanable = CLEANER.register(this, cleanup);
            this.materialStats = computeMaterialStats();

            this.frame = new WritableImage(this.widthPx, this.heightPx);
            this.framePw = frame.getPixelWriter();

            System.out.printf(
                    "[SolverV2][Metal] refFreq=%.3fGHz, scale=%.6f m/px, dx=%.4fm, dt=%.4fns, grid=%dx%d (map=%dx%d, pml=%d), sources=%d%n",
                    refFreqGhz, scaleMPerPx, dxMeters, timeStepNs, nx, ny, grid.mapNx, grid.mapNy, pml, sources.size()
            );
        }

        @Override
        @SuppressWarnings("removal")
        protected void finalize() throws Throwable {
            try {
                closeNative();
            } finally {
                super.finalize();
            }
        }

        private void closeNative() {
            if (closed) return;
            closed = true;
            cleanable.clean();
        }

        @Override
        public int widthPx() {
            return widthPx;
        }

        @Override
        public int heightPx() {
            return heightPx;
        }

        @Override
        public int cellPx() {
            return cellPx;
        }

        @Override
        public long stepCount() {
            return stepCount;
        }

        @Override
        public double timeNs() {
            return timeNs;
        }

        @Override
        public void reset() {
            if (cleanup.handle() == 0L) return;
            MetalNativeBridge.reset(cleanup.handle());
            for (Source s : sources) {
                s.phase = 0.0f;
            }
            java.util.Arrays.fill(power, 0.0f);
            stepCount = 0L;
            timeNs = 0.0;
            visNorm = 1.0e-6;
            lastVisualDebugSummary = "debug: field reset";
        }

        @Override
        public void refreshSources(WifiEnvironment env) {
            buildSources(env);
            reset();
        }

        @Override
        public void step(int subSteps) {
            long handle = cleanup.handle();
            if (handle == 0L) return;
            int n = Math.max(1, subSteps);
            for (int i = 0; i < n; i++) {
                fillSourceInstantValues();
                MetalNativeBridge.step(handle, sourceX, sourceY, sourceNow, sourceNow.length);
                stepCount++;
                timeNs += timeStepNs;
            }
        }

        @Override
        public WritableImage renderFrame() {
            long handle = cleanup.handle();
            if (handle == 0L) return frame;
            MetalNativeBridge.readPower(handle, power);

            double localMax = 1.0e-12;
            double localMaxOutsideSource = 1.0e-12;
            double localSumOutsideSource = 0.0;
            int localCountOutsideSource = 0;
            for (int mx = 0; mx < grid.mapNx; mx++) {
                int gx = mx + pml;
                for (int my = 0; my < grid.mapNy; my++) {
                    int gy = my + pml;
                    int id = idx(gx, gy);
                    double v = power[id];
                    if (!Double.isFinite(v) || v < 0.0) {
                        v = 0.0;
                        power[id] = 0.0f;
                    }
                    localMax = Math.max(localMax, v);
                    if (!isSourceCoreCell(gx, gy)) {
                        localMaxOutsideSource = Math.max(localMaxOutsideSource, v);
                        localSumOutsideSource += v;
                        localCountOutsideSource++;
                    }
                }
            }

            if (!Double.isFinite(localMax) || localMax <= 0.0) {
                localMax = 1.0e-12;
            }
            if (!Double.isFinite(localMaxOutsideSource) || localMaxOutsideSource <= 0.0) {
                localMaxOutsideSource = localMax;
            }

            double localMeanOutsideSource = localSumOutsideSource / Math.max(1, localCountOutsideSource);
            if (!Double.isFinite(localMeanOutsideSource) || localMeanOutsideSource <= 0.0) {
                localMeanOutsideSource = localMaxOutsideSource * 0.05;
            }

            double targetNorm = localMaxOutsideSource;
            targetNorm = Math.max(1.0e-12, targetNorm);
            if (!Double.isFinite(visNorm) || visNorm <= 0.0) {
                visNorm = targetNorm;
            }
            visNorm = 0.92 * visNorm + 0.08 * targetNorm;
            visNorm = clampDouble(visNorm, targetNorm * 0.50, targetNorm * 2.20);

            double norm = Math.max(1.0e-12, visNorm);
            double floorFromDb = norm * Math.pow(10.0, DISPLAY_FLOOR_DB / 10.0);
            double floor = Math.max(Math.max(floorFromDb, norm * DISPLAY_FLOOR_RATIO), 1.0e-20);
            double alphaCutoffPower = norm * Math.pow(10.0, DISPLAY_ALPHA_CUTOFF_DB / 10.0);

            double pxPerCellX = widthPx / (double) grid.mapNx;
            double pxPerCellY = heightPx / (double) grid.mapNy;
            int visibleCells = 0;
            int totalCells = Math.max(1, grid.mapNx * grid.mapNy);

            for (int mx = 0; mx < grid.mapNx; mx++) {
                int gx = mx + pml;
                int px0 = clamp((int) Math.floor(mx * pxPerCellX), 0, widthPx - 1);
                int px1 = clamp((int) Math.ceil((mx + 1) * pxPerCellX), px0 + 1, widthPx);
                for (int my = 0; my < grid.mapNy; my++) {
                    int gy = my + pml;
                    int py0 = clamp((int) Math.floor(my * pxPerCellY), 0, heightPx - 1);
                    int py1 = clamp((int) Math.ceil((my + 1) * pxPerCellY), py0 + 1, heightPx);

                    double rawP = power[idx(gx, gy)];
                    if (!Double.isFinite(rawP) || rawP < 0.0) rawP = 0.0;
                    double p = Math.max(floor, rawP);
                    double ratio = p / norm;
                    if (!Double.isFinite(ratio) || ratio <= 0.0) ratio = floor / norm;
                    double relDb = 10.0 * Math.log10(ratio);
                    if (!Double.isFinite(relDb)) relDb = DISPLAY_MIN_DB;
                    relDb = clampDouble(relDb, DISPLAY_MIN_DB, DISPLAY_MAX_DB);
                    double t = (relDb - DISPLAY_MIN_DB) / (DISPLAY_MAX_DB - DISPLAY_MIN_DB);
                    if (!Double.isFinite(t)) t = 0.0;
                    t = Math.pow(clampDouble(t, 0.0, 1.0), 0.82);
                    int argb = toArgb(powerColorFromDb(t, relDb));
                    if (rawP >= alphaCutoffPower) {
                        visibleCells++;
                    }

                    for (int py = py0; py < py1; py++) {
                        for (int px = px0; px < px1; px++) {
                            framePw.setArgb(px, py, argb);
                        }
                    }
                }
            }
            double coverage = 100.0 * visibleCells / totalCells;
            lastVisualDebugSummary = String.format(
                    "debug max=%.3e outMax=%.3e mean=%.3e norm=%.3e floor=%.3e vis=%.1f%%",
                    localMax,
                    localMaxOutsideSource,
                    localMeanOutsideSource,
                    norm,
                    floor,
                    coverage
            );
            return frame;
        }

        @Override
        public double dxMeters() {
            return dxMeters;
        }

        @Override
        public double dtSeconds() {
            return dtSeconds;
        }

        @Override
        public int pmlCells() {
            return pml;
        }

        @Override
        public Band displayBandFilter() {
            return displayBandFilter;
        }

        @Override
        public int sourceCount() {
            return sources.size();
        }

        @Override
        public double courantNumber() {
            return C0 * dtSeconds * Math.sqrt(2.0) / dxMeters;
        }

        @Override
        public FdtdWaveSimulator.MaterialStats materialStats() {
            return materialStats;
        }

        @Override
        public String diagnosticsSummary() {
            return String.format(
                    "[SolverV2][Metal] dx=%.4fm dt=%.4fns CFL=%.4f pml=%d src=%d materials(air=%d wall=%d door=%d window=%d)",
                    dxMeters,
                    timeStepNs,
                    courantNumber(),
                    pml,
                    sourceCount(),
                    materialStats.airCells(),
                    materialStats.wallCells(),
                    materialStats.doorCells(),
                    materialStats.windowCells()
            );
        }

        @Override
        public String visualDebugSummary() {
            return lastVisualDebugSummary;
        }

        private void buildCoefficients() {
            float[] sigmaEx = new float[nx];
            float[] sigmaEy = new float[ny];
            float[] sigmaMx = new float[nx];
            float[] sigmaMy = new float[ny];
            buildPmlProfiles(sigmaEx, sigmaEy, sigmaMx, sigmaMy);

            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < ny; j++) {
                    int id = idx(i, j);
                    double eps = EPS0 * Math.max(1.0e-6, grid.epsR[i][j]);
                    double sigmaMat = Math.max(0.0, grid.sigma[i][j]) + 5.0e-7;  // 2.0e-6→5.0e-7: Soft source 신호 소멸 방지

                    double sx = (sigmaMat + sigmaEx[i]) * dtSeconds / (2.0 * eps);
                    cEx1[id] = (float) ((1.0 - sx) / (1.0 + sx));
                    cEx2[id] = (float) ((dtSeconds / eps) / (1.0 + sx));

                    double sy = (sigmaMat + sigmaEy[j]) * dtSeconds / (2.0 * eps);
                    cEy1[id] = (float) ((1.0 - sy) / (1.0 + sy));
                    cEy2[id] = (float) ((dtSeconds / eps) / (1.0 + sy));
                }
            }

            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < ny - 1; j++) {
                    int hid = hxIdx(i, j);
                    double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i][j + 1]);
                    double mu = MU0 * Math.max(1.0e-6, muRavg);
                    double s = sigmaMy[j] * dtSeconds / (2.0 * mu);
                    bHx[hid] = (float) ((1.0 - s) / (1.0 + s));
                    cHx[hid] = (float) ((dtSeconds / (mu * dxMeters)) / (1.0 + s));
                }
            }

            for (int i = 0; i < nx - 1; i++) {
                for (int j = 0; j < ny; j++) {
                    int hid = hyIdx(i, j);
                    double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i + 1][j]);
                    double mu = MU0 * Math.max(1.0e-6, muRavg);
                    double s = sigmaMx[i] * dtSeconds / (2.0 * mu);
                    bHy[hid] = (float) ((1.0 - s) / (1.0 + s));
                    cHy[hid] = (float) ((dtSeconds / (mu * dxMeters)) / (1.0 + s));
                }
            }
        }

        private void buildPmlProfiles(float[] sigmaEx, float[] sigmaEy, float[] sigmaMx, float[] sigmaMy) {
            double m = 3.5;
            double r0 = 1.0e-8;
            double sigmaEmax = -((m + 1.0) * Math.log(r0)) * (EPS0 * C0) / (2.0 * Math.max(1, pml) * dxMeters);
            double sigmaMmax = sigmaEmax * (MU0 / EPS0);

            for (int i = 0; i < nx; i++) {
                int dist = Math.min(i, nx - 1 - i);
                double ratio = (dist < pml) ? ((pml - dist) / (double) pml) : 0.0;
                double g = Math.pow(ratio, m);
                sigmaEx[i] = (float) (sigmaEmax * g);
                sigmaMx[i] = (float) (sigmaMmax * g);
            }

            for (int j = 0; j < ny; j++) {
                int dist = Math.min(j, ny - 1 - j);
                double ratio = (dist < pml) ? ((pml - dist) / (double) pml) : 0.0;
                double g = Math.pow(ratio, m);
                sigmaEy[j] = (float) (sigmaEmax * g);
                sigmaMy[j] = (float) (sigmaMmax * g);
            }
        }

        private void buildSources(WifiEnvironment env) {
            sources.clear();
            if (env == null) {
                sourceX = new int[0];
                sourceY = new int[0];
                sourceNow = new float[0];
                return;
            }

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
                    float phase = (float) initialPhase(ap, band);
                    float amp = (float) sourceAmplitudeFromEirpDbm(rc.txPowerDbm + rc.antennaGain);
                    sources.add(new Source(gx, gy, band, amp, (float) omegaDt, rampSteps, phase));
                }
            }

            sourceX = new int[sources.size()];
            sourceY = new int[sources.size()];
            sourceNow = new float[sources.size()];
            for (int i = 0; i < sources.size(); i++) {
                Source s = sources.get(i);
                sourceX[i] = s.gx;
                sourceY[i] = s.gy;
            }

            if (clampedFreqCount > 0) {
                System.out.printf(
                        "[SolverV2][Metal] %d source(s) frequency clamped by grid Nyquist (dt=%.4fns).%n",
                        clampedFreqCount, timeStepNs
                );
            }
        }

        private void fillSourceInstantValues() {
            for (int i = 0; i < sources.size(); i++) {
                Source s = sources.get(i);
                float ramp = 1.0f;
                if (stepCount < s.rampSteps) {
                    float u = (float) clampDouble(stepCount / (double) s.rampSteps, 0.0, 1.0);
                    float sin = (float) Math.sin(0.5 * Math.PI * u);
                    ramp = sin * sin;
                }
                sourceNow[i] = s.amplitude * (float) Math.sin(s.phase) * ramp;
                s.phase += s.omegaDt;
                if (s.phase > (float) (Math.PI * 2.0)) {
                    s.phase -= (float) (Math.PI * 2.0);
                }
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

        private FdtdWaveSimulator.MaterialStats computeMaterialStats() {
            int air = 0;
            int wall = 0;
            int door = 0;
            int window = 0;
            for (int mx = 0; mx < grid.mapNx; mx++) {
                int gx = mx + pml;
                for (int my = 0; my < grid.mapNy; my++) {
                    int gy = my + pml;
                    int code = grid.materialCode[gx][gy];
                    switch (code) {
                        case 1 -> wall++;
                        case 2 -> door++;
                        case 3 -> window++;
                        default -> air++;
                    }
                }
            }
            return new FdtdWaveSimulator.MaterialStats(air, wall, door, window);
        }

        private int idx(int i, int j) {
            return i * ny + j;
        }

        private int hxIdx(int i, int j) {
            return i * nym1 + j;
        }

        private int hyIdx(int i, int j) {
            return i * ny + j;
        }
    }

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
        double targetDx = Math.max(0.004, lambda / 8.0);
        double dx = Math.min(baseDx, targetDx);

        double mapWidthM = Math.max(1.0e-6, widthPx * scaleMPerPx);
        double mapHeightM = Math.max(1.0e-6, heightPx * scaleMPerPx);
        double cells = (mapWidthM / dx) * (mapHeightM / dx);
        if (cells > 450_000.0) {
            double grow = Math.sqrt(cells / 450_000.0);
            dx *= grow;
        }
        return clampDouble(dx, 0.004, 0.25);
    }

    private static int choosePmlCells(int widthPx, int heightPx, int cellPx) {
        int gw = Math.max(4, (int) Math.ceil(widthPx / (double) Math.max(2, cellPx)));
        int gh = Math.max(4, (int) Math.ceil(heightPx / (double) Math.max(2, cellPx)));
        int bySize = Math.max(8, Math.min(20, Math.min(gw, gh) / 10));
        return clamp(bySize, 8, 20);
    }

    private static double sourceAmplitudeFromEirpDbm(double eirpDbm) {
        // Soft Source(+=): Maxwell curl H에 더하는 방식이므로 Hard source 대비 누적량이 작음
        // → CPU(FdtdWaveSimulator)와 동일하게 10× 스케일링 (렌더링 정규화로 절대값 무관)
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
