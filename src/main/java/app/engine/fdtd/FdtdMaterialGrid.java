package app.engine.fdtd;

/**
 * FDTD용 재질 격자.
 *
 * Yee grid 기준:
 * - Ez(i,j)는 셀 중심(정수 인덱스)에서 샘플링
 * - Hx, Hy는 Ez 셀 경계(반 셀 오프셋)에 배치되며, 시뮬레이터에서 별도 배열로 운용
 *
 * 본 클래스는 Ez 셀 기준 재질(εr, μr, σ)을 보관한다.
 */
public final class FdtdMaterialGrid {
    public final int nx;
    public final int ny;
    public final int pmlCells;
    public final int mapNx;
    public final int mapNy;
    public final double dxMeters;
    public final double scaleMPerPx;

    /** Ez 셀 기준 상대 유전율 εr(i,j) */
    public final double[][] epsR;
    /** Ez 셀 기준 상대 투자율 μr(i,j) */
    public final double[][] muR;
    /** Ez 셀 기준 도전율 σ(i,j) [S/m] */
    public final double[][] sigma;
    /**
     * 디버그 렌더링용 재질 코드.
     * 0: air, 1: wall, 2: door, 3: window
     */
    public final int[][] materialCode;
    /** PML 영역 여부 (true면 PML) */
    public final boolean[][] pmlMask;

    public FdtdMaterialGrid(int nx,
                            int ny,
                            int pmlCells,
                            int mapNx,
                            int mapNy,
                            double dxMeters,
                            double scaleMPerPx,
                            double[][] epsR,
                            double[][] muR,
                            double[][] sigma,
                            int[][] materialCode,
                            boolean[][] pmlMask) {
        this.nx = nx;
        this.ny = ny;
        this.pmlCells = pmlCells;
        this.mapNx = mapNx;
        this.mapNy = mapNy;
        this.dxMeters = dxMeters;
        this.scaleMPerPx = scaleMPerPx;
        this.epsR = epsR;
        this.muR = muR;
        this.sigma = sigma;
        this.materialCode = materialCode;
        this.pmlMask = pmlMask;
    }

    /**
     * 평면도 px 좌표를 Ez 격자 인덱스로 변환.
     */
    public int toGridX(double px) {
        int local = (int) Math.floor((px * scaleMPerPx) / dxMeters);
        return clamp(local + pmlCells, 0, nx - 1);
    }

    public int toGridY(double py) {
        int local = (int) Math.floor((py * scaleMPerPx) / dxMeters);
        return clamp(local + pmlCells, 0, ny - 1);
    }

    /**
     * Ez 격자 인덱스를 평면도 map 내부 인덱스로 변환(0..mapN-1).
     */
    public int toMapX(int gx) {
        return clamp(gx - pmlCells, 0, mapNx - 1);
    }

    public int toMapY(int gy) {
        return clamp(gy - pmlCells, 0, mapNy - 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

