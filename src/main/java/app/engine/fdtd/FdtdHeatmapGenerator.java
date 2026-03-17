package app.engine.fdtd;

import app.engine.WifiMath;
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
import java.util.function.Consumer;

/**
 * 거리 기반 경로손실 모델 대신 2D TEz FDTD로 RMS 전계 맵을 생성하고
 * 기존 컬러맵(rssiToColor)으로 렌더링한다.
 *
 * 결과 dB는 절대 측정값이 아닌 "RSSI-like 상대값"이며,
 * 아래 방식으로 정규화/앵커링한다.
 * 1) FDTD로 RMS(Ez) 계산
 * 2) 선택한 ref 기준으로 relDb = 20 log10(RMS/ref)
 * 3) AP의 tx/gain/대역폭 패널티 + 1m FSPL을 더해 RSSI-like dBm으로 매핑
 */
public final class FdtdHeatmapGenerator {
    private static final double C0 = 299_792_458.0; // [m/s]

    private record SourceSnapshot(double xPx,
                                  double yPx,
                                  String name,
                                  double txPowerDbm,
                                  double antennaGainDbi,
                                  double bandwidthPenaltyDb) {
    }

    private final List<Wall> walls = new ArrayList<>();
    private final List<SourceSnapshot> sources = new ArrayList<>();
    private final double scaleMPerPx;
    private final double pathLossN;

    public FdtdHeatmapGenerator(WifiEnvironment env, Band simBand) {
        if (env != null) {
            walls.addAll(env.getWalls());
            this.scaleMPerPx = env.getScaleMPerPx();
            this.pathLossN = env.getPathLossN();
            for (AP ap : env.getAps()) {
                if (ap == null || !ap.enabled) continue;
                RadioConfig rc = ap.radios.get(simBand);
                if (rc == null || !rc.enabled) continue;
                sources.add(new SourceSnapshot(
                        ap.x, ap.y,
                        ap.name,
                        rc.txPowerDbm,
                        rc.antennaGain,
                        rc.bandwidthPenaltyDb()
                ));
            }
        } else {
            this.scaleMPerPx = Double.NaN;
            this.pathLossN = 3.5;
        }
    }

    public WritableImage generate(int widthPx,
                                  int heightPx,
                                  double legendMinDbm,
                                  double legendMaxDbm,
                                  int smoothRadiusPx,
                                  FdtdConfig cfg,
                                  Consumer<FdtdProgress> progressCallback) {

        WritableImage empty = new WritableImage(Math.max(1, widthPx), Math.max(1, heightPx));
        if (sources.isEmpty()) return empty;
        if (!Double.isFinite(scaleMPerPx) || scaleMPerPx <= 0.0) return empty;

        FdtdMaterialGrid grid = FdtdMaterialGridBuilder.build(
                widthPx, heightPx, scaleMPerPx, cfg, walls
        );

        long totalPlan = (long) cfg.totalSteps * sources.size();
        // 파장/파수 정보 로그:
        // lambda = c / f [m], k = 2*pi/lambda [rad/m]
        double lambda = C0 / cfg.frequencyHz;
        double k = 2.0 * Math.PI / lambda;
        System.out.printf(
                "[FDTD] freq=%.3fGHz (lambda=%.4fm, k=%.3frad/m) dx=%.4fm steps=%d pml=%d ramp=%.3fns ref=%s wallPreset=%s%n",
                cfg.frequencyGhz(), lambda, k, cfg.dxMeters, cfg.totalSteps, cfg.pmlCells, cfg.rampTimeSeconds * 1.0e9,
                cfg.referenceMode, cfg.wallPreset
        );

        double[][] strongestDb = new double[grid.mapNx][grid.mapNy];
        for (int x = 0; x < grid.mapNx; x++) {
            java.util.Arrays.fill(strongestDb[x], -200.0);
        }

        for (int s = 0; s < sources.size(); s++) {
            SourceSnapshot src = sources.get(s);
            int sx = grid.toGridX(src.xPx);
            int sy = grid.toGridY(src.yPx);

            TezFdtdSolver solver = new TezFdtdSolver(grid, cfg, sx, sy);
            double dtNs = solver.dtSeconds() * 1.0e9;
            System.out.printf(
                    "[FDTD] source %d/%d (%s) grid=(%d,%d), dt=%.6fns (courant<=%.6fns)%n",
                    s + 1, sources.size(), src.name, sx, sy, dtNs, solver.courantLimitSeconds() * 1.0e9
            );

            int sourceIndex = s;
            TezFdtdSolver.Result result = solver.run((step, simTimeNs) -> {
                if (progressCallback == null) return;
                long done = (long) sourceIndex * cfg.totalSteps + step;
                String msg = String.format("FDTD %d/%d source, step %d/%d",
                        sourceIndex + 1, sources.size(), step, cfg.totalSteps);
                progressCallback.accept(new FdtdProgress(
                        sourceIndex + 1,
                        sources.size(),
                        step,
                        cfg.totalSteps,
                        done,
                        totalPlan,
                        simTimeNs,
                        msg
                ));
            });

            accumulateStrongestFromSource(grid, cfg, src, sx, sy, result.rmsEz(), strongestDb);
        }

        WritableImage out = renderRssiLikeImage(
                strongestDb, grid, widthPx, heightPx, legendMinDbm, legendMaxDbm, cfg
        );
        if (smoothRadiusPx > 0) {
            out = WifiMath.boxBlur(out, smoothRadiusPx);
        }
        return out;
    }

    private void accumulateStrongestFromSource(FdtdMaterialGrid grid,
                                               FdtdConfig cfg,
                                               SourceSnapshot src,
                                               int sx,
                                               int sy,
                                               double[][] rmsEz,
                                               double[][] strongestDb) {
        double ref = resolveReference(cfg.referenceMode, cfg.customReference, rmsEz, grid, sx, sy);
        ref = Math.max(ref, 1.0e-12);

        // RSSI-like anchor:
        // AP의 tx/gain에서 1m FSPL만 뺀 뒤 relDb를 더한다.
        // (RSSI 전력맵에서는 BW 패널티를 직접 차감하지 않음)
        double nearCompAt1m = WifiMath.nearFieldBandCompensationDb(1.0, cfg.frequencyGhz());
        double anchorDbm = src.txPowerDbm + src.antennaGainDbi
                + nearCompAt1m
                - WifiMath.pathLossDb(1.0, cfg.frequencyGhz(), pathLossN);

        for (int mx = 0; mx < grid.mapNx; mx++) {
            int gx = mx + grid.pmlCells;
            for (int my = 0; my < grid.mapNy; my++) {
                int gy = my + grid.pmlCells;
                double rms = Math.max(1.0e-12, rmsEz[gx][gy]);
                double relDb = 20.0 * Math.log10(rms / ref);
                double dbLike = anchorDbm + relDb;
                if (dbLike > strongestDb[mx][my]) {
                    strongestDb[mx][my] = dbLike;
                }
            }
        }
    }

    private WritableImage renderRssiLikeImage(double[][] strongestDb,
                                              FdtdMaterialGrid grid,
                                              int widthPx,
                                              int heightPx,
                                              double legendMinDbm,
                                              double legendMaxDbm,
                                              FdtdConfig cfg) {
        WritableImage img = new WritableImage(Math.max(1, widthPx), Math.max(1, heightPx));
        PixelWriter pw = img.getPixelWriter();

        for (int py = 0; py < heightPx; py++) {
            int my = clamp((int) Math.floor((py * grid.scaleMPerPx) / grid.dxMeters), 0, grid.mapNy - 1);
            int gy = my + grid.pmlCells;

            for (int px = 0; px < widthPx; px++) {
                int mx = clamp((int) Math.floor((px * grid.scaleMPerPx) / grid.dxMeters), 0, grid.mapNx - 1);
                int gx = mx + grid.pmlCells;

                double db = strongestDb[mx][my];
                Color base = WifiMath.rssiToColor(db, legendMinDbm, legendMaxDbm);

                if (cfg.showMaterialGrid) {
                    int code = grid.materialCode[gx][gy];
                    if (code > 0) {
                        Color overlay = switch (code) {
                            case 2 -> Color.color(1.0, 0.65, 0.2, 1.0); // door
                            case 3 -> Color.color(0.35, 0.9, 1.0, 1.0); // window
                            default -> Color.color(0.95, 0.95, 0.95, 1.0); // wall
                        };
                        base = base.interpolate(overlay, 0.32);
                    }
                }

                pw.setColor(px, py, base);
            }
        }

        if (cfg.showPmlGrid) {
            // PML은 계산 도메인 바깥으로 pad되어 있으므로, 이미지 경계에 "PML 접면" 프레임을 그려 디버그 표시.
            drawFrame(pw, widthPx, heightPx, Color.color(0.2, 0.95, 1.0, 0.85), 2);
        }
        if (cfg.showMaterialGrid || cfg.showPmlGrid) {
            drawSourceAndReferenceDebug(pw, widthPx, heightPx, grid, cfg);
        }
        return img;
    }

    /**
     * 디버그 시각화:
     * - AP 소스 위치
     * - AP_NEAR_RING reference 모드일 때 기준 반경(내/외곽) 링
     */
    private void drawSourceAndReferenceDebug(PixelWriter pw,
                                             int widthPx,
                                             int heightPx,
                                             FdtdMaterialGrid grid,
                                             FdtdConfig cfg) {
        int rInPx = Math.max(3, (int) Math.round((0.08 / grid.scaleMPerPx)));
        int rOutPx = Math.max(rInPx + 1, (int) Math.round((0.20 / grid.scaleMPerPx)));
        for (SourceSnapshot src : sources) {
            int cx = clamp((int) Math.round(src.xPx), 0, widthPx - 1);
            int cy = clamp((int) Math.round(src.yPx), 0, heightPx - 1);
            drawCross(pw, widthPx, heightPx, cx, cy, 5, Color.color(1.0, 1.0, 1.0, 0.95));
            drawCross(pw, widthPx, heightPx, cx, cy, 3, Color.color(0.15, 0.8, 1.0, 0.95));
            if (cfg.referenceMode == FdtdReferenceMode.AP_NEAR_RING) {
                drawCircle(pw, widthPx, heightPx, cx, cy, rInPx, Color.color(1.0, 0.7, 0.15, 0.65));
                drawCircle(pw, widthPx, heightPx, cx, cy, rOutPx, Color.color(1.0, 0.7, 0.15, 0.65));
            }
        }
    }

    private static double resolveReference(FdtdReferenceMode mode,
                                           double customRef,
                                           double[][] rms,
                                           FdtdMaterialGrid grid,
                                           int sx,
                                           int sy) {
        if (mode == FdtdReferenceMode.CUSTOM) {
            return Math.max(1.0e-12, customRef);
        }

        double max = 1.0e-12;
        for (int x = grid.pmlCells; x < grid.pmlCells + grid.mapNx; x++) {
            for (int y = grid.pmlCells; y < grid.pmlCells + grid.mapNy; y++) {
                max = Math.max(max, rms[x][y]);
            }
        }
        if (mode == FdtdReferenceMode.GLOBAL_MAX) {
            return max;
        }

        // AP_NEAR_RING: 소스 주변 링(약 0.08m~0.20m) RMS 평균
        int rIn = Math.max(2, (int) Math.round(0.08 / grid.dxMeters));
        int rOut = Math.max(rIn + 1, (int) Math.round(0.20 / grid.dxMeters));
        double sum = 0.0;
        int count = 0;
        for (int x = Math.max(grid.pmlCells, sx - rOut); x <= Math.min(grid.pmlCells + grid.mapNx - 1, sx + rOut); x++) {
            for (int y = Math.max(grid.pmlCells, sy - rOut); y <= Math.min(grid.pmlCells + grid.mapNy - 1, sy + rOut); y++) {
                int dx = x - sx;
                int dy = y - sy;
                int d2 = dx * dx + dy * dy;
                if (d2 < rIn * rIn || d2 > rOut * rOut) continue;
                sum += rms[x][y];
                count++;
            }
        }
        if (count <= 0) return max;
        return Math.max(1.0e-12, sum / count);
    }

    private static void drawFrame(PixelWriter pw, int w, int h, Color c, int t) {
        for (int k = 0; k < t; k++) {
            int x0 = k, y0 = k, x1 = w - 1 - k, y1 = h - 1 - k;
            if (x0 >= x1 || y0 >= y1) return;
            for (int x = x0; x <= x1; x++) {
                pw.setColor(x, y0, c);
                pw.setColor(x, y1, c);
            }
            for (int y = y0; y <= y1; y++) {
                pw.setColor(x0, y, c);
                pw.setColor(x1, y, c);
            }
        }
    }

    private static void drawCross(PixelWriter pw, int w, int h, int cx, int cy, int r, Color c) {
        for (int k = -r; k <= r; k++) {
            int x = cx + k;
            int y = cy + k;
            int y2 = cy - k;
            if (x >= 0 && x < w && cy >= 0 && cy < h) pw.setColor(x, cy, c);
            if (cx >= 0 && cx < w && y >= 0 && y < h) pw.setColor(cx, y, c);
            if (x >= 0 && x < w && y2 >= 0 && y2 < h) pw.setColor(x, y2, c);
        }
    }

    private static void drawCircle(PixelWriter pw, int w, int h, int cx, int cy, int r, Color c) {
        int rr = r * r;
        int band = Math.max(1, (int) Math.round(0.18 * r)); // 너무 두껍지 않게 근사 링 두께
        int rrIn = Math.max(0, (r - band) * (r - band));
        int x0 = Math.max(0, cx - r);
        int x1 = Math.min(w - 1, cx + r);
        int y0 = Math.max(0, cy - r);
        int y1 = Math.min(h - 1, cy + r);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                int dx = x - cx;
                int dy = y - cy;
                int d2 = dx * dx + dy * dy;
                if (d2 > rr || d2 < rrIn) continue;
                pw.setColor(x, y, c);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
