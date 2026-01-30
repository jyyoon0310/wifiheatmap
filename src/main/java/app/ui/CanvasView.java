package app.ui;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.ImageView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;

import app.model.*;
import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import java.util.List;

public class CanvasView {

    private final ImageView baseImageView = new ImageView();
    private final Canvas drawCanvas = new Canvas(900, 650);

    // 이미지+캔버스 한 좌표계
    private final Group floorGroup = new Group(baseImageView, drawCanvas);

    // 줌이 걸리는 그룹(ViewportController가 scale 바인딩)
    private final Group zoomGroup = new Group(floorGroup);

    private final StackPane viewportPane = new StackPane(zoomGroup);
    private final ScrollPane canvasSP = new ScrollPane(viewportPane);

    public CanvasView() {
        baseImageView.setPreserveRatio(true);
        baseImageView.setSmooth(true);

        viewportPane.setAlignment(Pos.CENTER);
        viewportPane.setStyle("-fx-background-color: " + Styles.BG_APP + ";");
        viewportPane.setPickOnBounds(true);

        canvasSP.setPannable(false);
        canvasSP.setFitToWidth(false);
        canvasSP.setFitToHeight(false);
        canvasSP.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        canvasSP.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        canvasSP.setStyle("-fx-background: " + Styles.BG_APP + "; -fx-background-color: " + Styles.BG_APP + "; -fx-border-color: transparent;");
    }

    public ScrollPane getCanvasSP() { return canvasSP; }
    public StackPane getViewportPane() { return viewportPane; }
    public Group getZoomGroup() { return zoomGroup; }
    public Group getFloorGroup() { return floorGroup; }
    public ImageView getBaseImageView() { return baseImageView; }
    public Canvas getDrawCanvas() { return drawCanvas; }

    /**
     * Render-only: draws current state to the overlay canvas.
     * Interactive state (preview points etc.) is passed in from controllers.
     */
    public void render(WifiEnvironment env,
                       app.model.AppState state,
                       WritableImage heatmap,
                       List<Point2D> calibPts,
                       Point2D wallFirst,
                       Point2D wallHover) {

        GraphicsContext g = drawCanvas.getGraphicsContext2D();
        if (g == null) return;

        double w = drawCanvas.getWidth();
        double h = drawCanvas.getHeight();
        g.clearRect(0, 0, w, h);

        // 1) Heatmap (optional)
        if (heatmap != null) {
            g.drawImage(heatmap, 0, 0);
        }

        // 2) Walls (material color)
        g.setLineWidth(2.0);
        if (env != null && env.getWalls() != null) {
            for (Wall wall : env.getWalls()) {
                if (wall == null) continue;

                Color c = Color.BLACK;
                try {
                    WallMaterial m = wall.getMaterial();
                    if (m != null) c = Color.web(m.colorHex());
                } catch (Exception ignored) {}

                g.setStroke(c);
                g.strokeLine(wall.x1, wall.y1, wall.x2, wall.y2);
            }
        }

        // 3) Wall preview (optional)
        if (state != null && state.getTool() == app.model.AppState.Tool.WALL && wallFirst != null && wallHover != null) {
            g.setLineDashes(8, 6);
            g.setStroke(Color.DARKMAGENTA);
            g.setLineWidth(2.0);
            g.strokeLine(wallFirst.getX(), wallFirst.getY(), wallHover.getX(), wallHover.getY());
            g.setLineDashes(null);
        }

        // 4) AP markers
        if (env != null && env.getAps() != null) {
            for (AP ap : env.getAps()) {
                if (ap == null || !ap.enabled) continue;

                double r = 6;
                g.setFill(Color.DODGERBLUE);
                g.fillOval(ap.x - r, ap.y - r, 2 * r, 2 * r);
                g.setStroke(Color.WHITE);
                g.setLineWidth(2.0);
                g.strokeOval(ap.x - r, ap.y - r, 2 * r, 2 * r);
                g.setFill(Color.BLACK);
                g.fillText(ap.name, ap.x + r + 4, ap.y - r - 2);
            }
        }

        // 5) Scale calibration points
        if (state != null && state.getTool() == app.model.AppState.Tool.SCALE && calibPts != null) {
            g.setFill(Color.PURPLE);
            for (Point2D p : calibPts) {
                if (p == null) continue;
                g.fillOval(p.getX() - 3, p.getY() - 3, 6, 6);
            }
        }
    }
}