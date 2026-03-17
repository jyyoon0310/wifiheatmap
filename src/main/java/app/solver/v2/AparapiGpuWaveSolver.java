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
import com.aparapi.Kernel;
import com.aparapi.Range;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Built-in GPU backend for Solver v2.
 *
 * This implementation keeps the same physical model as the CPU runtime
 * (TEz FDTD + split-field PML), but moves the hot loops(H/E/power update)
 * to Aparapi kernels.
 *
 * If OpenCL GPU execution is unavailable, ServiceLoader selection naturally
 * falls back to CPU backend in {@link SolverV2Engine}.
 */
public final class AparapiGpuWaveSolver implements GpuWaveSolver {
    private static volatile boolean printedUnsupportedOnce = false;

    @Override
    public boolean isAvailable() {
        if (!isPlatformSupported()) {
            if (!printedUnsupportedOnce) {
                printedUnsupportedOnce = true;
                System.out.println("[SolverV2][Aparapi] 현재 런타임(OpenCL/아키텍처)에서는 GPU backend를 사용할 수 없어 CPU fallback 합니다.");
            }
            return false;
        }
        try {
            ProbeKernel probe = new ProbeKernel();
            probe.setExecutionMode(Kernel.EXECUTION_MODE.GPU);
            probe.execute(Range.create(1));
            Kernel.EXECUTION_MODE mode = probe.getExecutionMode();
            probe.dispose();
            return isGpuLike(mode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPlatformSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        // macOS arm64에서 Aparapi(OpenCL) JNI 로딩 실패가 반복적으로 발생한다.
        if (os.contains("mac") && (arch.contains("arm64") || arch.contains("aarch64"))) {
            return false;
        }

        if (os.contains("mac")) {
            File openClFramework = new File("/System/Library/Frameworks/OpenCL.framework/OpenCL");
            return openClFramework.exists();
        }
        return true;
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
            System.err.println("[SolverV2] Aparapi GPU session init 실패: " + ex.getMessage());
            return null;
        }
    }

    private static boolean isGpuLike(Kernel.EXECUTION_MODE mode) {
        if (mode == null) return false;
        String n = mode.name();
        return "GPU".equalsIgnoreCase(n) || "ACC".equalsIgnoreCase(n);
    }

    private static final class ProbeKernel extends Kernel {
        @Override
        public void run() {
            // no-op
        }
    }

    private static final class SessionImpl implements GpuWaveSolver.Session {
        private static final double EPS0 = 8.854_187_812_8e-12;
        private static final double MU0 = 1.256_637_062_12e-6;
        private static final double C0 = 299_792_458.0;
        private static final double DEFAULT_SCALE_M_PER_PX = 0.01;

        // Soft Source 전환 후 CPU(FdtdWaveSimulator)와 동일하게 맞춤
        private static final double POWER_EMA_ALPHA = 0.85;  // 0.94→0.85: Soft source 초기 신호 포착
        private static final double DISPLAY_MIN_DB = -78.0;
        private static final double DISPLAY_MAX_DB = 0.0;
        // 너무 낮으면 정상 동작 중에도 오버레이가 "사라진 것처럼" 보일 수 있어
        // 시각 바닥값을 약간 올려 배경 파동 에너지를 유지한다.
        private static final double DISPLAY_FLOOR_RATIO = 1.0e-8;
        private static final double DISPLAY_FLOOR_DB = -72.0;
        private static final double AIR_SIGMA = 5.0e-7;  // 2.0e-6→5.0e-7: Soft source 신호 소멸 방지
        private static final int SOURCE_RAMP_CYCLES = 5;  // 20→5: Soft source ramp 초기 소멸 방지
        // 격자 해상도가 충분치 않을 때 Nyquist 근처 주파수는 수치 분산/불안정성을 키운다.
        // 실시간 오버레이 안정성을 위해 소스 주파수를 dt 기준으로 보수적으로 제한한다.
        // f_eff <= SOURCE_FREQ_LIMIT_FACTOR / dt
        // dt = 0.99*dx/(c*sqrt(2)) 기준으로 SOURCE_FREQ_LIMIT_FACTOR≈0.085이면
        // 대략 8 cells / wavelength를 확보해 수치 이방성(별모양) 완화에 유리하다.
        private static final double SOURCE_FREQ_LIMIT_FACTOR = 0.085;
        // 점 소스(1셀 주입)는 축 방향 별모양(수치 이방성)이 쉽게 생긴다.
        // 3x3 가우시안 유사 분포로 주입해 파면을 더 둥글게 만든다.
        private static final int[] SRC_OX = {0, 1, -1, 0, 0, 1, 1, -1, -1};
        private static final int[] SRC_OY = {0, 0, 0, 1, -1, 1, -1, 1, -1};
        private static final float[] SRC_W = {0.36f, 0.12f, 0.12f, 0.12f, 0.12f, 0.04f, 0.04f, 0.04f, 0.04f};
        private static final int NORM_SOURCE_EXCLUDE_R = 2;
        private static final int MIN_PML_CELLS = 8;
        private static final int MAX_PML_CELLS = 20;
        private static final double MIN_DX_M = 0.004;
        private static final double MAX_DX_M = 0.25;
        private static final double MAX_REALTIME_CELLS = 450_000.0;

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
        private final int nxm1;
        private final int pml;
        private final int cellCount;
        private final int hxCount;
        private final int hyCount;

        // Field arrays are flattened for Aparapi kernels.
        // idx(i,j) = i*ny + j
        private final float[] ez;
        private final float[] ezx;
        private final float[] ezy;
        // hxIdx(i,j) = i*(ny-1) + j, shape [nx][ny-1]
        private final float[] hx;
        // hyIdx(i,j) = i*ny + j, shape [nx-1][ny]
        private final float[] hy;

        // Coefficients (flattened)
        private final float[] cEx1;
        private final float[] cEx2;
        private final float[] cEy1;
        private final float[] cEy2;
        private final float[] bHx;
        private final float[] cHx;
        private final float[] bHy;
        private final float[] cHy;

        private final float[] powerEma;
        private final List<Source> sources = new ArrayList<>();

        private final UpdateHKernel updateHKernel;
        private final UpdateEKernel updateEKernel;
        private final PowerKernel powerKernel;
        private final Range rangeH;
        private final Range rangeE;
        private final String executionModeName;

        private final WritableImage frame;
        private final PixelWriter framePw;
        private final FdtdWaveSimulator.MaterialStats materialStats;

        private long stepCount = 0L;
        private double timeNs = 0.0;
        private double visNorm = 1.0e-6;

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
            this.nxm1 = Math.max(1, nx - 1);
            this.pml = grid.pmlCells;
            this.cellCount = nx * ny;
            this.hxCount = nx * nym1;
            this.hyCount = nxm1 * ny;

            this.ez = new float[cellCount];
            this.ezx = new float[cellCount];
            this.ezy = new float[cellCount];
            this.hx = new float[hxCount];
            this.hy = new float[hyCount];
            this.cEx1 = new float[cellCount];
            this.cEx2 = new float[cellCount];
            this.cEy1 = new float[cellCount];
            this.cEy2 = new float[cellCount];
            this.bHx = new float[hxCount];
            this.cHx = new float[hxCount];
            this.bHy = new float[hyCount];
            this.cHy = new float[hyCount];
            this.powerEma = new float[cellCount];

            buildCoefficients();
            buildSources(env);
            this.materialStats = computeMaterialStats();

            this.frame = new WritableImage(this.widthPx, this.heightPx);
            this.framePw = frame.getPixelWriter();

            this.updateHKernel = new UpdateHKernel(nx, ny, ez, hx, hy, bHx, cHx, bHy, cHy, (float) (1.0 / dxMeters));
            this.updateEKernel = new UpdateEKernel(nx, ny, ez, ezx, ezy, hx, hy, cEx1, cEx2, cEy1, cEy2, (float) (1.0 / dxMeters));
            this.powerKernel = new PowerKernel(nx, ny, ez, powerEma, (float) POWER_EMA_ALPHA, (float) (1.0 - POWER_EMA_ALPHA));
            this.rangeH = Range.create(Math.max(hxCount, hyCount));
            this.rangeE = Range.create(cellCount);
            this.executionModeName = initGpuKernels();

            System.out.printf(
                    "[SolverV2][Aparapi] mode=%s refFreq=%.3fGHz, scale=%.6f m/px, dx=%.4fm, dt=%.4fns, grid=%dx%d (map=%dx%d, pml=%d), sources=%d%n",
                    executionModeName, refFreqGhz, scaleMPerPx, dxMeters, timeStepNs, nx, ny, grid.mapNx, grid.mapNy, pml, sources.size()
            );
        }

        private String initGpuKernels() {
            updateHKernel.setExecutionMode(Kernel.EXECUTION_MODE.GPU);
            updateEKernel.setExecutionMode(Kernel.EXECUTION_MODE.GPU);
            powerKernel.setExecutionMode(Kernel.EXECUTION_MODE.GPU);

            // Warm-up execution to force mode selection.
            updateHKernel.execute(rangeH);
            updateEKernel.execute(rangeE);
            powerKernel.execute(rangeE);

            Kernel.EXECUTION_MODE mode = updateHKernel.getExecutionMode();
            if (!isGpuLike(mode)) {
                throw new IllegalStateException("OpenCL GPU 모드를 사용할 수 없습니다. mode=" + mode);
            }
            clearFieldState();
            return mode.name();
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
            clearFieldState();
            for (Source s : sources) {
                s.phase = 0.0f;
            }
        }

        @Override
        public void refreshSources(WifiEnvironment env) {
            buildSources(env);
            clearFieldState();
        }

        @Override
        public void step(int subSteps) {
            int n = Math.max(1, subSteps);
            for (int i = 0; i < n; i++) {
                stepOnce();
            }
        }

        @Override
        public WritableImage renderFrame() {
            double localMax = 1.0e-12;
            double localMaxOutsideSource = 1.0e-12;
            double localSumOutsideSource = 0.0;
            int localCountOutsideSource = 0;
            for (int mx = 0; mx < grid.mapNx; mx++) {
                int gx = mx + pml;
                for (int my = 0; my < grid.mapNy; my++) {
                    int gy = my + pml;
                    int id = idx(gx, gy);
                    double v = powerEma[id];
                    if (!Double.isFinite(v) || v < 0.0) {
                        v = 0.0;
                        powerEma[id] = 0.0f;
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
            double targetNorm = Math.max(localMeanOutsideSource * 28.0, localMaxOutsideSource * 0.22);
            targetNorm = Math.max(1.0e-12, targetNorm);
            if (!Double.isFinite(visNorm) || visNorm <= 0.0) {
                visNorm = targetNorm;
            }
            visNorm = 0.90 * visNorm + 0.10 * targetNorm;
            visNorm = clampDouble(visNorm, targetNorm * 0.65, targetNorm * 1.75);
            double norm = Math.max(1.0e-12, visNorm);
            double floorFromDb = norm * Math.pow(10.0, DISPLAY_FLOOR_DB / 10.0);
            double floor = Math.max(Math.max(floorFromDb, norm * DISPLAY_FLOOR_RATIO), 1.0e-14);

            double pxPerCellX = widthPx / (double) grid.mapNx;
            double pxPerCellY = heightPx / (double) grid.mapNy;

            for (int mx = 0; mx < grid.mapNx; mx++) {
                int gx = mx + pml;
                int px0 = clamp((int) Math.floor(mx * pxPerCellX), 0, widthPx - 1);
                int px1 = clamp((int) Math.ceil((mx + 1) * pxPerCellX), px0 + 1, widthPx);
                for (int my = 0; my < grid.mapNy; my++) {
                    int gy = my + pml;
                    int py0 = clamp((int) Math.floor(my * pxPerCellY), 0, heightPx - 1);
                    int py1 = clamp((int) Math.ceil((my + 1) * pxPerCellY), py0 + 1, heightPx);

                    double rawP = powerEma[idx(gx, gy)];
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

                    for (int py = py0; py < py1; py++) {
                        for (int px = px0; px < px1; px++) {
                            framePw.setArgb(px, py, argb);
                        }
                    }
                }
            }
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
                    "[SolverV2][Aparapi] mode=%s dx=%.4fm dt=%.4fns CFL=%.4f pml=%d src=%d materials(air=%d wall=%d door=%d window=%d)",
                    executionModeName,
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

        private void clearFieldState() {
            fill(ez, 0.0f);
            fill(ezx, 0.0f);
            fill(ezy, 0.0f);
            fill(hx, 0.0f);
            fill(hy, 0.0f);
            fill(powerEma, 0.0f);
            stepCount = 0L;
            timeNs = 0.0;
            visNorm = 1.0e-6;
        }

        private void stepOnce() {
            updateHKernel.execute(rangeH);
            updateEKernel.execute(rangeE);
            injectSources();
            powerKernel.execute(rangeE);
            stepCount++;
            timeNs += timeStepNs;
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
                    double sigmaMat = Math.max(0.0, grid.sigma[i][j]) + AIR_SIGMA;

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
                    double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i][j + 1]);
                    double mu = MU0 * Math.max(1.0e-6, muRavg);
                    double s = sigmaMy[j] * dtSeconds / (2.0 * mu);
                    int hid = hxIdx(i, j);
                    bHx[hid] = (float) ((1.0 - s) / (1.0 + s));
                    cHx[hid] = (float) ((dtSeconds / (mu * dxMeters)) / (1.0 + s));
                }
            }

            for (int i = 0; i < nx - 1; i++) {
                for (int j = 0; j < ny; j++) {
                    double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i + 1][j]);
                    double mu = MU0 * Math.max(1.0e-6, muRavg);
                    double s = sigmaMx[i] * dtSeconds / (2.0 * mu);
                    int hid = hyIdx(i, j);
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
                    float phase = (float) initialPhase(ap, band);
                    float amp = (float) sourceAmplitudeFromEirpDbm(rc.txPowerDbm + rc.antennaGain);
                    sources.add(new Source(gx, gy, band, amp, (float) omegaDt, rampSteps, phase));
                }
            }

            if (clampedFreqCount > 0) {
                System.out.printf(
                        "[SolverV2][Aparapi] %d source(s) frequency clamped by grid Nyquist (dt=%.4fns).%n",
                        clampedFreqCount, timeStepNs
                );
            }
        }

        private void injectSources() {
            if (sources.isEmpty()) return;

            for (Source s : sources) {
                if (s.gx <= 0 || s.gx >= nx - 1 || s.gy <= 0 || s.gy >= ny - 1) continue;

                float ramp = 1.0f;
                if (stepCount < s.rampSteps) {
                    float u = (float) clampDouble(stepCount / (double) s.rampSteps, 0.0, 1.0);
                    float sin = (float) Math.sin(0.5 * Math.PI * u);
                    ramp = sin * sin;
                }

                // Soft source + 3x3 분포 주입:
                // Hard source(=)는 Maxwell 방정식을 덮어써 에너지 폭발 유발.
                // Soft source(+=)는 curl H 결과에 더해 수치 안정성 유지.
                float src = s.amplitude * (float) Math.sin(s.phase) * ramp;
                for (int k = 0; k < SRC_OX.length; k++) {
                    int gx = s.gx + SRC_OX[k];
                    int gy = s.gy + SRC_OY[k];
                    if (gx <= 0 || gx >= nx - 1 || gy <= 0 || gy >= ny - 1) continue;
                    int id = idx(gx, gy);
                    float v = src * SRC_W[k];
                    ezx[id] += 0.5f * v;
                    ezy[id] += 0.5f * v;
                    ez[id] = ezx[id] + ezy[id];
                }

                s.phase += s.omegaDt;
                if (s.phase > (float) (Math.PI * 2.0)) {
                    s.phase -= (float) (Math.PI * 2.0);
                }
            }
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

        private boolean isSourceCoreCell(int gx, int gy) {
            int r2 = NORM_SOURCE_EXCLUDE_R * NORM_SOURCE_EXCLUDE_R;
            for (Source s : sources) {
                int dx = gx - s.gx;
                int dy = gy - s.gy;
                if (dx * dx + dy * dy <= r2) return true;
            }
            return false;
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

        private static void fill(float[] arr, float value) {
            java.util.Arrays.fill(arr, value);
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
            // Soft Source(+=): Hard source 대비 Ez 누적량이 작으므로 CPU와 동일하게 10× 스케일링
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
            if (u <= 0.0) return Color.rgb(10, 8, 28, 0.0);

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

            double alphaBoost = 0.10 * clampDouble((relDb + 20.0) / 20.0, 0.0, 1.0);
            double alpha = clampDouble(0.03 + c.getOpacity() * 0.62 + alphaBoost, 0.02, 0.90);
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

    /**
     * Updates Hx/Hy from Ez.
     * - globalId < hxCount: update Hx(i,j), shape [nx][ny-1]
     * - globalId < hyCount: update Hy(i,j), shape [nx-1][ny]
     */
    private static final class UpdateHKernel extends Kernel {
        private final int nx;
        private final int ny;
        private final int nym1;
        private final int hxCount;
        private final int hyCount;
        private final float invDx;
        private final float[] ez;
        private final float[] hx;
        private final float[] hy;
        private final float[] bHx;
        private final float[] cHx;
        private final float[] bHy;
        private final float[] cHy;

        UpdateHKernel(int nx,
                      int ny,
                      float[] ez,
                      float[] hx,
                      float[] hy,
                      float[] bHx,
                      float[] cHx,
                      float[] bHy,
                      float[] cHy,
                      float invDx) {
            this.nx = nx;
            this.ny = ny;
            this.nym1 = max(1, ny - 1);
            this.hxCount = nx * this.nym1;
            this.hyCount = max(1, nx - 1) * ny;
            this.invDx = invDx;
            this.ez = ez;
            this.hx = hx;
            this.hy = hy;
            this.bHx = bHx;
            this.cHx = cHx;
            this.bHy = bHy;
            this.cHy = cHy;
        }

        @Override
        public void run() {
            int gid = getGlobalId();

            if (gid < hxCount) {
                int i = gid / nym1;
                int j = gid - i * nym1;
                int id = i * ny + j;
                float dEdy = (ez[id + 1] - ez[id]) * invDx;
                hx[gid] = bHx[gid] * hx[gid] - cHx[gid] * dEdy;
            }

            if (gid < hyCount) {
                int i = gid / ny;
                int j = gid - i * ny;
                int id = i * ny + j;
                float dEdx = (ez[id + ny] - ez[id]) * invDx;
                hy[gid] = bHy[gid] * hy[gid] + cHy[gid] * dEdx;
            }
        }
    }

    /**
     * Updates Ez split components from Hx/Hy curls, then merges Ez = Ezx + Ezy.
     */
    private static final class UpdateEKernel extends Kernel {
        private final int nx;
        private final int ny;
        private final int nym1;
        private final float invDx;
        private final float[] ez;
        private final float[] ezx;
        private final float[] ezy;
        private final float[] hx;
        private final float[] hy;
        private final float[] cEx1;
        private final float[] cEx2;
        private final float[] cEy1;
        private final float[] cEy2;

        UpdateEKernel(int nx,
                      int ny,
                      float[] ez,
                      float[] ezx,
                      float[] ezy,
                      float[] hx,
                      float[] hy,
                      float[] cEx1,
                      float[] cEx2,
                      float[] cEy1,
                      float[] cEy2,
                      float invDx) {
            this.nx = nx;
            this.ny = ny;
            this.nym1 = max(1, ny - 1);
            this.ez = ez;
            this.ezx = ezx;
            this.ezy = ezy;
            this.hx = hx;
            this.hy = hy;
            this.cEx1 = cEx1;
            this.cEx2 = cEx2;
            this.cEy1 = cEy1;
            this.cEy2 = cEy2;
            this.invDx = invDx;
        }

        @Override
        public void run() {
            int gid = getGlobalId();
            int i = gid / ny;
            int j = gid - i * ny;

            if (i <= 0 || i >= nx - 1 || j <= 0 || j >= ny - 1) return;

            int id = gid;
            int hyId = i * ny + j;
            int hyPrev = (i - 1) * ny + j;
            int hxRow = i * nym1;
            int hxId = hxRow + j;
            int hxPrev = hxRow + (j - 1);

            float curlHy = (hy[hyId] - hy[hyPrev]) * invDx;
            float curlHx = (hx[hxId] - hx[hxPrev]) * invDx;

            float ex = cEx1[id] * ezx[id] + cEx2[id] * curlHy;
            float ey = cEy1[id] * ezy[id] - cEy2[id] * curlHx;
            ezx[id] = ex;
            ezy[id] = ey;
            ez[id] = ex + ey;
        }
    }

    /**
     * Exponential moving average of power: P = alpha*P + (1-alpha)*(Ez^2)
     */
    private static final class PowerKernel extends Kernel {
        private final int nx;
        private final int ny;
        private final float alpha;
        private final float beta;
        private final float[] ez;
        private final float[] power;

        PowerKernel(int nx, int ny, float[] ez, float[] power, float alpha, float beta) {
            this.nx = nx;
            this.ny = ny;
            this.ez = ez;
            this.power = power;
            this.alpha = alpha;
            this.beta = beta;
        }

        @Override
        public void run() {
            int gid = getGlobalId();
            int i = gid / ny;
            int j = gid - i * ny;
            if (i <= 0 || i >= nx - 1 || j <= 0 || j >= ny - 1) return;

            float v = ez[gid];
            float p = v * v;
            power[gid] = alpha * power[gid] + beta * p;
        }
    }
}
