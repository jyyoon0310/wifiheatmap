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

    private static final double POWER_EMA_ALPHA = 0.94;
    private static final double DISPLAY_MIN_DB = -78.0;
    private static final double DISPLAY_MAX_DB = 0.0;
    private static final double DISPLAY_FLOOR_RATIO = 2.5e-7;
    private static final double AIR_SIGMA = 2.0e-6; // [S/m], air damping (too high면 에너지가 빨리 죽는다)
    private static final int SOURCE_RAMP_CYCLES = 20;
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
    }

    public void step(int subSteps) {
        int n = Math.max(1, subSteps);
        for (int i = 0; i < n; i++) {
            stepOnce();
        }
    }

    public WritableImage renderFrame() {
        double localMax = 1.0e-12;
        for (int mx = 0; mx < grid.mapNx; mx++) {
            int gx = mx + pml;
            for (int my = 0; my < grid.mapNy; my++) {
                int gy = my + pml;
                localMax = Math.max(localMax, powerEma[gx][gy]);
            }
        }

        visNorm = Math.max(localMax, visNorm * 0.999);
        visNorm = 0.94 * visNorm + 0.06 * localMax;
        double norm = Math.max(1.0e-12, visNorm);
        double floor = Math.max(norm * DISPLAY_FLOOR_RATIO, 1.0e-14);

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

                double p = Math.max(floor, powerEma[gx][gy]);
                double relDb = 10.0 * Math.log10(p / norm);
                relDb = clampDouble(relDb, DISPLAY_MIN_DB, DISPLAY_MAX_DB);
                double t = (relDb - DISPLAY_MIN_DB) / (DISPLAY_MAX_DB - DISPLAY_MIN_DB);
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
        double r0 = 1.0e-8;
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
                double fNyquistSafe = 0.45 / dtSeconds;
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
                hx[i][j] = bHx[i][j] * hx[i][j] - cHx[i][j] * dEdy;
            }
        }

        for (int i = 0; i < nx - 1; i++) {
            for (int j = 0; j < ny; j++) {
                double dEdx = (ez[i + 1][j] - ez[i][j]) / dxMeters;
                hy[i][j] = bHy[i][j] * hy[i][j] + cHy[i][j] * dEdx;
            }
        }
    }

    private void updateE() {
        for (int i = 1; i < nx - 1; i++) {
            for (int j = 1; j < ny - 1; j++) {
                double curlHy = (hy[i][j] - hy[i - 1][j]) / dxMeters;
                double curlHx = (hx[i][j] - hx[i][j - 1]) / dxMeters;

                ezx[i][j] = cEx1[i][j] * ezx[i][j] + cEx2[i][j] * curlHy;
                ezy[i][j] = cEy1[i][j] * ezy[i][j] - cEy2[i][j] * curlHx;
                ez[i][j] = ezx[i][j] + ezy[i][j];
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

            // Hard source: 소스 셀 에너지를 매 스텝 강제로 유지해
            // "퍼졌다가 완전히 사라지는" 체감 현상을 줄인다.
            double src = s.amplitude * Math.sin(s.phase) * ramp;
            ezx[s.gx][s.gy] = 0.5 * src;
            ezy[s.gx][s.gy] = 0.5 * src;
            ez[s.gx][s.gy] = src;
            s.phase += s.omegaDt;
        }
    }

    private void accumulatePower() {
        double beta = 1.0 - POWER_EMA_ALPHA;
        for (int i = 1; i < nx - 1; i++) {
            for (int j = 1; j < ny - 1; j++) {
                double p = ez[i][j] * ez[i][j];
                powerEma[i][j] = POWER_EMA_ALPHA * powerEma[i][j] + beta * p;
            }
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
        return 0.35 + 1.45 * t;
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

        double alphaBoost = 0.08 * clampDouble((relDb + 10.0) / 10.0, 0.0, 1.0);
        double alpha = clampDouble(0.10 + c.getOpacity() * 0.75 + alphaBoost, 0.08, 0.95);
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
