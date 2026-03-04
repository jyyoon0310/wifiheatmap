package app.engine.fdtd;

import app.model.Band;

/**
 * 2D TEz FDTD 계산 파라미터.
 *
 * 단위:
 * - dxMeters: 격자 간격(m)
 * - rampTimeSeconds: 소스 램프 시간(s)
 * - frequencyHz: 소스 주파수(Hz)
 */
public final class FdtdConfig {
    public final Band band;
    public final double frequencyHz;
    public final double dxMeters;
    public final int totalSteps;
    public final int pmlCells;
    public final double rampTimeSeconds;
    public final double sourceAmplitude;
    public final int rmsCycles;
    public final FdtdReferenceMode referenceMode;
    public final double customReference;
    public final FdtdWallPreset wallPreset;
    public final boolean showMaterialGrid;
    public final boolean showPmlGrid;

    public FdtdConfig(Band band,
                      double frequencyHz,
                      double dxMeters,
                      int totalSteps,
                      int pmlCells,
                      double rampTimeSeconds,
                      double sourceAmplitude,
                      int rmsCycles,
                      FdtdReferenceMode referenceMode,
                      double customReference,
                      FdtdWallPreset wallPreset,
                      boolean showMaterialGrid,
                      boolean showPmlGrid) {
        this.band = (band == null) ? Band.GHZ_24 : band;
        this.frequencyHz = Math.max(1.0, frequencyHz);
        this.dxMeters = Math.max(1.0e-4, dxMeters);
        this.totalSteps = Math.max(10, totalSteps);
        // split-field PML 안정성을 위해 최소 8 cells 강제
        this.pmlCells = Math.max(8, pmlCells);
        this.rampTimeSeconds = Math.max(0.0, rampTimeSeconds);
        this.sourceAmplitude = Math.max(1.0e-6, sourceAmplitude);
        this.rmsCycles = Math.max(1, rmsCycles);
        this.referenceMode = (referenceMode == null) ? FdtdReferenceMode.AP_NEAR_RING : referenceMode;
        this.customReference = Math.max(1.0e-12, customReference);
        this.wallPreset = (wallPreset == null) ? FdtdWallPreset.FROM_WALL : wallPreset;
        this.showMaterialGrid = showMaterialGrid;
        this.showPmlGrid = showPmlGrid;
    }

    public double frequencyGhz() {
        return frequencyHz / 1.0e9;
    }
}
