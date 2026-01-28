package app.controller;

import app.model.Wall;
import app.model.WallMaterial;
import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.collections.ObservableList;

public class WallDrawController {

    private Point2D firstPoint = null;
    private Point2D hoverPoint = null;

    public boolean isDrawing() {
        return firstPoint != null;
    }

    public void clearInteractive() {
        firstPoint = null;
        hoverPoint = null;
    }

    public void onMove(double x, double y) {
        if (firstPoint == null) return;
        hoverPoint = new Point2D(x, y);
    }

    /** @return 벽이 실제로 생성되면 true */
    public boolean onClick(double x, double y,
                           ObservableList<Wall> walls,
                           WallMaterial chosenMaterial,
                           double customDb) {

        Point2D p = new Point2D(x, y);

        // 첫 클릭
        if (firstPoint == null) {
            firstPoint = p;
            hoverPoint = p;
            return false;
        }

        // 두번째 클릭 -> 생성
        Point2D end = p;
        boolean created = false;

        if (firstPoint.distance(end) >= 3.0) {
            Wall w = new Wall();
            w.x1 = firstPoint.getX();
            w.y1 = firstPoint.getY();
            w.x2 = end.getX();
            w.y2 = end.getY();

            if (chosenMaterial == null) chosenMaterial = WallMaterial.CONCRETE_WALL;
            w.setMaterial(chosenMaterial);

            if (chosenMaterial == WallMaterial.CUSTOM) {
                w.setAttenuationDb(customDb);
            }

            walls.add(w);
            created = true;
        }

        clearInteractive();
        return created;
    }

    public void draw(GraphicsContext g, ObservableList<Wall> walls, boolean activeWallMode) {
        // 벽(재질색)
        g.setLineWidth(2.0);
        for (Wall w : walls) {
            Color c = Color.BLACK;
            try {
                if (w != null && w.getMaterial() != null) {
                    c = Color.web(w.getMaterial().colorHex());
                }
            } catch (Exception ignored) { }
            g.setStroke(c);
            g.strokeLine(w.x1, w.y1, w.x2, w.y2);
        }

        // 미리보기(점선)
        if (activeWallMode && firstPoint != null && hoverPoint != null) {
            g.setLineDashes(8, 6);
            g.setStroke(Color.DARKMAGENTA);
            g.setLineWidth(2.0);
            g.strokeLine(firstPoint.getX(), firstPoint.getY(),
                    hoverPoint.getX(), hoverPoint.getY());
            g.setLineDashes(null);
        }
    }
}