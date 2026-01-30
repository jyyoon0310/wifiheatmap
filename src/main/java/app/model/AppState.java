package app.model;

import javafx.beans.property.*;

public class AppState {

    public enum Tool { VIEW, SCALE, AP, WALL }

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
    private final DoubleProperty pathLossN = new SimpleDoubleProperty(2.5);
    private final IntegerProperty smoothRadiusPx = new SimpleIntegerProperty(8);

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

    public IntegerProperty smoothRadiusPxProperty() { return smoothRadiusPx; }
    public int getSmoothRadiusPx() { return smoothRadiusPx.get(); }
}