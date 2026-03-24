package app.engine.fdtd;

import app.model.Band;

/**
 * 2D TEz FDTD 계산 파라미터.
 *
 * 단위:
 * - dxMeters: 격자 간격(m)
 * - rampTimeSeconds: 소스 램프 시간(s)
 * - frequencyHz: 소스 중심 주파수(Hz)
 * - ofdmSubcarriers: OFDM 서브캐리어 수 (1 = 단일 주파수, 7/52 등으로 늘리면 dead zone 완화)
 * - bandwidthHz: 서브캐리어를 분산시킬 총 대역폭(Hz), 0이면 단일 주파수로 동작
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
    /** OFDM 서브캐리어 수. 1이면 단일 주파수(기존 동작). */
    public final int ofdmSubcarriers;
    /** 서브캐리어를 분산시킬 총 대역폭(Hz). 0 또는 ofdmSubcarriers=1이면 단일 주파수로 동작. */
    public final double bandwidthHz;

    /** 기존 13-arg 생성자: ofdmSubcarriers=1, bandwidthHz=0 으로 위임 (하위 호환). */
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
        this(band, frequencyHz, dxMeters, totalSteps, pmlCells, rampTimeSeconds,
                sourceAmplitude, rmsCycles, referenceMode, customReference, wallPreset,
                showMaterialGrid, showPmlGrid, 1, 0.0);
    }

    /** 전체 파라미터 생성자 (OFDM 포함). */
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
                      boolean showPmlGrid,
                      int ofdmSubcarriers,
                      double bandwidthHz) {
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
        this.ofdmSubcarriers = Math.max(1, ofdmSubcarriers);
        this.bandwidthHz = Math.max(0.0, bandwidthHz);
    }

    public double frequencyGhz() {
        return frequencyHz / 1.0e9;
    }

    /** totalSteps만 변경한 복사본을 반환한다. */
    public FdtdConfig withTotalSteps(int newTotalSteps) {
        return new FdtdConfig(band, frequencyHz, dxMeters, newTotalSteps, pmlCells,
                rampTimeSeconds, sourceAmplitude, rmsCycles,
                referenceMode, customReference, wallPreset,
                showMaterialGrid, showPmlGrid,
                ofdmSubcarriers, bandwidthHz);
    }

    /** OFDM 파라미터만 변경한 복사본을 반환한다. */
    public FdtdConfig withOfdm(int subcarriers, double bwHz) {
        return new FdtdConfig(band, frequencyHz, dxMeters, totalSteps, pmlCells,
                rampTimeSeconds, sourceAmplitude, rmsCycles,
                referenceMode, customReference, wallPreset,
                showMaterialGrid, showPmlGrid,
                subcarriers, bwHz);
    }
}
