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
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Real-time TEz FDTD solver overlay.
 *
 * Notes:
 * - Uses Yee staggered fields (Ez, Hx, Hy) with split-field PML.
 * - This class keeps the same public API as the previous solver so controller wiring stays unchanged.
 * - Grid resolution is constrained for interactive rendering; if requested RF exceeds numerical Nyquist,
 *   the source frequency is clamped to a stable effective value.
 */
public class FdtdWaveSimulator {
    private static final double EPS0 = 8.854_187_812_8e-12;
    private static final double MU0 = 1.256_637_062_12e-6;
    private static final double C0 = 299_792_458.0;
    private static final double DEFAULT_SCALE_M_PER_PX = 0.01;

    // Soft Source(+=) 전환 후 응답성 향상: Hard Source 시절 0.94 → 0.85
    // Hard source는 매 스텝 Ez를 절대값으로 덮어써서 EMA가 느려도 상관없었으나,
    // Soft source는 주입량이 누적식이므로 EMA가 빠르게 따라가야 초기 신호가 보임
    private static final double POWER_EMA_ALPHA = 0.85;
    private static final double DISPLAY_MIN_DB = -92.0;
    private static final double DISPLAY_MAX_DB = 0.0;
    // 표시 바닥은 충분히 낮게 두고(alpha 컷오프로 노이즈 투명화) 전체 화면이 녹색으로 채워지는 현상을 줄인다.
    private static final double DISPLAY_FLOOR_RATIO = 1.0e-11;
    private static final double DISPLAY_FLOOR_DB = -104.0;
    private static final double DISPLAY_ALPHA_CUTOFF_DB = -82.0;
    // Soft Source 전환 후 공기 감쇠 감소: Hard source는 매 스텝 Ez를 절대값으로 복구하므로
    // 높은 sigma도 이겨냈으나, Soft source는 주입 후 감쇠에 의해 신호가 소멸되기 쉬움.
    // 2.0e-6 → 5.0e-7 S/m 으로 줄여 파장이 멀리 퍼지도록 허용
    private static final double AIR_SIGMA = 5.0e-7; // [S/m], air damping
    // Soft Source 전환 후: Hard source는 ramp가 느려도 절대값으로 Ez를 덮어쓰므로
    // 초기에도 신호가 보였으나, Soft source는 ramp 초기에 주입량이 ≈0 이어서
    // 공기 감쇠에 지져서 신호가 사라짐 → ramp 사이클을 20→5로 단축
    private static final int SOURCE_RAMP_CYCLES = 5;
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
    private static final double[] SRC_W = {0.36, 0.12, 0.12, 0.12, 0.12, 0.04, 0.04, 0.04, 0.04};
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
    private final int pml;

    // Yee fields
    private final double[][] ez;
    private final double[][] ezx;
    private final double[][] ezy;
    private final double[][] hx; // [nx][ny-1]
    private final double[][] hy; // [nx-1][ny]

    // PML profiles
    private final double[] sigmaEx;
    private final double[] sigmaEy;
    private final double[] sigmaMx;
    private final double[] sigmaMy;

    // Update coefficients
    private final double[][] cEx1;
    private final double[][] cEx2;
    private final double[][] cEy1;
    private final double[][] cEy2;
    private final double[][] bHx;
    private final double[][] cHx;
    private final double[][] bHy;
    private final double[][] cHy;

    private final double[][] powerEma;
    private final List<Source> sources = new ArrayList<>();

    private final WritableImage frame;
    private final PixelWriter framePw;

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
        this.pml = grid.pmlCells;

        this.ez = new double[nx][ny];
        this.ezx = new double[nx][ny];
        this.ezy = new double[nx][ny];
        this.hx = new double[nx][Math.max(1, ny - 1)];
        this.hy = new double[Math.max(1, nx - 1)][ny];

        this.sigmaEx = new double[nx];
        this.sigmaEy = new double[ny];
        this.sigmaMx = new double[nx];
        this.sigmaMy = new double[ny];

        this.cEx1 = new double[nx][ny];
        this.cEx2 = new double[nx][ny];
        this.cEy1 = new double[nx][ny];
        this.cEy2 = new double[nx][ny];
        this.bHx = new double[nx][Math.max(1, ny - 1)];
        this.cHx = new double[nx][Math.max(1, ny - 1)];
        this.bHy = new double[Math.max(1, nx - 1)][ny];
        this.cHy = new double[Math.max(1, nx - 1)][ny];
        this.powerEma = new double[nx][ny];

        this.frame = new WritableImage(this.widthPx, this.heightPx);
        this.framePw = frame.getPixelWriter();

        buildPmlProfiles();
        buildCoefficients();
        buildSources(env);

        System.out.printf(
                "[SolverV2] refFreq=%.3fGHz, scale=%.6f m/px, dx=%.4fm, dt=%.4fns, grid=%dx%d (map=%dx%d, pml=%d), sources=%d%n",
                refFreqGhz, scaleMPerPx, dxMeters, timeStepNs, nx, ny, grid.mapNx, grid.mapNy, pml, sources.size()
        );
    }

    public int widthPx() {
        return widthPx;
    }

    public int heightPx() {
        return heightPx;
    }

    public int cellPx() {
        return cellPx;
    }

    public long stepCount() {
        return stepCount;
    }

    public double timeNs() {
        return timeNs;
    }

    public void reset() {
        clearFieldState();
        for (Source s : sources) {
            s.phase = 0.0;
        }
    }

    /**
     * AP/라디오 변경 시 재질 격자는 유지하고 소스만 교체한다.
     * (부분 무효화: material rebuild 없이 source-path만 갱신)
     */
    public void refreshSources(WifiEnvironment env) {
        buildSources(env);
        clearFieldState();
    }

    public double dxMeters() {
        return dxMeters;
    }

    public double dtSeconds() {
        return dtSeconds;
    }

    public int pmlCells() {
        return pml;
    }

    public Band displayBandFilter() {
        return displayBandFilter;
    }

    public int sourceCount() {
        return sources.size();
    }

    /**
     * 2D Courant number:
     * CFL = c * dt * sqrt((1/dx)^2 + (1/dy)^2), dx=dy.
     * 안정 조건은 CFL <= 1.
     */
    public double courantNumber() {
        return C0 * dtSeconds * Math.sqrt(2.0) / dxMeters;
    }

    public record MaterialStats(int airCells, int wallCells, int doorCells, int windowCells) {}

    public MaterialStats materialStats() {
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
        return new MaterialStats(air, wall, door, window);
    }

    public String diagnosticsSummary() {
        MaterialStats ms = materialStats();
        return String.format(
                "[SolverV2] dx=%.4fm dt=%.4fns CFL=%.4f pml=%d src=%d materials(air=%d wall=%d door=%d window=%d)",
                dxMeters,
                timeStepNs,
                courantNumber(),
                pml,
                sourceCount(),
                ms.airCells(),
                ms.wallCells(),
                ms.doorCells(),
                ms.windowCells()
        );
    }

    /**
     * Solver 시각화 디버그 HUD/패널 표시용 한 줄 요약.
     */
    public String visualDebugSummary() {
        return lastVisualDebugSummary;
    }

    private void clearFieldState() {
        clear2D(ez);
        clear2D(ezx);
        clear2D(ezy);
        clear2D(hx);
        clear2D(hy);
        clear2D(powerEma);
        stepCount = 0L;
        timeNs = 0.0;
        visNorm = 1.0e-6;
        lastVisualDebugSummary = "debug: field reset";
    }

    public void step(int subSteps) {
        int n = Math.max(1, subSteps);
        for (int i = 0; i < n; i++) {
            stepOnce();
        }
    }

    public WritableImage renderFrame() {
        double localMax = 1.0e-12;
        double localMaxOutsideSource = 1.0e-12;
        double localSumOutsideSource = 0.0;
        int localCountOutsideSource = 0;
        for (int mx = 0; mx < grid.mapNx; mx++) {
            int gx = mx + pml;
            for (int my = 0; my < grid.mapNy; my++) {
                int gy = my + pml;
                double v = powerEma[gx][gy];
                if (!Double.isFinite(v) || v < 0.0) {
                    v = 0.0;
                    powerEma[gx][gy] = 0.0;
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

        // 기준 정규화 값:
        // - source core 제외 최대값(localMaxOutsideSource)을 기준으로 삼아
        //   전체 화면이 단색(녹색)으로 채워지는 현상을 줄인다.
        // - EMA로 프레임 간 튐을 완화한다.
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

                double rawP = powerEma[gx][gy];
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

    private void stepOnce() {
        updateH();
        updateE();
        injectSources();
        accumulatePower();
        stepCount++;
        timeNs += timeStepNs;
    }

    private void buildPmlProfiles() {
        double m = 3.5;
        // r0: 목표 반사 계수. 너무 작으면 sigma_max가 수백만 S/m → 계수 폭발.
        // pml 두께에 따라 적절히 완화: 8셀 → 1e-3, 12셀 → 1e-5, 그 이상 → 1e-6
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
                double eps = EPS0 * Math.max(1.0e-6, grid.epsR[i][j]);
                double sigmaMat = Math.max(0.0, grid.sigma[i][j]) + AIR_SIGMA;

                double sx = (sigmaMat + sigmaEx[i]) * dtSeconds / (2.0 * eps);
                cEx1[i][j] = (1.0 - sx) / (1.0 + sx);
                cEx2[i][j] = (dtSeconds / eps) / (1.0 + sx);

                double sy = (sigmaMat + sigmaEy[j]) * dtSeconds / (2.0 * eps);
                cEy1[i][j] = (1.0 - sy) / (1.0 + sy);
                cEy2[i][j] = (dtSeconds / eps) / (1.0 + sy);
            }
        }

        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny - 1; j++) {
                double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i][j + 1]);
                double mu = MU0 * Math.max(1.0e-6, muRavg);
                double s = sigmaMy[j] * dtSeconds / (2.0 * mu);
                bHx[i][j] = (1.0 - s) / (1.0 + s);
                cHx[i][j] = (dtSeconds / (mu * dxMeters)) / (1.0 + s);
            }
        }

        for (int i = 0; i < nx - 1; i++) {
            for (int j = 0; j < ny; j++) {
                double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i + 1][j]);
                double mu = MU0 * Math.max(1.0e-6, muRavg);
                double s = sigmaMx[i] * dtSeconds / (2.0 * mu);
                bHy[i][j] = (1.0 - s) / (1.0 + s);
                cHy[i][j] = (dtSeconds / (mu * dxMeters)) / (1.0 + s);
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

    private void updateH() {
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny - 1; j++) {
                double dEdy = (ez[i][j + 1] - ez[i][j]) / dxMeters;
                double next = bHx[i][j] * hx[i][j] - cHx[i][j] * dEdy;
                hx[i][j] = Double.isFinite(next) ? next : 0.0;
            }
        }

        for (int i = 0; i < nx - 1; i++) {
            for (int j = 0; j < ny; j++) {
                double dEdx = (ez[i + 1][j] - ez[i][j]) / dxMeters;
                double next = bHy[i][j] * hy[i][j] + cHy[i][j] * dEdx;
                hy[i][j] = Double.isFinite(next) ? next : 0.0;
            }
        }
    }

    private void updateE() {
        for (int i = 1; i < nx - 1; i++) {
            for (int j = 1; j < ny - 1; j++) {
                double curlHy = (hy[i][j] - hy[i - 1][j]) / dxMeters;
                double curlHx = (hx[i][j] - hx[i][j - 1]) / dxMeters;

                double ex = cEx1[i][j] * ezx[i][j] + cEx2[i][j] * curlHy;
                double ey = cEy1[i][j] * ezy[i][j] - cEy2[i][j] * curlHx;
                if (!Double.isFinite(ex)) ex = 0.0;
                if (!Double.isFinite(ey)) ey = 0.0;
                ezx[i][j] = ex;
                ezy[i][j] = ey;
                double sum = ex + ey;
                ez[i][j] = Double.isFinite(sum) ? sum : 0.0;
            }
        }
    }

    private void injectSources() {
        for (Source s : sources) {
            if (s.gx <= 0 || s.gx >= nx - 1 || s.gy <= 0 || s.gy >= ny - 1) continue;

            double ramp = 1.0;
            if (stepCount < s.rampSteps) {
                double u = clampDouble(stepCount / (double) s.rampSteps, 0.0, 1.0);
                ramp = Math.sin(0.5 * Math.PI * u);
                ramp *= ramp;
            }

            // Soft source + 3x3 분포 주입:
            // Hard source(=)는 Maxwell 방정식을 덮어써 에너지 폭발을 유발.
            // Soft source(+=)는 curl H 결과에 소스를 더해 수치 안정성 유지.
            // 1셀 점 주입보다 파면 이방성(별모양 축방향 로브)을 줄여준다.
            double src = s.amplitude * Math.sin(s.phase) * ramp;
            for (int k = 0; k < SRC_OX.length; k++) {
                int gx = s.gx + SRC_OX[k];
                int gy = s.gy + SRC_OY[k];
                if (gx <= 0 || gx >= nx - 1 || gy <= 0 || gy >= ny - 1) continue;
                double v = src * SRC_W[k];
                ezx[gx][gy] += 0.5 * v;
                ezy[gx][gy] += 0.5 * v;
                ez[gx][gy] = ezx[gx][gy] + ezy[gx][gy];
            }
            s.phase += s.omegaDt;
        }
    }

    private void accumulatePower() {
        double beta = 1.0 - POWER_EMA_ALPHA;
        for (int i = 1; i < nx - 1; i++) {
            for (int j = 1; j < ny - 1; j++) {
                double e = ez[i][j];
                if (!Double.isFinite(e)) {
                    e = 0.0;
                    ez[i][j] = 0.0;
                    ezx[i][j] = 0.0;
                    ezy[i][j] = 0.0;
                }
                double p = e * e;
                if (!Double.isFinite(p)) p = 0.0;
                double prev = powerEma[i][j];
                if (!Double.isFinite(prev) || prev < 0.0) prev = 0.0;
                double next = POWER_EMA_ALPHA * prev + beta * p;
                powerEma[i][j] = Double.isFinite(next) ? next : 0.0;
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
        // Soft Source(+=): Maxwell curl H에 더하는 방식이므로
        // Hard Source(=) 대비 실제 Ez 누적량이 훨씬 작다.
        // 전형적 EIRP 23dBm AP → amplitude: Hard=1.1 V/m → Soft=10 V/m 급으로 증가 필요.
        // 단순히 10× 스케일링으로 적용 (렌더링 정규화로 절대값은 의미 없음)
        double t = clampDouble((eirpDbm - 5.0) / 30.0, 0.0, 1.0);
        return 10.0 * (0.35 + 1.45 * t);
    }

    private static double initialPhase(AP ap, Band band) {
        int h = (ap == null || ap.name == null) ? 0 : ap.name.hashCode();
        int b = (band == null) ? 0 : band.ordinal() * 7919;
        int v = h * 31 + b;
        return ((v & 0xffff) / 65535.0) * Math.PI * 2.0;
    }

    private static void clear2D(double[][] arr) {
        for (double[] row : arr) {
            java.util.Arrays.fill(row, 0.0);
        }
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
