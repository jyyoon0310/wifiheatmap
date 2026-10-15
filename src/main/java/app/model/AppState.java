package app.model;

import javafx.beans.property.*;

public class AppState {

    public enum Tool { VIEW, SCALE, AP, WALL, SOLVER }

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

        @Override public String toString() { return label; }
    }

    // 현재 도구 모드
    private final ObjectProperty<Tool> tool = new SimpleObjectProperty<>(Tool.VIEW);

    // 스케일
    private final DoubleProperty scaleMPerPx = new SimpleDoubleProperty(Double.NaN);
    private final DoubleProperty calibRealMeters = new SimpleDoubleProperty(5.0);

    // Legacy 모델 경로손실 지수 (작업 파일에 저장·복원된다)
    private final DoubleProperty pathLossN = new SimpleDoubleProperty(3.5);
    /** 히트맵에 표시할 밴드 필터 (기본: 전체 MAX) */
    private final ObjectProperty<BandFilter> bandFilter =
            new SimpleObjectProperty<>(BandFilter.ALL_MAX);

    /**
     * 고급(연구) 모드 — 켜면 DPM 탐색 통계, 전파 흐름 보기의 HUD·세부 설정 같은
     * 전문가용 정보가 화면에 나온다. 기본 OFF.
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
    public Tool getTool() { return tool.get(); }
    public void setTool(Tool t) { tool.set(t); }

    public double getScaleMPerPx() { return scaleMPerPx.get(); }
    public void setScaleMPerPx(double v) { scaleMPerPx.set(v); }

    public double getCalibRealMeters() { return calibRealMeters.get(); }
    public void setCalibRealMeters(double v) { calibRealMeters.set(v); }

    public double getPathLossN() { return pathLossN.get(); }
    public void setPathLossN(double n) {
        pathLossN.set(Double.isFinite(n) ? n : 3.5);
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
