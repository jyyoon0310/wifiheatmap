package app.solver.v2;

/**
 * 평면도(px) <-> 물리좌표(m) <-> FDTD 격자 인덱스 변환기.
 *
 * Solver v2에서 사용하는 좌표 변환을 한 곳으로 모아
 * material grid 생성/소스 배치/디버그 렌더가 같은 기준을 쓰도록 한다.
 */
public final class FloorplanGridTransform {
    private final int widthPx;
    private final int heightPx;
    private final double scaleMPerPx;
    private final double dxMeters;
    private final int pmlCells;
    private final int mapNx;
    private final int mapNy;
    private final int nx;
    private final int ny;

    public FloorplanGridTransform(int widthPx,
                                  int heightPx,
                                  double scaleMPerPx,
                                  double dxMeters,
                                  int pmlCells) {
        this.widthPx = Math.max(1, widthPx);
        this.heightPx = Math.max(1, heightPx);
        this.scaleMPerPx = (Double.isFinite(scaleMPerPx) && scaleMPerPx > 1.0e-9) ? scaleMPerPx : 0.01;
        this.dxMeters = Math.max(1.0e-4, dxMeters);
        this.pmlCells = Math.max(0, pmlCells);

        double mapWidthM = this.widthPx * this.scaleMPerPx;
        double mapHeightM = this.heightPx * this.scaleMPerPx;
        this.mapNx = Math.max(4, (int) Math.ceil(mapWidthM / this.dxMeters));
        this.mapNy = Math.max(4, (int) Math.ceil(mapHeightM / this.dxMeters));
        this.nx = this.mapNx + 2 * this.pmlCells;
        this.ny = this.mapNy + 2 * this.pmlCells;
    }

    public int mapNx() { return mapNx; }
    public int mapNy() { return mapNy; }
    public int nx() { return nx; }
    public int ny() { return ny; }

    public int toGridX(double px) {
        int local = (int) Math.round((px * scaleMPerPx) / dxMeters);
        return clamp(local + pmlCells, 0, nx - 1);
    }

    public int toGridY(double py) {
        int local = (int) Math.round((py * scaleMPerPx) / dxMeters);
        return clamp(local + pmlCells, 0, ny - 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
