package app.ui;

import app.model.*;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.util.List;

public class CanvasView {

    private final ImageView baseImageView;

    private final Canvas drawCanvas;
    private final GraphicsContext g;

    private final Group floorGroup;   // (image + drawCanvas)
    private final Group zoomGroup;    // (floorGroup) -> scale/translate 걸 그룹

    private final StackPane viewportPane;
    private final ScrollPane canvasSP;
    private final StackPane root;

    public CanvasView() {
        baseImageView = new ImageView();
        baseImageView.setPreserveRatio(true);
        baseImageView.setSmooth(true);

        drawCanvas = new Canvas(900, 650);
        g = drawCanvas.getGraphicsContext2D();

        floorGroup = new Group(baseImageView, drawCanvas);
        zoomGroup = new Group(floorGroup);

        viewportPane = new StackPane(zoomGroup);
        viewportPane.setAlignment(Pos.CENTER);
        viewportPane.setPickOnBounds(true);
        viewportPane.setStyle("-fx-background-color: " + Styles.BG_APP + ";");

        canvasSP = new ScrollPane(viewportPane);
        canvasSP.setPannable(false);
        canvasSP.setFitToWidth(false);
        canvasSP.setFitToHeight(false);
        canvasSP.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        canvasSP.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        canvasSP.setStyle("-fx-background: " + Styles.BG_APP + "; -fx-background-color: " + Styles.BG_APP + "; -fx-border-color: transparent;");

        root = new StackPane(canvasSP);
        root.setStyle("-fx-background-color: " + Styles.BG_APP + ";");
    }

    // ===== getters =====
    public StackPane getRoot() { return root; }
    public ImageView getBaseImageView() { return baseImageView; }
    public Canvas getDrawCanvas() { return drawCanvas; }
    public ScrollPane getCanvasSP() { return canvasSP; }
    public StackPane getViewportPane() { return viewportPane; }
    public Group getZoomGroup() { return zoomGroup; }
    public Group getFloorGroup() { return floorGroup; }

    // ===== render =====
    public void render(WifiEnvironment env,
                       AppState state,
                       WritableImage heatmap,
                       List<Point2D> calibPts,
                       Point2D wallFirst,
                       Point2D wallHover,
                       AP hoverAp,
                       AP selectedAp) {

        g.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());

        // heatmap
        if (heatmap != null) {
            g.drawImage(heatmap, 0, 0);
        }

        // walls
        g.setLineWidth(2.0);
        for (Wall w : env.getWalls()) {
            Color c = Color.BLACK;
            try {
                if (w != null && w.getMaterial() != null) {
                    c = Color.web(w.getMaterial().colorHex());
                }
            } catch (Exception ignored) {}
            g.setStroke(c);
            g.strokeLine(w.x1, w.y1, w.x2, w.y2);
        }

        // preview line (WALL/SCALE)
        if (state != null && wallFirst != null && wallHover != null) {
            if (state.getTool() == AppState.Tool.WALL) {
                g.setLineDashes(8, 6);
                g.setStroke(Color.DARKMAGENTA);
                g.setLineWidth(2.0);
                g.strokeLine(wallFirst.getX(), wallFirst.getY(), wallHover.getX(), wallHover.getY());
                g.setLineDashes(null);
            } else if (state.getTool() == AppState.Tool.SCALE) {
                g.setLineDashes(8, 6);
                g.setStroke(Color.DODGERBLUE);
                g.setLineWidth(2.0);
                g.strokeLine(wallFirst.getX(), wallFirst.getY(), wallHover.getX(), wallHover.getY());
                g.setLineDashes(null);
            }
        }

        // AP
        for (AP ap : env.getAps()) {
            if (ap == null || !ap.enabled) continue;

            double r = 6;

            // 기본 점
            g.setFill(Color.DODGERBLUE);
            g.fillOval(ap.x - r, ap.y - r, 2 * r, 2 * r);

            // 기본 테두리
            g.setStroke(Color.WHITE);
            g.setLineWidth(2.0);
            g.strokeOval(ap.x - r, ap.y - r, 2 * r, 2 * r);

            // hover 링
            if (ap == hoverAp) {
                g.setStroke(Color.DODGERBLUE);
                g.setLineWidth(2.0);
                double rr = r + 7;
                g.strokeOval(ap.x - rr, ap.y - rr, 2 * rr, 2 * rr);
            }

            // selected 링
            if (ap == selectedAp) {
                g.setStroke(Color.DARKBLUE);
                g.setLineWidth(3.0);
                double rr = r + 10;
                g.strokeOval(ap.x - rr, ap.y - rr, 2 * rr, 2 * rr);
            }

            // 이름
            g.setFill(Color.BLACK);
            g.fillText(ap.name, ap.x + r + 4, ap.y - r - 2);
        }

        // SCALE 확정선 (두 점 확정되면 실선)
        if (state != null && state.getTool() == AppState.Tool.SCALE && calibPts != null && calibPts.size() == 2) {
            Point2D a = calibPts.get(0);
            Point2D b = calibPts.get(1);
            if (a != null && b != null) {
                g.setStroke(Color.DODGERBLUE);
                g.setLineWidth(2.5);
                g.strokeLine(a.getX(), a.getY(), b.getX(), b.getY());
            }
        }
    }

    // 기존 시그니처 유지용(호출부 남아있으면 깨지니까)
    public void render(WifiEnvironment env,
                       AppState state,
                       WritableImage heatmap,
                       List<Point2D> calibPts,
                       Point2D wallFirst,
                       Point2D wallHover) {
        render(env, state, heatmap, calibPts, wallFirst, wallHover, null, null);
    }
}