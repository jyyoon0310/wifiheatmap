package app;

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

    // 실제로 scale이 적용될 그룹(= zoomGroup)
    private final Group zoomGroup;

    // 커서 기준 줌 계산에 쓰는 원본 좌표 그룹(= floorGroup)
    private final Group floorGroup;

    private double baseContentW = 900;
    private double baseContentH = 650;

    public ViewportController(ScrollPane canvasSP, StackPane viewportPane, Group zoomGroup, Group floorGroup) {
        this.canvasSP = canvasSP;
        this.viewportPane = viewportPane;
        this.zoomGroup = zoomGroup;
        this.floorGroup = floorGroup;

        // ✅ 줌이 실제로 보이도록 scale 바인딩 (이게 핵심)
        this.zoomGroup.scaleXProperty().bind(zoomScale);
        this.zoomGroup.scaleYProperty().bind(zoomScale);

        zoomScale.addListener((o, ov, nv) -> updateViewportSize());
    }

    public DoubleProperty zoomScaleProperty() {
        return zoomScale;
    }

    public void setZoom(double v) {
        zoomScale.set(v);
        updateViewportSize();
    }

    public void setBaseContentSize(double w, double h) {
        baseContentW = w;
        baseContentH = h;
        updateViewportSize();
    }

    public void updateViewportSize() {
        double scaledW = baseContentW * zoomScale.get();
        double scaledH = baseContentH * zoomScale.get();

        double vpW = 0, vpH = 0;
        if (canvasSP.getViewportBounds() != null) {
            vpW = canvasSP.getViewportBounds().getWidth();
            vpH = canvasSP.getViewportBounds().getHeight();
        }

        // viewport보다 작으면 content를 viewport만큼 키워서 StackPane 중앙정렬이 먹게 함
        double w = Math.max(scaledW, vpW);
        double h = Math.max(scaledH, vpH);

        viewportPane.setMinSize(w, h);
        viewportPane.setPrefSize(w, h);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public void centerViewport() {
        Platform.runLater(() -> {
            Bounds vp = canvasSP.getViewportBounds();
            if (vp == null) return;

            double contentW = viewportPane.getPrefWidth();
            double contentH = viewportPane.getPrefHeight();
            double denomX = contentW - vp.getWidth();
            double denomY = contentH - vp.getHeight();

            canvasSP.setHvalue(denomX > 0 ? 0.5 : 0.0);
            canvasSP.setVvalue(denomY > 0 ? 0.5 : 0.0);
        });
    }

    public void zoomAt(double factor, double sceneX, double sceneY) {
        double oldScale = zoomScale.get();
        double newScale = Math.max(0.25, Math.min(5.0, oldScale * factor));
        double scaleFactor = newScale / oldScale;
        if (Math.abs(scaleFactor - 1.0) < 1e-6) return;

        Bounds vp = canvasSP.getViewportBounds();
        if (vp == null) return;

        // 커서가 가리키는 지점(원본 좌표계: 이미지/캔버스 좌표)
        Point2D mouseInFloor = floorGroup.sceneToLocal(sceneX, sceneY);
        double mouseScaledX = mouseInFloor.getX() * oldScale;
        double mouseScaledY = mouseInFloor.getY() * oldScale;

        // 현재 content 크기(스케일 반영)
        double contentW = baseContentW * oldScale;
        double contentH = baseContentH * oldScale;

        // 현재 스크롤 오프셋(px)
        double denomX = Math.max(0, contentW - vp.getWidth());
        double denomY = Math.max(0, contentH - vp.getHeight());
        double offsetX = canvasSP.getHvalue() * denomX;
        double offsetY = canvasSP.getVvalue() * denomY;

        // 줌 후에도 커서가 가리키는 점이 같은 화면 위치에 있도록 오프셋 보정
        double newOffsetX = offsetX + mouseScaledX * (scaleFactor - 1.0);
        double newOffsetY = offsetY + mouseScaledY * (scaleFactor - 1.0);

        zoomScale.set(newScale);
        updateViewportSize();

        Platform.runLater(() -> {
            Bounds vp2 = canvasSP.getViewportBounds();
            if (vp2 == null) return;

            double contentW2 = baseContentW * newScale;
            double contentH2 = baseContentH * newScale;

            double denomX2 = contentW2 - vp2.getWidth();
            double denomY2 = contentH2 - vp2.getHeight();

            canvasSP.setHvalue(denomX2 > 0 ? clamp01(newOffsetX / denomX2) : 0.0);
            canvasSP.setVvalue(denomY2 > 0 ? clamp01(newOffsetY / denomY2) : 0.0);
        });
    }

    public void fitToViewport(int paddingPx, double contentW, double contentH) {
        Platform.runLater(() -> {
            Bounds vp = canvasSP.getViewportBounds();
            if (vp == null || vp.getWidth() <= 0 || vp.getHeight() <= 0) return;
            if (contentW <= 0 || contentH <= 0) return;

            double availW = Math.max(50, vp.getWidth() - paddingPx * 2.0);
            double availH = Math.max(50, vp.getHeight() - paddingPx * 2.0);

            double s = Math.min(availW / contentW, availH / contentH);
            s = Math.max(0.25, Math.min(5.0, s));

            zoomScale.set(s);
            updateViewportSize();
            Platform.runLater(this::centerViewport);
        });
    }
}