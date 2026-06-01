package app.ui;

import app.model.*;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

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

    /** 빈 캔버스 안내 오버레이 */
    private final VBox emptyGuide = new VBox(10);
    private final Label emptyEmoji = new Label("📂");
    private final Label emptyTitle = new Label("평면도를 불러와 시작하세요");
    private final Label emptyDesc  = new Label(
            "상단 도구모음의 [열기] 버튼을 눌러 도면 이미지(PNG, JPG)를 선택하세요.");

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

        canvasSP = new ScrollPane(viewportPane);
        canvasSP.setPannable(false);
        canvasSP.setFitToWidth(false);
        canvasSP.setFitToHeight(false);
        canvasSP.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        canvasSP.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        // ── 빈 캔버스 가이드 오버레이 (평면도 없을 때만 표시) ────────────
        emptyEmoji.setStyle("-fx-font-size:56px;");
        emptyTitle.setWrapText(true);
        emptyDesc.setWrapText(true);
        emptyGuide.setAlignment(Pos.CENTER);
        emptyGuide.setMaxWidth(440);
        emptyGuide.setPadding(new Insets(28, 32, 28, 32));
        emptyGuide.setMouseTransparent(true);
        emptyGuide.getChildren().addAll(emptyEmoji, emptyTitle, emptyDesc);

        Runnable applyGuideTheme = () -> {
            // 카드 박스 — 배경 + 둥근 모서리 + 약한 그림자
            emptyGuide.setStyle(
                    "-fx-background-color:" + Styles.bgPanel() + ";" +
                    "-fx-background-radius:14;" +
                    "-fx-border-color:" + Styles.borderSoft() + ";" +
                    "-fx-border-radius:14;" +
                    "-fx-border-width:1;" +
                    "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 24, 0.12, 0, 6);");
            emptyTitle.setStyle(
                    "-fx-text-fill:" + Styles.textMain() + ";" +
                    "-fx-font-size:18px;" +
                    "-fx-font-weight:bold;" +
                    "-fx-font-family:" + Styles.FONT_STACK + ";");
            emptyDesc.setStyle(
                    "-fx-text-fill:" + Styles.textSub() + ";" +
                    "-fx-font-size:12px;" +
                    "-fx-font-family:" + Styles.FONT_STACK + ";");
            emptyDesc.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            emptyTitle.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        };
        applyGuideTheme.run();
        Styles.addThemeListener(applyGuideTheme);

        root = new StackPane(canvasSP, emptyGuide);
        StackPane.setAlignment(emptyGuide, Pos.CENTER);

        Runnable applyCanvasTheme = () -> {
            viewportPane.setStyle("-fx-background-color: " + Styles.bgApp() + ";");
            canvasSP.setStyle("-fx-background: " + Styles.bgApp() + "; -fx-background-color: " + Styles.bgApp() + "; -fx-border-color: transparent;");
            root.setStyle("-fx-background-color: " + Styles.bgApp() + ";");
        };
        applyCanvasTheme.run();
        Styles.addThemeListener(applyCanvasTheme);
    }

    /**
     * 빈 캔버스 가이드 — 평면도가 없을 때만 표시. 평면도가 로드되면 도면이
     * 작업 자체가 되므로 가이드 텍스트로 도면을 가리지 않는다.
     *
     * <p>이후의 단계별 안내(공유기 배치 / 히트맵 생성)는 도구 버튼의 툴팁과
     * LeftPanel의 카드 상태로 처리한다.</p>
     */
    public void updateGuideOverlay(boolean hasFloorplan, boolean hasAps, boolean hasHeatmap) {
        if (!hasFloorplan) {
            emptyEmoji.setText("📂");
            emptyTitle.setText("평면도를 불러와 시작하세요");
            emptyDesc.setText("상단의 [열기] 버튼을 눌러 도면 이미지(PNG, JPG)를 선택하거나,\n"
                    + "[불러오기]로 저장된 작업을 이어할 수 있습니다.");
            emptyGuide.setVisible(true);
            emptyGuide.setManaged(true);
        } else {
            emptyGuide.setVisible(false);
            emptyGuide.setManaged(false);
        }
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
                       WritableImage solverOverlay,
                       List<Point2D> calibPts,
                       Point2D wallFirst,
                       Point2D wallHover,
                       AP hoverAp,
                       AP selectedAp,
                       Wall hoverWall,
                       Wall selectedWall,
                       List<PropagationPath> debugPaths,
                       String solverDebugText) {

        g.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());

        // heatmap
        if (heatmap != null) {
            g.drawImage(heatmap, 0, 0);
        }
        if (solverOverlay != null) {
            g.save();
            g.setGlobalAlpha(0.74);
            g.drawImage(solverOverlay, 0, 0);
            g.restore();
            drawSolverDebugHud(solverDebugText);
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
            if (w == selectedWall) {
                g.setStroke(Color.ORANGE);
                g.setLineWidth(4.0);
            } else if (w == hoverWall) {
                g.setStroke(c.brighter());
                g.setLineWidth(3.0);
            } else {
                g.setStroke(c);
                g.setLineWidth(2.0);
            }
            g.strokeLine(w.x1, w.y1, w.x2, w.y2);
            if (w == selectedWall) {
                g.setFill(Color.ORANGE);
                g.fillOval(w.x1 - 3, w.y1 - 3, 6, 6);
                g.fillOval(w.x2 - 3, w.y2 - 3, 6, 6);
            }
        }

        // propagation debug paths
        if (debugPaths != null && !debugPaths.isEmpty()) {
            for (PropagationPath p : debugPaths) {
                if (p == null || p.p1 == null || p.p2 == null) continue;
                switch (p.type) {
                    case LOS -> {
                        g.setStroke(Color.web("#94A3B8"));
                        g.setLineDashes(6, 6);
                        g.setLineWidth(1.5);
                        g.strokeLine(p.p1.getX(), p.p1.getY(), p.p2.getX(), p.p2.getY());
                        g.setLineDashes(null);
                    }
                    case REFLECTION -> {
                        if (p.p3 == null) break;
                        g.setStroke(Color.web("#2563EB"));
                        g.setLineWidth(2.0);
                        g.strokeLine(p.p1.getX(), p.p1.getY(), p.p2.getX(), p.p2.getY());
                        g.strokeLine(p.p2.getX(), p.p2.getY(), p.p3.getX(), p.p3.getY());
                        g.setFill(Color.web("#2563EB"));
                        g.fillOval(p.p2.getX() - 3, p.p2.getY() - 3, 6, 6);
                    }
                    case DIFFRACTION -> {
                        if (p.p3 == null) break;
                        g.setStroke(Color.web("#16A34A"));
                        g.setLineWidth(2.0);
                        g.strokeLine(p.p1.getX(), p.p1.getY(), p.p2.getX(), p.p2.getY());
                        g.strokeLine(p.p2.getX(), p.p2.getY(), p.p3.getX(), p.p3.getY());
                        g.setFill(Color.web("#16A34A"));
                        g.fillOval(p.p2.getX() - 3, p.p2.getY() - 3, 6, 6);
                    }
                }
            }
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
            boolean isSelected = (ap == selectedAp);
            Color dotColor = isSelected ? Color.ORANGE : Color.DODGERBLUE;

            // ✅ 기본은 "파란 점"만 (테두리/링 없음)
            g.setFill(dotColor);
            g.fillOval(ap.x - r, ap.y - r, 2 * r, 2 * r);

            // ✅ hover 링만 표시 (VIEW 포함)
            if (ap == hoverAp) {
                g.setStroke(dotColor);
                g.setLineWidth(2.0);
                double rr = r + 7;
                g.strokeOval(ap.x - rr, ap.y - rr, 2 * rr, 2 * rr);
            }

            // 이름
            g.setFill(isSelected ? Color.ORANGE : (Styles.isDark() ? Color.web("#E8E8ED") : Color.BLACK));
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
        render(env, state, heatmap, null, calibPts, wallFirst, wallHover, null, null, null, null, null, null);
    }

    public void render(WifiEnvironment env,
                       AppState state,
                       WritableImage heatmap,
                       List<Point2D> calibPts,
                       Point2D wallFirst,
                       Point2D wallHover,
                       AP hoverAp,
                       AP selectedAp) {
        render(env, state, heatmap, null, calibPts, wallFirst, wallHover, hoverAp, selectedAp, null, null, null, null);
    }

    private void drawSolverDebugHud(String solverDebugText) {
        if (solverDebugText == null || solverDebugText.isBlank()) return;
        String[] lines = solverDebugText.split("\\R");
        if (lines.length == 0) return;

        g.save();
        g.setFont(Font.font("Menlo", 11));

        double x = 14.0;
        double y = 14.0;
        double lineH = 15.0;
        double w = 0.0;
        for (String line : lines) {
            w = Math.max(w, line.length() * 6.7);
        }
        double h = 10.0 + lines.length * lineH;

        g.setFill(Color.rgb(18, 18, 24, 0.72));
        g.fillRoundRect(x, y, w + 16.0, h, 10.0, 10.0);
        g.setStroke(Color.rgb(255, 255, 255, 0.18));
        g.strokeRoundRect(x, y, w + 16.0, h, 10.0, 10.0);

        g.setFill(Color.rgb(240, 244, 255, 0.95));
        double ty = y + 16.0;
        for (String line : lines) {
            g.fillText(line, x + 8.0, ty);
            ty += lineH;
        }
        g.restore();
    }
}
