package app.controller;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;

public class ViewportController {

    private final DoubleProperty zoomScale = new SimpleDoubleProperty(1.0);

    private final ScrollPane canvasSP;
    private final StackPane viewportPane;
    private final Group zoomGroup;   // scale/translate 적용
    private final Group floorGroup;  // sceneToLocal용

    private double baseContentW = 900;
    private double baseContentH = 650;

    // 여백 팬(translate) - zoomGroup에 적용
    private final DoubleProperty panTx = new SimpleDoubleProperty(0.0);
    private final DoubleProperty panTy = new SimpleDoubleProperty(0.0);

    public ViewportController(ScrollPane canvasSP, StackPane viewportPane, Group zoomGroup, Group floorGroup) {
        this.canvasSP = canvasSP;
        this.viewportPane = viewportPane;
        this.zoomGroup = zoomGroup;
        this.floorGroup = floorGroup;

        // ✅ 줌은 zoomGroup에만 적용
        zoomGroup.scaleXProperty().bind(zoomScale);
        zoomGroup.scaleYProperty().bind(zoomScale);

        // ✅ 여백 팬 translate도 zoomGroup에 적용
        zoomGroup.translateXProperty().bind(panTx);
        zoomGroup.translateYProperty().bind(panTy);

        zoomScale.addListener((o, ov, nv) -> {
            updateViewportSize();
            clampPanToBounds();
        });
    }

    public DoubleProperty zoomScaleProperty() { return zoomScale; }
    public double getZoom() { return zoomScale.get(); }
    public void setZoom(double z) { zoomScale.set(z); }

    public void setBaseContentSize(double w, double h) {
        baseContentW = w;
        baseContentH = h;
        updateViewportSize();
        clampPanToBounds();
    }

    public void updateViewportSize() {
        Bounds vp = canvasSP.getViewportBounds();
        double vpW = (vp != null) ? vp.getWidth() : 0;
        double vpH = (vp != null) ? vp.getHeight() : 0;

        double scaledW = baseContentW * zoomScale.get();
        double scaledH = baseContentH * zoomScale.get();

        // viewport보다 작으면 viewport만큼 키워서 중앙정렬/여백 유지
        double w = Math.max(scaledW, vpW);
        double h = Math.max(scaledH, vpH);

        viewportPane.setMinSize(w, h);
        viewportPane.setPrefSize(w, h);

        clampPanToBounds();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    /**
     * ✅ 여백 팬 제한:
     * "화면에 보이는 크기"는 base * zoomScale 이므로 그걸로 계산해야 맞다.
     */
    private void clampPanToBounds() {
        Bounds vp = canvasSP.getViewportBounds();
        if (vp == null) return;

        double scaledW = baseContentW * zoomScale.get();
        double scaledH = baseContentH * zoomScale.get();

        double extraX = Math.max(0.0, vp.getWidth() - scaledW);
        double extraY = Math.max(0.0, vp.getHeight() - scaledH);

        double maxTx = extraX / 2.0;
        double maxTy = extraY / 2.0;

        if (extraX > 0) panTx.set(clamp(panTx.get(), -maxTx, maxTx));
        else panTx.set(0);

        if (extraY > 0) panTy.set(clamp(panTy.get(), -maxTy, maxTy));
        else panTy.set(0);
    }

    /**
     * ✅ 팬:
     * - scaledW/H(= base * zoom)로 스크롤 가능 여부 판단
     * - 스크롤 불가능(여백)일 때만 translate로 이동
     */
    public void panBy(double startH, double startV,
                      double startPanTx, double startPanTy,
                      double startSceneX, double startSceneY,
                      double nowSceneX, double nowSceneY) {

        Bounds vp = canvasSP.getViewportBounds();
        if (vp == null) return;

        double scaledW = baseContentW * zoomScale.get();
        double scaledH = baseContentH * zoomScale.get();

        double denomX = scaledW - vp.getWidth();
        double denomY = scaledH - vp.getHeight();

        double dx = nowSceneX - startSceneX;
        double dy = nowSceneY - startSceneY;

        if (denomX > 0) {
            canvasSP.setHvalue(clamp01(startH - dx / denomX));
            panTx.set(0);
        } else {
            panTx.set(startPanTx + dx);
        }

        if (denomY > 0) {
            canvasSP.setVvalue(clamp01(startV - dy / denomY));
            panTy.set(0);
        } else {
            panTy.set(startPanTy + dy);
        }

        clampPanToBounds();
    }

    public void resetPan() {
        panTx.set(0);
        panTy.set(0);
    }

    public double getPanTx() { return panTx.get(); }
    public double getPanTy() { return panTy.get(); }

    public void centerViewport() {
        Platform.runLater(() -> {
            Bounds vp = canvasSP.getViewportBounds();
            if (vp == null) return;

            double scaledW = baseContentW * zoomScale.get();
            double scaledH = baseContentH * zoomScale.get();

            double denomX = scaledW - vp.getWidth();
            double denomY = scaledH - vp.getHeight();

            canvasSP.setHvalue(denomX > 0 ? 0.5 : 0.0);
            canvasSP.setVvalue(denomY > 0 ? 0.5 : 0.0);

            resetPan();
            clampPanToBounds();
        });
    }

    public void zoomAt(double factor, double sceneX, double sceneY) {
        double oldScale = zoomScale.get();
        double newScale = Math.max(0.25, Math.min(5.0, oldScale * factor));
        double scaleFactor = newScale / oldScale;
        if (Math.abs(scaleFactor - 1.0) < 1e-6) return;

        Bounds vp = canvasSP.getViewportBounds();
        if (vp == null) return;

        // 커서가 가리키는 지점(원본 좌표계)
        Point2D mouseInFloor = floorGroup.sceneToLocal(sceneX, sceneY);

        // oldScale 기준으로 "스케일된 위치"
        double mouseScaledX = mouseInFloor.getX() * oldScale;
        double mouseScaledY = mouseInFloor.getY() * oldScale;

        double contentW = baseContentW * oldScale;
        double contentH = baseContentH * oldScale;

        double denomX = Math.max(0, contentW - vp.getWidth());
        double denomY = Math.max(0, contentH - vp.getHeight());
        double offsetX = canvasSP.getHvalue() * denomX;
        double offsetY = canvasSP.getVvalue() * denomY;

        double newOffsetX = offsetX + mouseScaledX * (scaleFactor - 1.0);
        double newOffsetY = offsetY + mouseScaledY * (scaleFactor - 1.0);

        zoomScale.set(newScale);

        Platform.runLater(() -> {
            Bounds vp2 = canvasSP.getViewportBounds();
            if (vp2 == null) return;

            double contentW2 = baseContentW * newScale;
            double contentH2 = baseContentH * newScale;

            double denomX2 = contentW2 - vp2.getWidth();
            double denomY2 = contentH2 - vp2.getHeight();

            canvasSP.setHvalue(denomX2 > 0 ? clamp01(newOffsetX / denomX2) : 0.0);
            canvasSP.setVvalue(denomY2 > 0 ? clamp01(newOffsetY / denomY2) : 0.0);

            clampPanToBounds();
        });
    }

    // ✅ Fit(맞춤): viewport에 컨텐츠가 들어오도록 줌 자동 설정
    public void fitToViewport(int paddingPx, double contentW, double contentH) {
        Platform.runLater(() -> {
            Bounds vp = canvasSP.getViewportBounds();
            if (vp == null || vp.getWidth() <= 0 || vp.getHeight() <= 0) return;

            double availW = Math.max(50, vp.getWidth() - paddingPx * 2.0);
            double availH = Math.max(50, vp.getHeight() - paddingPx * 2.0);

            double s = Math.min(availW / contentW, availH / contentH);
            s = Math.max(0.25, Math.min(5.0, s));

            zoomScale.set(s);
            updateViewportSize();
            centerViewport();
        });
    }
}