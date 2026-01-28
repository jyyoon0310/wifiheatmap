package app.controller;

import javafx.beans.property.DoubleProperty;
import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * 스케일(거리) 보정 전용 컨트롤러.
 *
 * 사용 흐름
 * 1) SCALE 모드에서 첫 점 클릭 -> 시작점 지정(미리보기 시작)
 * 2) 마우스 이동 -> 시작점~현재 마우스까지 점선 미리보기
 * 3) 두 번째 클릭 -> 끝점 확정(선분 확정)
 * 4) 좌측 패널 "적용" 버튼 -> realMeters / pixelDistance 로 scaleMPerPx 갱신
 *
 * Main.java는 이 컨트롤러를 통해
 * - 두 점 저장/초기화
 * - 미리보기 갱신
 * - scaleMPerPx 계산/적용
 * - draw(GraphicsContext)로 화면 표시
 * 를 처리한다.
 */
public class ScaleController {

    // 사용자가 입력하는 "두 점 실제거리(m)"
    private final DoubleProperty realMeters;

    // 결과로 세팅될 m/px (환경 파라미터)
    private final DoubleProperty scaleMPerPx;

    // 상태: start는 1번째 클릭, end는 2번째 클릭, hover는 미리보기용 현재 마우스 위치
    private Point2D start;
    private Point2D end;
    private Point2D hover;

    public ScaleController(DoubleProperty realMeters, DoubleProperty scaleMPerPx) {
        this.realMeters = realMeters;
        this.scaleMPerPx = scaleMPerPx;
    }

    /** 첫 점을 찍은 상태인지(= 미리보기 드로잉 중인지) */
    public boolean isDrawing() {
        return start != null && end == null;
    }

    /** 두 점이 모두 확정되어 적용 가능한 상태인지 */
    public boolean canApply() {
        return start != null && end != null;
    }

    /** 클릭 입력: WALL처럼 두 점으로 선분을 만든다. */
    public void onClick(double x, double y) {
        Point2D p = new Point2D(x, y);

        // 아직 시작점이 없거나, 이미 두 점이 완료된 상태면: 새로 시작
        if (start == null || end != null) {
            start = p;
            end = null;
            hover = p; // 첫 클릭 직후에도 미리보기 선이 보이게
            return;
        }

        // 시작점만 있는 상태(= 두 번째 클릭) -> 끝점 확정
        end = p;
        hover = null;
    }

    /** 미리보기 갱신: 첫 점 찍은 상태에서만 의미 있음 */
    public void onMove(double x, double y) {
        if (!isDrawing()) return;
        hover = new Point2D(x, y);
    }

    /**
     * 확정된 두 점을 기준으로 scaleMPerPx를 계산해 반영.
     * @return 성공하면 true
     */
    public boolean applyFromCurrentPoints() {
        if (!canApply()) return false;

        double rm = realMeters.get();
        if (!(rm > 0)) return false;

        double dPx = start.distance(end);
        if (!(dPx > 0)) return false;

        scaleMPerPx.set(rm / dPx);
        return true;
    }

    /** 모든 스케일 상태(확정/미리보기 포함) 초기화 */
    public void clear() {
        start = null;
        end = null;
        hover = null;
    }

    /**
     * “진행 중인 미리보기”만 취소.
     * - 이미 두 점 확정(end != null)은 유지
     * - 첫 점만 찍고 끝점 없는 상태를 취소하는 용도
     */
    public void clearInteractive() {
        if (isDrawing()) {
            start = null;
            hover = null;
        }
    }

    /**
     * 스케일 선/점 렌더링.
     * @param g GraphicsContext
     * @param activeScaleMode 현재 Tool이 SCALE일 때 true (SCALE일 때만 미리보기 점선을 보여주려고)
     */
    public void draw(GraphicsContext g, boolean activeScaleMode) {
        // 확정 선(두 점)
        if (start != null && end != null) {
            g.setStroke(Color.PURPLE);
            g.setLineWidth(2.0);
            g.strokeLine(start.getX(), start.getY(), end.getX(), end.getY());
        }

        // 미리보기 선(첫 점 + hover)
        if (activeScaleMode && isDrawing() && hover != null) {
            g.setLineDashes(8, 6);
            g.setStroke(Color.DARKMAGENTA);
            g.setLineWidth(2.0);
            g.strokeLine(start.getX(), start.getY(), hover.getX(), hover.getY());
            g.setLineDashes(null);
        }

        // 점 표시(시작/끝)
        g.setFill(Color.PURPLE);
        if (start != null) g.fillOval(start.getX() - 3, start.getY() - 3, 6, 6);
        if (end != null) g.fillOval(end.getX() - 3, end.getY() - 3, 6, 6);
    }
}