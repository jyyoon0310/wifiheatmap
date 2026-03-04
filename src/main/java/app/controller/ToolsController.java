package app.controller;

import app.model.*;
import javafx.geometry.Point2D;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
    private Wall selectedWall = null;
    private Wall hoverWall = null;

    public static final double WALL_HIT_R = 8.0;
    private static final double WALL_SNAP_ANGLE_DEG = 6.0;
    private static final double WALL_ENDPOINT_SNAP_R = 10.0;

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
    public void clearApInteraction() { apController.clearInteraction(); }
    public Wall getSelectedWall() { return selectedWall; }
    public void clearWallSelection() { selectedWall = null; }
    public Wall getHoverWall() { return hoverWall; }
    public void setSelectedWall(Wall wall, Runnable requestRender) {
        selectedWall = wall;
        hoverWall = wall;
        if (requestRender != null) requestRender.run();
    }

    /** MainController 더블클릭 편집용 */
    public AP findApNear(double x, double y) { return apController.findApNear(x, y); }
    public Wall findWallNear(double x, double y) {
        Wall best = null;
        double bestD = WALL_HIT_R;
        for (Wall w : env.getWalls()) {
            if (w == null) continue;
            double d = distancePointToSegment(x, y, w.x1, w.y1, w.x2, w.y2);
            if (d <= bestD) {
                bestD = d;
                best = w;
            }
        }
        return best;
    }

    public void selectWallNear(double x, double y, Runnable requestRender) {
        Wall hit = findWallNear(x, y);
        if (hit != selectedWall) {
            selectedWall = hit;
            hoverWall = hit;
            if (requestRender != null) requestRender.run();
        }
    }

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
                               Runnable requestReturnToViewAndClearToggle,
                               Consumer<Wall> onWallCreated) {

        if (button == MouseButton.SECONDARY) return;

        switch (state.getTool()) {
            case SCALE -> handleScaleClick(x, y, requestRender, requestReturnToViewAndClearToggle);
            case WALL  -> handleWallClick(x, y, requestRender, onWallCreated);
            case AP    -> apController.onMouseClicked(x, y, button, requestRender); // ✅ AP툴에서만 생성
            default -> {}
        }
    }

    public void onMouseMoved(double x, double y, Runnable requestRender) {
        // ✅ hover 링은 VIEW에서도 보여야 하니까 VIEW/AP에서 모두 갱신
        if (state.getTool() == AppState.Tool.AP || state.getTool() == AppState.Tool.VIEW) {
            apController.onMouseMoved(x, y, requestRender);
        }

        // 벽 hover는 VIEW/WALL에서 표시
        if (state.getTool() == AppState.Tool.VIEW || state.getTool() == AppState.Tool.WALL) {
            Wall hit = findWallNear(x, y);
            if (hit != hoverWall) {
                hoverWall = hit;
                if (requestRender != null) requestRender.run();
            }
        }

        // WALL/SCALE 프리뷰
        if ((state.getTool() == AppState.Tool.WALL || state.getTool() == AppState.Tool.SCALE) && firstPoint != null) {
            Point2D raw = new Point2D(x, y);
            if (state.getTool() == AppState.Tool.WALL) {
                Point2D snappedEnd = snapWallEndpoint(raw);
                hoverPoint = (snappedEnd != null) ? snappedEnd : snapWallPoint(firstPoint, raw);
            } else {
                hoverPoint = raw;
            }
            if (requestRender != null) requestRender.run();
        }
    }

    public boolean onKeyPressed(KeyCode code, Runnable requestRender) {
        if (state.getTool() == AppState.Tool.AP && apController.onKeyPressed(code, requestRender)) {
            return true;
        }

        if (code == KeyCode.DELETE || code == KeyCode.BACK_SPACE) {
            if (selectedWall != null) {
                env.getWalls().remove(selectedWall);
                selectedWall = null;
                hoverWall = null;
                if (requestRender != null) requestRender.run();
                return true;
            }
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

    private void handleWallClick(double x, double y, Runnable requestRender, Consumer<Wall> onWallCreated) {
        if (firstPoint == null) {
            Point2D snappedStart = snapWallEndpoint(new Point2D(x, y));
            firstPoint = (snappedStart != null) ? snappedStart : new Point2D(x, y);
            hoverPoint = firstPoint;
            if (requestRender != null) requestRender.run();
            return;
        }

        Point2D snappedEnd = snapWallEndpoint(new Point2D(x, y));
        Point2D end = (snappedEnd != null) ? snappedEnd : snapWallPoint(firstPoint, new Point2D(x, y));
        if (firstPoint.distance(end) >= 3.0) {
            Wall w = new Wall();
            w.x1 = firstPoint.getX();
            w.y1 = firstPoint.getY();
            w.x2 = end.getX();
            w.y2 = end.getY();
            w.setMaterial(WallMaterial.CONCRETE_WALL);
            env.getWalls().add(w);
            selectedWall = w;
            hoverWall = w;
            if (onWallCreated != null) onWallCreated.accept(w);
        }

        firstPoint = null;
        hoverPoint = null;

        if (requestRender != null) requestRender.run();
    }

    private Point2D snapWallEndpoint(Point2D raw) {
        return snapWallEndpoint(raw.getX(), raw.getY());
    }

    private Point2D snapWallEndpoint(double x, double y) {
        Point2D best = null;
        double bestD = WALL_ENDPOINT_SNAP_R;
        for (Wall w : env.getWalls()) {
            if (w == null) continue;
            double d1 = Math.hypot(x - w.x1, y - w.y1);
            if (d1 <= bestD) {
                bestD = d1;
                best = new Point2D(w.x1, w.y1);
            }
            double d2 = Math.hypot(x - w.x2, y - w.y2);
            if (d2 <= bestD) {
                bestD = d2;
                best = new Point2D(w.x2, w.y2);
            }
        }
        return best;
    }

    private static Point2D snapWallPoint(Point2D start, Point2D rawEnd) {
        double dx = rawEnd.getX() - start.getX();
        double dy = rawEnd.getY() - start.getY();
        if (Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return rawEnd;

        double angle = Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx))); // 0: horizontal, 90: vertical
        if (angle <= WALL_SNAP_ANGLE_DEG) {
            return new Point2D(rawEnd.getX(), start.getY()); // horizontal snap
        }
        if (angle >= 90.0 - WALL_SNAP_ANGLE_DEG) {
            return new Point2D(start.getX(), rawEnd.getY()); // vertical snap
        }
        return rawEnd;
    }

    private static double distancePointToSegment(double px, double py,
                                                 double x1, double y1,
                                                 double x2, double y2) {
        double vx = x2 - x1;
        double vy = y2 - y1;
        double len2 = vx * vx + vy * vy;
        if (len2 <= 1e-9) {
            double dx = px - x1;
            double dy = py - y1;
            return Math.hypot(dx, dy);
        }
        double t = ((px - x1) * vx + (py - y1) * vy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = x1 + t * vx;
        double cy = y1 + t * vy;
        return Math.hypot(px - cx, py - cy);
    }

    private double safeGetCalibRealMeters() {
        try { return state.getCalibRealMeters(); }
        catch (Exception ignored) { return 5.0; }
    }
}
