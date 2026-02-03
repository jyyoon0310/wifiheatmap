package app.controller;

import app.model.*;
import javafx.geometry.Point2D;
import javafx.scene.input.MouseButton;

import java.util.ArrayList;
import java.util.List;

/**
 * SCALE / WALL / AP 툴 입력을 한 군데로 모은 컨트롤러.
 * - MainController는 여기로 이벤트를 라우팅만 함.
 * - 렌더링은 MainController가 "현재 상태"를 받아서 CanvasView.render(...) 호출.
 */
public class ToolsController {

    private final WifiEnvironment env;
    private final AppState state;

    // SCALE 확정 선분(2점)
    private final List<Point2D> calibPts = new ArrayList<>();

    // WALL/SCALE 프리뷰(첫 점 + hover)
    private Point2D firstPoint = null;
    private Point2D hoverPoint = null;

    public ToolsController(WifiEnvironment env, AppState state) {
        this.env = env;
        this.state = state;
    }

    // ===== getters (MainController가 render에 넘김) =====
    public List<Point2D> getCalibPts() { return calibPts; }
    public Point2D getFirstPoint() { return firstPoint; }
    public Point2D getHoverPoint() { return hoverPoint; }

    // ===== tool change hook =====
    public void onToolChanged(AppState.Tool tool) {
        // 모드 바뀔 때 프리뷰 정리
        firstPoint = null;
        hoverPoint = null;

        // SCALE로 들어오면 이전 선분 제거(혼동 방지)
        if (tool == AppState.Tool.SCALE) {
            calibPts.clear();
        }
    }

    // ===== LeftPanel actions =====
    public void applyScaleIfReady(Runnable afterStateChanged) {
        if (calibPts.size() != 2) {
            // 여기서 Alert 띄우는 건 MainController 책임으로 두는 게 더 깔끔하지만,
            // 지금은 기존 구조 유지하려면 MainController에서 검사/메시지 처리해도 됨.
            return;
        }
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
    public void onMouseClicked(double x, double y,
                               MouseButton button,
                               Runnable requestRender,
                               Runnable requestReturnToViewAndClearToggle) {

        // 우클릭은 팬 전용(MainController에서 처리)
        if (button == MouseButton.SECONDARY) return;

        switch (state.getTool()) {
            case SCALE -> handleScaleClick(x, y, requestRender, requestReturnToViewAndClearToggle);
            case WALL  -> handleWallClick(x, y, requestRender);
            case AP    -> {
                addApAt(x, y);
                if (requestRender != null) requestRender.run();
            }
            default -> {
                // VIEW는 아무것도 안 함
            }
        }
    }

    public void onMouseMoved(double x, double y, Runnable requestRender) {
        // WALL/SCALE 프리뷰 업데이트
        if ((state.getTool() == AppState.Tool.WALL || state.getTool() == AppState.Tool.SCALE) && firstPoint != null) {
            hoverPoint = new Point2D(x, y);
            if (requestRender != null) requestRender.run();
        }
    }

    // ===== internals =====
    private void handleScaleClick(double x, double y,
                                  Runnable requestRender,
                                  Runnable requestReturnToViewAndClearToggle) {

        // 1클릭 시작
        if (firstPoint == null) {
            firstPoint = new Point2D(x, y);
            hoverPoint = firstPoint;
            calibPts.clear(); // 새로 시작하니 기존 선 제거
            if (requestRender != null) requestRender.run();
            return;
        }

        // 2클릭 확정
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

        // 완료 후 프리뷰 종료
        firstPoint = null;
        hoverPoint = null;

        // ✅ 완료하면 VIEW로 복귀 + 토글 해제
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

            // TODO: 나중에 LeftPanel 선택값(AppState)에 따라 material/db 반영
            w.setMaterial(WallMaterial.CONCRETE_WALL);

            env.getWalls().add(w);
        }

        firstPoint = null;
        hoverPoint = null;

        if (requestRender != null) requestRender.run();
    }

    private void addApAt(double x, double y) {
        AP ap = new AP();
        ap.name = "AP-" + (env.getAps().size() + 1);
        ap.x = x;
        ap.y = y;
        ap.enabled = true;

        // 밴드별 SSID 기본값(구조가 존재할 때만)
        try {
            ap.radios.get(Band.GHZ_24).ssid = ap.name + "_24G";
            ap.radios.get(Band.GHZ_5).ssid  = ap.name + "_5G";
            ap.radios.get(Band.GHZ_6).ssid  = ap.name + "_6G";
        } catch (Exception ignored) {}

        env.getAps().add(ap);
    }

    private double safeGetCalibRealMeters() {
        try {
            return state.getCalibRealMeters();
        } catch (Exception ignored) {
            return 5.0;
        }
    }
}