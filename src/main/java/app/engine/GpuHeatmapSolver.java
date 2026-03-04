package app.engine;

import app.model.WifiEnvironment;
import javafx.scene.image.WritableImage;

/**
 * Optional GPU heatmap backend SPI.
 * - 기본 프로젝트에는 구현이 없어 CPU fallback으로 동작한다.
 * - 외부 모듈에서 ServiceLoader로 구현을 제공하면 자동 사용된다.
 */
public interface GpuHeatmapSolver {
    boolean isAvailable();

    WritableImage generate(WifiEnvironment env,
                           int width,
                           int height,
                           int gridStepPx,
                           double legendMinDbm,
                           double legendMaxDbm,
                           int smoothRadiusPx);
}
