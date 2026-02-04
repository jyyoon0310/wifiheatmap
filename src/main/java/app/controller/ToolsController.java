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

    // WALL/SCALE 프리뷰
    private Point2D firstPoint = null;
    private Point2D hoverPoint = null;

    // AP (캔버스 인터랙션)
    private final ApController apController;

    public ToolsController(WifiEnvironment env, AppState state) {
        this.env = env;
        this.state = state;
        this.apController = new ApController(env);
    }

    public List<Point2D> getCalibPts() { return calibPts; }
    public Point2D getFirstPoint() { return firstPoint; }
    public Point2D getHoverPoint() { return hoverPoint; }

    public AP getHoverAp() { return apController.getHoverAp(); }
    public AP getSelectedAp() { return apController.getSelectedAp(); }
    public void clearApSelection() { apController.clearSelection(); }

    /** MainController 더블클릭 편집용 */
    public AP findApNear(double x, double y) { return apController.findApNear(x, y); }

    public void onToolChanged(AppState.Tool tool) {
        firstPoint = null;
        hoverPoint = null;

        // AP 인터랙션 정리
        apController.clearInteraction();

        if (tool == AppState.Tool.SCALE) {
            calibPts.clear();
        }
    }

    /** ✅ 적용 버튼 눌렀을 때만 적용되게: 성공/실패 반환 */
    public boolean applyScaleIfReady(Runnable afterStateChanged) {
        if (calibPts.size() != 2) return false;

        double dPx = calibPts.get(0).distance(calibPts.get(1));
        double realM = safeGetCalibRealMeters();
        if (dPx <= 0 || realM <= 0) return false;

        state.setScaleMPerPx(realM / dPx);
        if (afterStateChanged != null) afterStateChanged.run();
        return true;
    }

    public void resetScale(Runnable afterStateChanged) {
        calibPts.clear();
        state.setScaleMPerPx(Double.NaN);
        firstPoint = null;
        hoverPoint = null;

        if (afterStateChanged != null) afterStateChanged.run();
    }

    // ===== AP drag route (AP툴에서만) =====
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
            case AP    -> apController.onMouseClicked(x, y, button, requestRender); // ✅ AP툴에서만 생성
            default -> {}
        }
    }

    public void onMouseMoved(double x, double y, Runnable requestRender) {
        // ✅ hover 링은 VIEW에서도 보여야 하니까 VIEW/AP에서 모두 갱신
        if (state.getTool() == AppState.Tool.AP || state.getTool() == AppState.Tool.VIEW) {
            apController.onMouseMoved(x, y, requestRender);
        }

        // WALL/SCALE 프리뷰
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
        }

        firstPoint = null;
        hoverPoint = null;

        // 스케일은 “적용 버튼”에서만 state.setScaleMPerPx 하기로 했으니 여기선 안 함

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
        try { return state.getCalibRealMeters(); }
        catch (Exception ignored) { return 5.0; }
    }
}