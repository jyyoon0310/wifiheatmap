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
    private final Label toast = new Label();
    private javafx.animation.Animation toastAnim;
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
        // 최대 높이를 내용에 묶지 않으면 StackPane 이 카드를 캔버스 높이 전체로 늘려
        // 첫 화면 가운데에 커다란 흰 기둥이 생겼다.
        emptyGuide.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
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

        // ── 토스트 (흐름을 막지 않는 짧은 알림) ─────────────────────────────
        // 히트맵 색 위에서도 읽히도록 테마와 무관하게 어두운 반투명 알약 (macOS HUD 계열)
        toast.setWrapText(true);
        toast.setMaxWidth(520);
        toast.setMouseTransparent(true);
        toast.setVisible(false);
        toast.setStyle(
                "-fx-background-color: rgba(28,28,30,0.88);" +
                "-fx-background-radius: 14;" +
                "-fx-padding: 10 18;" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";" +
                "-fx-border-color: rgba(255,255,255,0.14);" +   // 다크 모드 배경 위에서도 윤곽이 보이도록
                "-fx-border-radius: 14;" +
                "-fx-border-width: 0.5;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.30), 18, 0.15, 0, 4);");
        root.getChildren().add(toast);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 28, 0));

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

    /**
     * 흐름을 막지 않는 짧은 알림을 캔버스 아래쪽 가운데에 띄웠다가 사라지게 한다.
     * 성공·안내 메시지용 — 확인이 필요한 경고·오류는 모달({@link Notify})로.
     * 표시 시간은 글 길이에 비례 (2.5초 + 글자당 50ms, 최대 7초).
     */
    public void showToast(String message) {
        if (message == null || message.isBlank()) return;
        if (toastAnim != null) toastAnim.stop();
        toast.setText(message);
        // 짧은 글은 글 폭만큼, 긴 글은 520px 에서 줄바꿈 (StackPane 이 최대 폭까지 늘리지 않도록)
        toast.setMaxWidth(520);
        toast.applyCss();
        double textW = toast.prefWidth(-1);
        toast.setMaxWidth(Math.min(520, textW));
        toast.setOpacity(0);
        toast.setVisible(true);
        toast.toFront();
        double holdMs = Math.min(7000, 2500 + 50.0 * message.length());
        javafx.animation.FadeTransition in = new javafx.animation.FadeTransition(javafx.util.Duration.millis(160), toast);
        in.setToValue(1);
        javafx.animation.PauseTransition stay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(holdMs));
        javafx.animation.FadeTransition out = new javafx.animation.FadeTransition(javafx.util.Duration.millis(260), toast);
        out.setToValue(0);
        toastAnim = new javafx.animation.SequentialTransition(in, stay, out);
        toastAnim.setOnFinished(e -> toast.setVisible(false));
        toastAnim.play();
    }
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

        // AP — 공유기 마커(흰 테두리 원 + Wi-Fi 기호) + 아래 이름표.
        // (예전: 6px 파란 점 + 배경 없는 글씨 → 히트맵 위에서 잘 안 보이고 문 호와 겹쳤다)
        for (AP ap : env.getAps()) {
            if (ap == null || !ap.enabled) continue;
            drawApMarker(ap, ap == selectedAp, ap == hoverAp);
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
        render(env, state, heatmap, null, calibPts, wallFirst, wallHover, null, null, null, null, null);
    }

    public void render(WifiEnvironment env,
                       AppState state,
                       WritableImage heatmap,
                       List<Point2D> calibPts,
                       Point2D wallFirst,
                       Point2D wallHover,
                       AP hoverAp,
                       AP selectedAp) {
        render(env, state, heatmap, null, calibPts, wallFirst, wallHover, hoverAp, selectedAp, null, null, null);
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

    // ── 공유기 마커 ──────────────────────────────────────────────────────────
    private static final double AP_R = 11;          // 마커 반지름 [px, 도면 좌표]
    private static final Font AP_LABEL_FONT = Font.font("System", javafx.scene.text.FontWeight.SEMI_BOLD, 12);

    private void drawApMarker(AP ap, boolean selected, boolean hovered) {
        double x = ap.x, y = ap.y;
        Color fill = selected ? Color.web("#FF9500") : Color.web(Styles.accent());

        // 마우스를 올리면 바깥 고리
        if (hovered || selected) {
            g.setFill(fill.deriveColor(0, 1, 1, 0.22));
            double hr = AP_R + 7;
            g.fillOval(x - hr, y - hr, hr * 2, hr * 2);
        }

        // 원 + 흰 테두리 + 그림자
        javafx.scene.effect.DropShadow shadow = new javafx.scene.effect.DropShadow(6, 0, 1.5, Color.rgb(0, 0, 0, 0.35));
        g.setEffect(shadow);
        g.setFill(Color.WHITE);
        g.fillOval(x - AP_R - 2, y - AP_R - 2, (AP_R + 2) * 2, (AP_R + 2) * 2);
        g.setEffect(null);
        g.setFill(fill);
        g.fillOval(x - AP_R, y - AP_R, AP_R * 2, AP_R * 2);

        // Wi-Fi 기호 — 아래 점 + 동심 호 2개 (흰색)
        g.setStroke(Color.WHITE);
        g.setLineWidth(1.8);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        double cy = y + 4.0;
        for (double rr : new double[]{4.2, 7.6}) {
            g.strokeArc(x - rr, cy - rr, rr * 2, rr * 2, 45, 90, javafx.scene.shape.ArcType.OPEN);
        }
        g.setFill(Color.WHITE);
        g.fillOval(x - 1.6, cy - 1.6, 3.2, 3.2);

        // 이름표 — 마커 아래 알약 배경 (히트맵 색 위에서도 읽히도록)
        String name = (ap.name == null || ap.name.isBlank()) ? "공유기" : ap.name;
        javafx.scene.text.Text measure = new javafx.scene.text.Text(name);
        measure.setFont(AP_LABEL_FONT);
        double tw = measure.getLayoutBounds().getWidth();
        double th = measure.getLayoutBounds().getHeight();
        double padX = 7, padY = 3;
        double bw = tw + padX * 2, bh = th + padY * 2;
        double bx = x - bw / 2, by = y + AP_R + 6;
        boolean dark = Styles.isDark();
        g.setEffect(new javafx.scene.effect.DropShadow(4, 0, 1, Color.rgb(0, 0, 0, 0.25)));
        g.setFill(dark ? Color.rgb(28, 28, 30, 0.92) : Color.rgb(255, 255, 255, 0.94));
        g.fillRoundRect(bx, by, bw, bh, bh, bh);
        g.setEffect(null);
        if (selected) {
            g.setStroke(fill);
            g.setLineWidth(1.5);
            g.strokeRoundRect(bx, by, bw, bh, bh, bh);
        }
        g.setFont(AP_LABEL_FONT);
        g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        g.setTextBaseline(javafx.geometry.VPos.TOP);
        g.setFill(dark ? Color.web("#F2F2F5") : Color.web("#1C1C1E"));
        g.fillText(name, x, by + padY);
        // 다른 그리기 코드가 기본 정렬을 가정하므로 되돌린다
        g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        g.setTextBaseline(javafx.geometry.VPos.BASELINE);
    }
}
