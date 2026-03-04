package app.engine.fdtd;

import app.model.Wall;
import app.model.WallMaterial;

import java.util.List;

/**
 * 평면도 좌표(px) + 벽 선분 데이터를 FDTD 재질 격자(Ez 셀 기준)로 변환한다.
 *
 * 좌표계:
 * - 입력 wall 좌표는 평면도 pixel 좌표
 * - FDTD 계산은 meter 단위 격자(dx)로 수행
 * - scaleMPerPx를 이용해 px -> m 변환 후 격자 인덱스로 사상
 */
public final class FdtdMaterialGridBuilder {

    private FdtdMaterialGridBuilder() {
    }

    private record MaterialProps(double epsR, double muR, double sigma, int code, double thicknessM) {}

    public static FdtdMaterialGrid build(int widthPx,
                                         int heightPx,
                                         double scaleMPerPx,
                                         FdtdConfig cfg,
                                         List<Wall> walls) {

        double safeScale = (Double.isFinite(scaleMPerPx) && scaleMPerPx > 1.0e-9) ? scaleMPerPx : 0.01;
        double dx = Math.max(1.0e-4, cfg.dxMeters);
        int pml = Math.max(8, cfg.pmlCells);

        double mapWidthM = Math.max(1.0e-6, widthPx * safeScale);
        double mapHeightM = Math.max(1.0e-6, heightPx * safeScale);

        // mapNx/mapNy: 실제 평면도 영역을 표현하는 Ez 셀 수(내부 도메인)
        int mapNx = Math.max(4, (int) Math.ceil(mapWidthM / dx));
        int mapNy = Math.max(4, (int) Math.ceil(mapHeightM / dx));

        // 전체 계산 도메인 = 내부 도메인 + 양쪽 PML
        int nx = mapNx + 2 * pml;
        int ny = mapNy + 2 * pml;

        double[][] epsR = new double[nx][ny];
        double[][] muR = new double[nx][ny];
        double[][] sigma = new double[nx][ny];
        int[][] materialCode = new int[nx][ny];
        boolean[][] pmlMask = new boolean[nx][ny];

        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                epsR[i][j] = 1.0;   // air
                muR[i][j] = 1.0;    // air
                sigma[i][j] = 0.0;  // air
                materialCode[i][j] = 0;
                pmlMask[i][j] = (i < pml || i >= nx - pml || j < pml || j >= ny - pml);
            }
        }

        if (walls != null) {
            for (Wall w : walls) {
                if (w == null) continue;
                MaterialProps props = resolveMaterial(w, cfg.wallPreset, cfg.frequencyGhz());

                // wall endpoint를 meter -> cell index로 변환
                int x0 = clamp((int) Math.round((w.x1 * safeScale) / dx) + pml, 0, nx - 1);
                int y0 = clamp((int) Math.round((w.y1 * safeScale) / dx) + pml, 0, ny - 1);
                int x1 = clamp((int) Math.round((w.x2 * safeScale) / dx) + pml, 0, nx - 1);
                int y1 = clamp((int) Math.round((w.y2 * safeScale) / dx) + pml, 0, ny - 1);

                int radius = Math.max(0, (int) Math.round(0.5 * props.thicknessM / dx));
                rasterLine(x0, y0, x1, y1, (x, y) -> paintDisk(
                        x, y, radius, nx, ny, pml,
                        props.epsR, props.muR, props.sigma, props.code,
                        epsR, muR, sigma, materialCode
                ));
            }
        }

        return new FdtdMaterialGrid(
                nx, ny, pml, mapNx, mapNy, dx, safeScale,
                epsR, muR, sigma, materialCode, pmlMask
        );
    }

    private static MaterialProps resolveMaterial(Wall wall, FdtdWallPreset preset, double freqGhz) {
        WallMaterial raw = wall.getMaterial();
        if (raw == null) raw = WallMaterial.CUSTOM;

        // 문/창문은 항상 유지하고, 일반 벽만 프리셋 override 대상.
        WallMaterial mat = raw;
        boolean isDoorOrWindow = (raw == WallMaterial.DOOR || raw == WallMaterial.WINDOW);
        if (!isDoorOrWindow) {
            switch (preset) {
                case GYPSUM -> mat = WallMaterial.DRY_WALL;
                case WOOD -> mat = WallMaterial.BOOKSHELF;
                case CONCRETE -> mat = WallMaterial.CONCRETE_WALL;
                case FROM_WALL -> mat = raw;
            }
        }

        double eps = mat.epsilonReal(freqGhz);
        double sig = mat.conductivity(freqGhz);
        if (!Double.isFinite(eps) || eps <= 1.0e-6 || !Double.isFinite(sig) || sig < 0.0) {
            // epsilon/sigma 모델이 없는 재질(CUSTOM 등)은 attenuation 기반 근사로 변환.
            double db = (freqGhz >= 4.0) ? Math.max(0.0, wall.attenuationDb5) : Math.max(0.0, wall.attenuationDb24);
            eps = clampDouble(1.2 + 0.22 * db, 1.2, 8.0);
            sig = clampDouble(0.001 + 0.0018 * db, 0.0005, 0.08);
        }

        int code;
        if (mat == WallMaterial.DOOR) code = 2;
        else if (mat == WallMaterial.WINDOW) code = 3;
        else code = 1;

        double thicknessM = switch (mat) {
            case DOOR, WINDOW -> 0.05;
            case DRY_WALL, CUBICLE -> 0.10;
            case BRICK_WALL -> 0.12;
            case CONCRETE_WALL -> 0.18;
            default -> 0.10;
        };

        return new MaterialProps(eps, 1.0, sig, code, thicknessM);
    }

    private interface CellConsumer {
        void accept(int x, int y);
    }

    private static void rasterLine(int x0, int y0, int x1, int y1, CellConsumer consumer) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;

        int x = x0;
        int y = y0;
        while (true) {
            consumer.accept(x, y);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private static void paintDisk(int cx,
                                  int cy,
                                  int radius,
                                  int nx,
                                  int ny,
                                  int pml,
                                  double eps,
                                  double mu,
                                  double sig,
                                  int code,
                                  double[][] epsR,
                                  double[][] muR,
                                  double[][] sigma,
                                  int[][] materialCode) {
        int r2 = radius * radius;
        int x0 = clamp(cx - radius, pml, nx - pml - 1);
        int x1 = clamp(cx + radius, pml, nx - pml - 1);
        int y0 = clamp(cy - radius, pml, ny - pml - 1);
        int y1 = clamp(cy + radius, pml, ny - pml - 1);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                int dx = x - cx;
                int dy = y - cy;
                if (dx * dx + dy * dy > r2) continue;
                epsR[x][y] = eps;
                muR[x][y] = mu;
                sigma[x][y] = sig;
                materialCode[x][y] = code;
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampDouble(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
