package app.controller;

import app.model.*;
import javafx.geometry.Point2D;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;

import java.util.ArrayList;
import java.util.List;

public class ToolsController {

    private final WifiEnvironment env;
    private final AppState state;

    // SCALE 확정 선분(2점)
    private final List<Point2D> calibPts = new ArrayList<>();

    // WALL/SCALE 프리뷰(첫 점 + hover)
    private Point2D firstPoint = null;
    private Point2D hoverPoint = null;

    // ✅ AP는 별도 컨트롤러로 분리
    private final ApController apController;

    public ToolsController(WifiEnvironment env, AppState state) {
        this.env = env;
        this.state = state;
        this.apController = new ApController(env);
    }

    // ===== getters (MainController가 render에 넘김) =====
    public List<Point2D> getCalibPts() { return calibPts; }
    public Point2D getFirstPoint() { return firstPoint; }
    public Point2D getHoverPoint() { return hoverPoint; }

    public AP getHoverAp() { return apController.getHoverAp(); }
    public AP getSelectedAp() { return apController.getSelectedAp(); }
    public boolean isApDragging() { return apController.isDragging(); }

    // ===== tool change hook =====
    public void onToolChanged(AppState.Tool tool) {
        // 모드 바뀔 때 프리뷰 정리
        firstPoint = null;
        hoverPoint = null;

        // AP 쪽도 인터랙션 정리
        apController.clearInteraction();

        if (tool == AppState.Tool.SCALE) {
            calibPts.clear();
        }
    }

    // ===== LeftPanel actions =====
    public void applyScaleIfReady(Runnable afterStateChanged) {
        if (calibPts.size() != 2) return;

        double dPx = calibPts.get(0).distance(calibPts.get(1));
        double realM = safeGetCalibRealMeters();
        if (dPx <= 0 || realM <= 0) return;

        state.setScaleMPerPx(realM / dPx);
        if (afterStateChanged != null) afterStateChanged.run();
    }

    public void resetScale(Runnable afterStateChanged) {
        calibPts.clear();
        state.setScaleMPerPx(Double.NaN);
        firstPoint = null;
        hoverPoint = null;

        if (afterStateChanged != null) afterStateChanged.run();
    }

    // ===== mouse input =====
    public void onMousePressed(double x, double y, MouseButton button, Runnable requestRender) {
        if (state.getTool() == AppState.Tool.AP) {
            apController.onMousePressed(x, y, button, requestRender);
        }
    }

    public void onMouseDragged(double x, double y, Runnable requestRender) {
        if (state.getTool() == AppState.Tool.AP) {
            apController.onMouseDragged(x, y, requestRender);
        }
    }

    public void onMouseReleased(Runnable requestRender) {
        if (state.getTool() == AppState.Tool.AP) {
            apController.onMouseReleased(requestRender);
        }
    }

    public void onMouseClicked(double x, double y,
                               MouseButton button,
                               Runnable requestRender,
                               Runnable requestReturnToViewAndClearToggle) {

        if (button == MouseButton.SECONDARY) return;

        switch (state.getTool()) {
            case SCALE -> handleScaleClick(x, y, requestRender, requestReturnToViewAndClearToggle);
            case WALL  -> handleWallClick(x, y, requestRender);
            case AP    -> apController.onMouseClicked(x, y, button, requestRender);
            default -> {}
        }
    }

    public void onMouseMoved(double x, double y, Runnable requestRender) {
        if (state.getTool() == AppState.Tool.AP) {
            apController.onMouseMoved(x, y, requestRender);
            return;
        }

        if ((state.getTool() == AppState.Tool.WALL || state.getTool() == AppState.Tool.SCALE) && firstPoint != null) {
            hoverPoint = new Point2D(x, y);
            if (requestRender != null) requestRender.run();
        }
    }

    public boolean onKeyPressed(KeyCode code, Runnable requestRender) {
        if (state.getTool() == AppState.Tool.AP) {
            return apController.onKeyPressed(code, requestRender);
        }
        return false;
    }

    public void clearApSelection() {
        apController.clearSelection();
    }

    // ===== internals =====
    private void handleScaleClick(double x, double y,
                                  Runnable requestRender,
                                  Runnable requestReturnToViewAndClearToggle) {

        if (firstPoint == null) {
            firstPoint = new Point2D(x, y);
            hoverPoint = firstPoint;
            calibPts.clear();
            if (requestRender != null) requestRender.run();
            return;
        }

        Point2D end = new Point2D(x, y);
        if (firstPoint.distance(end) >= 3.0) {
            calibPts.clear();
            calibPts.add(firstPoint);
            calibPts.add(end);

            double dPx = firstPoint.distance(end);
            double realM = safeGetCalibRealMeters();
            if (dPx > 0 && realM > 0) {
                state.setScaleMPerPx(realM / dPx);
            }
        }

        firstPoint = null;
        hoverPoint = null;

        if (requestReturnToViewAndClearToggle != null) requestReturnToViewAndClearToggle.run();
        if (requestRender != null) requestRender.run();
    }

    private void handleWallClick(double x, double y, Runnable requestRender) {
        if (firstPoint == null) {
            firstPoint = new Point2D(x, y);
            hoverPoint = firstPoint;
            if (requestRender != null) requestRender.run();
            return;
        }

        Point2D end = new Point2D(x, y);
        if (firstPoint.distance(end) >= 3.0) {
            Wall w = new Wall();
            w.x1 = firstPoint.getX();
            w.y1 = firstPoint.getY();
            w.x2 = end.getX();
            w.y2 = end.getY();
            w.setMaterial(WallMaterial.CONCRETE_WALL);
            env.getWalls().add(w);
        }

        firstPoint = null;
        hoverPoint = null;

        if (requestRender != null) requestRender.run();
    }

    private double safeGetCalibRealMeters() {
        try {
            return state.getCalibRealMeters();
        } catch (Exception ignored) {
            return 5.0;
        }
    }
}