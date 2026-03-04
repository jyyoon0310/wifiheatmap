package app.engine.fdtd;

import java.util.function.BiConsumer;

/**
 * 2D TEz FDTD (Yee grid) + split-field PML.
 *
 * 물리량/배열 정의 (인덱스 기준):
 * - Ez(i,j)  : z축 전기장 [V/m], 셀 중심
 * - Hx(i,j)  : x축 자기장 [A/m], Ez(i,j)와 Ez(i,j+1) 사이의 y-엣지
 *              -> 배열 크기 [nx][ny-1]
 * - Hy(i,j)  : y축 자기장 [A/m], Ez(i,j)와 Ez(i+1,j) 사이의 x-엣지
 *              -> 배열 크기 [nx-1][ny]
 *
 * Yee staggered grid에서 curl 연산 인덱싱:
 * - dEz/dy  -> Hx 업데이트
 * - dEz/dx  -> Hy 업데이트
 * - dHy/dx, dHx/dy -> Ez 업데이트
 *
 * PML:
 * - split-field 방식으로 Ez를 Ezx/Ezy로 분리
 * - x/y 방향 전기 도전율 sigmaEx/sigmaEy, 자기 도전율 sigmaMx/sigmaMy 사용
 */
public final class TezFdtdSolver {
    private static final double EPS0 = 8.854_187_812_8e-12;   // [F/m]
    private static final double MU0 = 1.256_637_062_12e-6;    // [H/m]
    private static final double C0 = 299_792_458.0;           // [m/s]

    private final FdtdMaterialGrid grid;
    private final FdtdConfig cfg;
    private final int sourceX;
    private final int sourceY;

    private final int nx;
    private final int ny;
    private final double dx;
    private final double dy;
    private final double dt;

    private final double[][] ez;
    private final double[][] ezx;
    private final double[][] ezy;
    private final double[][] hx; // [nx][ny-1]
    private final double[][] hy; // [nx-1][ny]

    private final double[] sigmaEx;
    private final double[] sigmaEy;
    private final double[] sigmaMx;
    private final double[] sigmaMy;

    // Ez split-field 계수
    private final double[][] cEx1;
    private final double[][] cEx2;
    private final double[][] cEy1;
    private final double[][] cEy2;

    // H 계수
    private final double[][] bHx;
    private final double[][] cHx;
    private final double[][] bHy;
    private final double[][] cHy;

    public record Result(double[][] rmsEz,
                         double dtSeconds,
                         int settleStep,
                         int rmsWindowSteps,
                         int periodSteps,
                         int accumulatedSamples) {}

    public TezFdtdSolver(FdtdMaterialGrid grid, FdtdConfig cfg, int sourceX, int sourceY) {
        this.grid = grid;
        this.cfg = cfg;
        this.sourceX = clamp(sourceX, 1, grid.nx - 2);
        this.sourceY = clamp(sourceY, 1, grid.ny - 2);
        this.nx = grid.nx;
        this.ny = grid.ny;
        this.dx = grid.dxMeters;
        this.dy = grid.dxMeters;

        // Courant 안정조건(2D, dx=dy): dt <= dx / (c * sqrt(2))
        double dtMax = dx / (C0 * Math.sqrt(2.0));
        this.dt = 0.99 * dtMax;

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

        buildPmlProfiles();
        buildCoefficients();
    }

    public double dtSeconds() {
        return dt;
    }

    public double courantLimitSeconds() {
        return dx / (C0 * Math.sqrt(2.0));
    }

    public Result run(BiConsumer<Integer, Double> progressStepAndTimeNs) {
        int totalSteps = Math.max(10, cfg.totalSteps);
        double period = 1.0 / cfg.frequencyHz;
        int periodSteps = Math.max(1, (int) Math.round(period / dt));
        int rampSteps = Math.max(0, (int) Math.round(cfg.rampTimeSeconds / dt));
        int rmsWindowSteps = Math.max(periodSteps, cfg.rmsCycles * periodSteps);
        int settleStep = Math.max(rampSteps + 5 * periodSteps, totalSteps - rmsWindowSteps);
        settleStep = clamp(settleStep, 0, totalSteps - 1);

        double[][] sumSq = new double[nx][ny];
        int accumCount = 0;

        for (int step = 0; step < totalSteps; step++) {
            updateH();
            updateE();
            injectSource(step);

            if (step >= settleStep) {
                accumCount++;
                for (int i = 1; i < nx - 1; i++) {
                    for (int j = 1; j < ny - 1; j++) {
                        double v = ez[i][j];
                        sumSq[i][j] += v * v;
                    }
                }
            }

            if (progressStepAndTimeNs != null && (step == 0 || step % 50 == 0 || step == totalSteps - 1)) {
                progressStepAndTimeNs.accept(step + 1, (step + 1) * dt * 1.0e9);
            }
        }

        double[][] rms = new double[nx][ny];
        double inv = (accumCount > 0) ? (1.0 / accumCount) : 0.0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                rms[i][j] = (accumCount > 0) ? Math.sqrt(Math.max(0.0, sumSq[i][j] * inv)) : 0.0;
            }
        }

        return new Result(rms, dt, settleStep, rmsWindowSteps, periodSteps, accumCount);
    }

    private void buildPmlProfiles() {
        int pml = grid.pmlCells;
        double m = 3.5;      // polynomial grading order
        double r0 = 1.0e-8;  // target reflection coefficient

        // sigma_max ≈ -((m+1) ln(r0)) * eps0 * c / (2 * pml * dx)
        double sigmaEmax = -((m + 1.0) * Math.log(r0)) * (EPS0 * C0) / (2.0 * Math.max(1, pml) * dx);
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
        // Ez split-field 계수.
        //
        // 일반 전도성 매질 Ez 업데이트의 안정형 계수:
        //   C1 = (1 - sigma*dt/(2*eps)) / (1 + sigma*dt/(2*eps))
        //   C2 = (dt/eps) / (1 + sigma*dt/(2*eps))
        //
        // split-field에서는 Ez = Ezx + Ezy로 분리하고,
        // Ezx에는 x-PML(sigmaEx), Ezy에는 y-PML(sigmaEy)를 독립 적용한다.
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                double eps = EPS0 * Math.max(1.0e-6, grid.epsR[i][j]);
                double sigmaMat = Math.max(0.0, grid.sigma[i][j]);

                // Ezx는 dHy/dx 항 담당 -> x방향 sigmaEx 적용
                double sx = (sigmaMat + sigmaEx[i]) * dt / (2.0 * eps);
                cEx1[i][j] = (1.0 - sx) / (1.0 + sx);
                cEx2[i][j] = (dt / eps) / (1.0 + sx);

                // Ezy는 dHx/dy 항 담당 -> y방향 sigmaEy 적용
                double sy = (sigmaMat + sigmaEy[j]) * dt / (2.0 * eps);
                cEy1[i][j] = (1.0 - sy) / (1.0 + sy);
                cEy2[i][j] = (dt / eps) / (1.0 + sy);
            }
        }

        // H 계수
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny - 1; j++) {
                // Hx(i,j)는 Ez(i,j), Ez(i,j+1) 사이 y-edge에 위치
                double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i][j + 1]);
                double mu = MU0 * Math.max(1.0e-6, muRavg);
                double s = sigmaMy[j] * dt / (2.0 * mu);
                bHx[i][j] = (1.0 - s) / (1.0 + s);
                cHx[i][j] = (dt / (mu * dy)) / (1.0 + s);
            }
        }
        for (int i = 0; i < nx - 1; i++) {
            for (int j = 0; j < ny; j++) {
                // Hy(i,j)는 Ez(i,j), Ez(i+1,j) 사이 x-edge에 위치
                double muRavg = 0.5 * (grid.muR[i][j] + grid.muR[i + 1][j]);
                double mu = MU0 * Math.max(1.0e-6, muRavg);
                double s = sigmaMx[i] * dt / (2.0 * mu);
                bHy[i][j] = (1.0 - s) / (1.0 + s);
                cHy[i][j] = (dt / (mu * dx)) / (1.0 + s);
            }
        }
    }

    private void updateH() {
        // Hx(i,j) = Hx(i,j) - (dt/mu)*(Ez(i,j+1)-Ez(i,j))/dy
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny - 1; j++) {
                double dEdy = (ez[i][j + 1] - ez[i][j]) / dy;
                hx[i][j] = bHx[i][j] * hx[i][j] - cHx[i][j] * dEdy;
            }
        }

        // Hy(i,j) = Hy(i,j) + (dt/mu)*(Ez(i+1,j)-Ez(i,j))/dx
        for (int i = 0; i < nx - 1; i++) {
            for (int j = 0; j < ny; j++) {
                double dEdx = (ez[i + 1][j] - ez[i][j]) / dx;
                hy[i][j] = bHy[i][j] * hy[i][j] + cHy[i][j] * dEdx;
            }
        }
    }

    private void updateE() {
        // Ez split-field:
        // Ezx <- dHy/dx 항
        // Ezy <- dHx/dy 항
        // Ez = Ezx + Ezy
        for (int i = 1; i < nx - 1; i++) {
            for (int j = 1; j < ny - 1; j++) {
                double curlHy = (hy[i][j] - hy[i - 1][j]) / dx;
                double curlHx = (hx[i][j] - hx[i][j - 1]) / dy;

                ezx[i][j] = cEx1[i][j] * ezx[i][j] + cEx2[i][j] * curlHy;
                ezy[i][j] = cEy1[i][j] * ezy[i][j] - cEy2[i][j] * curlHx;
                ez[i][j] = ezx[i][j] + ezy[i][j];
            }
        }
    }

    private void injectSource(int step) {
        double t = step * dt;
        double ramp;
        if (cfg.rampTimeSeconds <= 1.0e-15) {
            ramp = 1.0;
        } else {
            double u = Math.min(1.0, t / cfg.rampTimeSeconds);
            ramp = Math.pow(Math.sin(0.5 * Math.PI * u), 2.0);
        }

        // CW source: Ez += A * sin(2π f t) * ramp
        double src = cfg.sourceAmplitude * Math.sin(2.0 * Math.PI * cfg.frequencyHz * t) * ramp;

        // split-field 일관성을 위해 Ezx/Ezy에 절반씩 주입
        ezx[sourceX][sourceY] += 0.5 * src;
        ezy[sourceX][sourceY] += 0.5 * src;
        ez[sourceX][sourceY] = ezx[sourceX][sourceY] + ezy[sourceX][sourceY];
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
