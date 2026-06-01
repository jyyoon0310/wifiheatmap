package app.model;

import javafx.beans.property.*;

public class AppState {

    public enum Tool { VIEW, SCALE, AP, WALL, SOLVER }
    public enum HeatmapSolverMode { CPU, GPU }
    public enum HeatmapModel { LEGACY, DPM, FDTD_TEZ }

    /**
     * 히트맵에 표시할 밴드 선택.
     * ALL_MAX : 모든 활성 밴드의 RSSI 중 최댓값 (기존 동작)
     * GHZ_24/5/6 : 해당 밴드 라디오만 사용해서 RSSI 계산 → 밴드별 시각 비교용
     */
    public enum BandFilter {
        ALL_MAX("전체 MAX", null),
        GHZ_24 ("2.4 GHz",  Band.GHZ_24),
        GHZ_5  ("5 GHz",    Band.GHZ_5),
        GHZ_6  ("6 GHz",    Band.GHZ_6);

        public final String label;
        public final Band band;  // null = ALL_MAX

        BandFilter(String label, Band band) { this.label = label; this.band = band; }

        public boolean matches(Band b) { return band == null || band == b; }

        @Override public String toString() { return label; }
    }

    // 현재 도구 모드
    private final ObjectProperty<Tool> tool = new SimpleObjectProperty<>(Tool.VIEW);

    // 스케일
    private final DoubleProperty scaleMPerPx = new SimpleDoubleProperty(Double.NaN);
    private final DoubleProperty calibRealMeters = new SimpleDoubleProperty(5.0);

    // 렌더/레전드 범위
    private final DoubleProperty legendMin = new SimpleDoubleProperty(-96);
    private final DoubleProperty legendMax = new SimpleDoubleProperty(-10);

    // 기본 벽 재질/감쇠
    private final ObjectProperty<app.model.WallMaterial> defaultWallMaterial =
            new SimpleObjectProperty<>(app.model.WallMaterial.CONCRETE_WALL);
    private final DoubleProperty defaultWallDb =
            new SimpleDoubleProperty(app.model.WallMaterial.CONCRETE_WALL.defaultAttenuationDb());

    // 모델 고정 파라미터(네가 고정한다고 했던 값)
    private final DoubleProperty pathLossN = new SimpleDoubleProperty(3.5);
    private final IntegerProperty smoothRadiusPx = new SimpleIntegerProperty(8);
    private final ObjectProperty<HeatmapSolverMode> heatmapSolverMode =
            new SimpleObjectProperty<>(HeatmapSolverMode.CPU);
    private final ObjectProperty<HeatmapModel> heatmapModel =
            new SimpleObjectProperty<>(HeatmapModel.DPM);
    /** 히트맵에 표시할 밴드 필터 (기본: 전체 MAX) */
    private final ObjectProperty<BandFilter> bandFilter =
            new SimpleObjectProperty<>(BandFilter.ALL_MAX);

    /**
     * 고급(연구) 모드 — 활성화 시 Legacy/FDTD_TEZ 히트맵 콤보, CPU/GPU 솔버 모드 콤보,
     * solver start/stop/reset 버튼 등 전문가용 컨트롤이 다시 노출된다. 기본 OFF.
     */
    private final BooleanProperty advancedMode = new SimpleBooleanProperty(false);

    /**
     * 사용자가 지정한 "와이파이 사용 공간" 마스크 (AP 추천의 flood fill 결과).
     * AP 추천과 측정 결과가 공유하는 단일 소스. null = 미선택(영역 지정 안 됨).
     * 좌표계: 캔버스 px → 셀 인덱스 = px / cellSize.
     */
    private boolean[] coverageMask;
    private int coverageMaskW, coverageMaskH, coverageCellSize;

    // ===== getters / properties =====
    public ObjectProperty<Tool> toolProperty() { return tool; }
    public Tool getTool() { return tool.get(); }
    public void setTool(Tool t) { tool.set(t); }

    public DoubleProperty scaleMPerPxProperty() { return scaleMPerPx; }
    public double getScaleMPerPx() { return scaleMPerPx.get(); }
    public void setScaleMPerPx(double v) { scaleMPerPx.set(v); }

    public DoubleProperty calibRealMetersProperty() { return calibRealMeters; }
    public double getCalibRealMeters() { return calibRealMeters.get(); }
    public void setCalibRealMeters(double v) { calibRealMeters.set(v); }

    public DoubleProperty legendMinProperty() { return legendMin; }
    public DoubleProperty legendMaxProperty() { return legendMax; }

    public ObjectProperty<app.model.WallMaterial> defaultWallMaterialProperty() { return defaultWallMaterial; }
    public app.model.WallMaterial getDefaultWallMaterial() { return defaultWallMaterial.get(); }
    public void setDefaultWallMaterial(app.model.WallMaterial m) { defaultWallMaterial.set(m); }

    public DoubleProperty defaultWallDbProperty() { return defaultWallDb; }
    public double getDefaultWallDb() { return defaultWallDb.get(); }
    public void setDefaultWallDb(double v) { defaultWallDb.set(v); }

    public DoubleProperty pathLossNProperty() { return pathLossN; }
    public double getPathLossN() { return pathLossN.get(); }
    public void setPathLossN(double n) {
        pathLossN.set(Double.isFinite(n) ? n : 3.5);
    }

    public IntegerProperty smoothRadiusPxProperty() { return smoothRadiusPx; }
    public int getSmoothRadiusPx() { return smoothRadiusPx.get(); }

    public ObjectProperty<HeatmapSolverMode> heatmapSolverModeProperty() { return heatmapSolverMode; }
    public HeatmapSolverMode getHeatmapSolverMode() { return heatmapSolverMode.get(); }
    public void setHeatmapSolverMode(HeatmapSolverMode mode) {
        heatmapSolverMode.set(mode == null ? HeatmapSolverMode.CPU : mode);
    }

    public ObjectProperty<HeatmapModel> heatmapModelProperty() { return heatmapModel; }
    public HeatmapModel getHeatmapModel() { return heatmapModel.get(); }
    public void setHeatmapModel(HeatmapModel model) {
        heatmapModel.set(model == null ? HeatmapModel.DPM : model);
    }

    public BooleanProperty advancedModeProperty() { return advancedMode; }
    public boolean isAdvancedMode() { return advancedMode.get(); }
    public void setAdvancedMode(boolean v) { advancedMode.set(v); }

    public ObjectProperty<BandFilter> bandFilterProperty() { return bandFilter; }
    public BandFilter getBandFilter() { return bandFilter.get(); }
    public void setBandFilter(BandFilter f) {
        bandFilter.set(f == null ? BandFilter.ALL_MAX : f);
    }

    // ===== 사용 공간 마스크 =====
    public void setCoverageMask(boolean[] mask, int maskW, int maskH, int cellSize) {
        this.coverageMask = mask;
        this.coverageMaskW = maskW;
        this.coverageMaskH = maskH;
        this.coverageCellSize = Math.max(1, cellSize);
    }

    public void clearCoverageMask() {
        this.coverageMask = null;
        this.coverageMaskW = this.coverageMaskH = this.coverageCellSize = 0;
    }

    /** 사용 공간이 지정되어 있는가. false면 측정 결과를 숨기고 안내해야 한다. */
    public boolean hasCoverageArea() { return coverageMask != null; }

    /** 캔버스 px 좌표가 사용 공간 안인지. 마스크 미설정이면 true(제한 없음). */
    public boolean coverageContains(double px, double py) {
        if (coverageMask == null) return true;
        int mx = (int) (px / coverageCellSize);
        int my = (int) (py / coverageCellSize);
        if (mx < 0 || my < 0 || mx >= coverageMaskW || my >= coverageMaskH) return false;
        return coverageMask[my * coverageMaskW + mx];
    }
}
