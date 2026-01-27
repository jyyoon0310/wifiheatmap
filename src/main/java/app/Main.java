package app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.*;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wi-Fi Heatmap
 * - 경로손실 PL(d) + 직선 경로상 벽 감쇠만 적용하는 단순 모델
 * - 벽: 재질 프리셋(WallMaterial) 선택 가능
 *   * 프리셋 선택 → 기본 dB 자동 반영
 *   * dB 직접 수정 → 재질을 CUSTOM(사용자 지정)으로 전환
 */
public class Main extends Application {

    // ===== NetSpot-ish Light Theme (inline styles; no external CSS file) =====
    private static final String BG_APP = "#FFFFFF";
    private static final String BG_PANEL = "#F7F8FA";
    private static final String BORDER_SOFT = "#E6E9EF";
    private static final String TEXT_MAIN = "#1F2937";
    private static final String TEXT_SUB = "#6B7280";
    private static final String ACCENT = "#3B82F6";

    private static void styleFlatButton(Button b) {
        b.setStyle("-fx-background-color: " + BG_PANEL + ";" +
                "-fx-text-fill: " + TEXT_MAIN + ";" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: " + BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-padding: 6 10;" +
                "-fx-font-size: 12px;");
    }

    private static void styleAccentButton(Button b) {
        b.setStyle("-fx-background-color: " + ACCENT + ";" +
                "-fx-text-fill: white;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 6 12;" +
                "-fx-font-size: 12px;");
    }

    private static void styleTextField(TextField tf) {
        tf.setStyle("-fx-background-color: " + BG_APP + ";" +
                "-fx-text-fill: " + TEXT_MAIN + ";" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: " + BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-padding: 6 8;" +
                "-fx-font-size: 12px;");
    }

    private static void styleSpinner(Spinner<?> sp) {
        sp.setStyle("-fx-background-color: " + BG_APP + ";" +
                "-fx-border-color: " + BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;");
        if (sp.getEditor() != null) {
            styleTextField(sp.getEditor());
        }
    }

    /** Left-panel “card” container (NetSpot-ish group). */
    private static VBox card(String title, Node... content) {
        Label t = new Label(title);
        t.setStyle("-fx-text-fill: " + TEXT_MAIN + "; -fx-font-size: 13px; -fx-font-weight: 600;");

        VBox box = new VBox(10);
        box.getChildren().add(t);
        box.getChildren().addAll(content);
        box.setPadding(new Insets(12));
        box.setStyle(
                "-fx-background-color: " + BG_APP + ";" +
                "-fx-background-radius: 12;" +
                "-fx-border-color: " + BORDER_SOFT + ";" +
                "-fx-border-radius: 12;"
        );
        return box;
    }

    /**
     * Style ComboBox popup (dropdown) surface.
     * JavaFX popup styling is tricky without external CSS, so we style the popup node tree on showing.
     */
    private static void installComboPopupStyle(ComboBox<?> cb) {
        cb.showingProperty().addListener((o, ov, showing) -> {
            if (!showing) return;
            Platform.runLater(() -> {
                // The popup is created lazily; after showing, nodes are available for lookup.
                try {
                    Node popup = cb.lookup(".combo-box-popup");
                    if (popup != null) {
                        popup.setStyle(
                                "-fx-background-color: " + BG_APP + ";" +
                                "-fx-border-color: " + BORDER_SOFT + ";" +
                                "-fx-border-radius: 10;" +
                                "-fx-background-radius: 10;" +
                                "-fx-padding: 6;"
                        );
                    }
                    Node lv = cb.lookup(".combo-box-popup .list-view");
                    if (lv != null) {
                        lv.setStyle(
                                "-fx-background-color: " + BG_APP + ";" +
                                "-fx-control-inner-background: " + BG_APP + ";" +
                                "-fx-border-color: transparent;" +
                                "-fx-background-radius: 10;"
                        );
                    }
                    Node cells = cb.lookup(".combo-box-popup .list-view .virtual-flow");
                    if (cells != null) {
                        cells.setStyle("-fx-background-color: " + BG_APP + ";");
                    }
                } catch (Exception ignored) {
                }
            });
        });
    }

    private static void styleToggle(ToggleButton t) {
        t.setStyle("-fx-background-color: " + BG_PANEL + ";" +
                "-fx-text-fill: " + TEXT_MAIN + ";" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: " + BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-padding: 6 10;" +
                "-fx-font-size: 12px;");
        t.selectedProperty().addListener((o, ov, nv) -> {
            if (nv) {
                t.setStyle("-fx-background-color: " + ACCENT + ";" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-border-color: transparent;" +
                        "-fx-padding: 6 10;" +
                        "-fx-font-size: 12px;");
            } else {
                t.setStyle("-fx-background-color: " + BG_PANEL + ";" +
                        "-fx-text-fill: " + TEXT_MAIN + ";" +
                        "-fx-background-radius: 8;" +
                        "-fx-border-color: " + BORDER_SOFT + ";" +
                        "-fx-border-radius: 8;" +
                        "-fx-padding: 6 10;" +
                        "-fx-font-size: 12px;");
            }
        });
    }

    /**
     * UI에서 관리하는 파라미터 값을 WifiEnvironment에 반영한다.
     * (스케일, 경로손실 지수 n 만 동기화)
     */
    private void syncEnvParams() {
        env.setScaleMPerPx(scaleMPerPx.get());
        env.setPathLossN(pathLossN.get());
    }

    // ===== 상태 =====
    private final ObjectProperty<Image> floorplan = new SimpleObjectProperty<>();
    private BufferedImage floorplanBI;
    private final DoubleProperty scaleMPerPx = new SimpleDoubleProperty(Double.NaN);
    // 히트맵 격자: 4 px 고정
    private final IntegerProperty gridStepPx = new SimpleIntegerProperty(4);

    // Wi-Fi 환경
    private final WifiEnvironment env = new WifiEnvironment();
    private final javafx.collections.ObservableList<AP> aps = env.getAps();
    private final javafx.collections.ObservableList<Wall> walls = env.getWalls();

    // 상단 툴 토글(모드)
    private ToggleGroup toolToggleGroup;
    private ToggleButton tScaleBtn;
    private ToggleButton tAPBtn;
    private ToggleButton tWallBtn;

    private final DoubleProperty calibRealMeters = new SimpleDoubleProperty(5.0);
    private final ScaleController scaleController = new ScaleController(calibRealMeters, scaleMPerPx);

    private enum Tool { VIEW, SCALE, AP, WALL }
    private final ObjectProperty<Tool> tool = new SimpleObjectProperty<>(Tool.VIEW);

    // 렌더 요소
    private ImageView baseImageView;
    private Canvas drawCanvas;
    private GraphicsContext g;
    private WritableImage heatmapImage;

    // ===== Zoom / Pan (CAD-like) =====
    private ScrollPane canvasSP;
    private StackPane viewportPane;
    private Group zoomGroup;
    private Group floorGroup;
    private ViewportController viewportController;

    // 우클릭 드래그로 팬
    // 팬(우클릭 / Space+좌클릭 드래그)
    private boolean panning = false;
    private double panStartSceneX, panStartSceneY;
    private double panStartH, panStartV;
    private boolean spaceDown = false;
    private MouseButton panButton = MouseButton.SECONDARY;

    // 실행기
    private final ExecutorService exec =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

    // Legend & 호버
    private final DoubleProperty legendMin = new SimpleDoubleProperty(-96);
    private final DoubleProperty legendMax = new SimpleDoubleProperty(-10);
    private final DoubleProperty hoverRssiStrongest = new SimpleDoubleProperty(Double.NaN);
    private Canvas legendCanvas;            // 오버레이
    private Label legendLabel;
    private Canvas legendCanvasDock;        // 도킹
    private Label legendLabelDock;
    private Label hoverTip;
    private final int LEGEND_W = 260, LEGEND_H = 20;

    // 줌 컨트롤 UI
    private Label zoomPctLabel;

    // ===== 벽(재질 프리셋 + 커스텀 dB) =====
    private final ObjectProperty<WallMaterial> defaultWallMaterial =
            new SimpleObjectProperty<>(WallMaterial.CONCRETE_WALL);
    private final DoubleProperty defaultWallDb =
            new SimpleDoubleProperty(defaultWallMaterial.get().defaultAttenuationDb());

    // 벽 프리셋 UI 리스너 참조 (프로그램 세팅 시 잠깐 떼었다 붙이기 위해)
    private ChangeListener<WallMaterial> wallMatListener;
    private ChangeListener<Double> wallDbListener;

    // 수동 벽 그리기(두 점 클릭) 로직 분리
    private final WallDrawController wallDrawController = new WallDrawController();

    // AP 로직 분리
    private ApController apController;
    private Stage primaryStage;

    // 모델 파라미터 (고정)
    private static final double FIXED_PATH_LOSS_N = 2.5;
    private static final int FIXED_SMOOTH_RADIUS_PX = 8;
    private final DoubleProperty pathLossN = new SimpleDoubleProperty(FIXED_PATH_LOSS_N);
    private final IntegerProperty smoothRadiusPx = new SimpleIntegerProperty(FIXED_SMOOTH_RADIUS_PX);

    // 하단 도킹 바
    private HBox legendDockBar;


    // ===== 앱 시작 =====
    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG_APP + "; -fx-font-family: 'System'; -fx-text-fill: " + TEXT_MAIN + ";");

        // 상단 툴바
        ToolBar tb = buildToolbar(stage);
        root.setTop(tb);

        // 좌측 패널(스크롤)
        this.primaryStage = stage;

        // AP 컨트롤러 연결 (Left panel에서 AP 리스트를 만들 때 필요)
        apController = new ApController(
                aps,
                this::redraw,
                () -> { if (heatmapImage != null) generateHeatmapAsync(); }
        );

        VBox left = buildLeftPanel(stage);
        ScrollPane leftSP = new ScrollPane(left);
        leftSP.setFitToWidth(true);
        leftSP.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftSP.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftSP.setPrefViewportWidth(360);
        left.setStyle("-fx-background-color: " + BG_PANEL + ";");
        leftSP.setStyle("-fx-background: " + BG_PANEL + "; -fx-background-color: " + BG_PANEL + ";" +
                "-fx-border-color: " + BORDER_SOFT + "; -fx-border-width: 0 1 0 0;");
        root.setLeft(leftSP);

        // 중앙 캔버스/이미지 영역 구성 (줌/팬 지원)
        StackPane center = new StackPane();
        center.setStyle("-fx-background-color: " + BG_APP + ";");

        baseImageView = new ImageView();
        baseImageView.setPreserveRatio(true);
        baseImageView.setSmooth(true);

        drawCanvas = new Canvas(900, 650);
        g = drawCanvas.getGraphicsContext2D();

        // 이미지 + 캔버스를 같은 좌표계로 묶어서 함께 확대/축소
        floorGroup = new Group(baseImageView, drawCanvas);

        // scale을 걸 그룹 (줌/팬 컨트롤러가 scale 바인딩을 담당)
        zoomGroup = new Group(floorGroup);

        viewportPane = new StackPane(zoomGroup);
        viewportPane.setAlignment(Pos.CENTER); // 핵심
        viewportPane.setStyle("-fx-background-color: " + BG_APP + ";");
        viewportPane.setPickOnBounds(true);

        canvasSP = new ScrollPane(viewportPane);
        canvasSP.setPannable(false); // 팬은 우클릭 드래그로 구현
        canvasSP.setFitToWidth(false);
        canvasSP.setFitToHeight(false);
        canvasSP.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        canvasSP.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        canvasSP.setStyle("-fx-background: " + BG_APP + "; -fx-background-color: " + BG_APP + "; -fx-border-color: transparent;");

        // 줌/팬 컨트롤러 연결
        viewportController = new ViewportController(canvasSP, viewportPane, zoomGroup, floorGroup);
        viewportController.updateViewportSize();

        center.getChildren().add(canvasSP);
        root.setCenter(center);


        // ===== Zoom controls (overlay, top-left) ===== hookup
        Button zoomInBtn = new Button("+");
        Button zoomOutBtn = new Button("−");
        Button zoom100Btn = new Button("100%");
        Button centerBtn = new Button("센터");
        Button fitBtn = new Button("맞춤");

        styleFlatButton(zoomInBtn);
        styleFlatButton(zoomOutBtn);
        styleFlatButton(zoom100Btn);
        styleFlatButton(centerBtn);
        styleFlatButton(fitBtn);

        // 버튼이 너무 넓지 않게
        zoomInBtn.setPrefWidth(34);
        zoomOutBtn.setPrefWidth(34);

        zoomPctLabel = new Label();
        zoomPctLabel.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 12px;");
        zoomPctLabel.textProperty().bind(viewportController.zoomScaleProperty().multiply(100).asString("%.0f%%"));

        HBox zoomBar = new HBox(6, zoomOutBtn, zoomInBtn, zoom100Btn, fitBtn, centerBtn, zoomPctLabel);
        zoomBar.setAlignment(Pos.CENTER_LEFT);
        zoomBar.setPadding(new Insets(6));
        zoomBar.setBackground(new Background(new BackgroundFill(
                Color.rgb(255, 255, 255, 0.92), new CornerRadii(10), Insets.EMPTY)));
        zoomBar.setBorder(new Border(new BorderStroke(Color.web(BORDER_SOFT),
                BorderStrokeStyle.SOLID, new CornerRadii(10), new BorderWidths(1))));
        zoomBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(zoomBar, Pos.TOP_LEFT);
        StackPane.setMargin(zoomBar, new Insets(14, 0, 0, 14));
        center.getChildren().add(zoomBar);

        // actions
        zoomInBtn.setOnAction(e -> viewportController.zoomAt(1.10, center.getScene().getWidth() / 2.0, center.getScene().getHeight() / 2.0));
        zoomOutBtn.setOnAction(e -> viewportController.zoomAt(1.0 / 1.10, center.getScene().getWidth() / 2.0, center.getScene().getHeight() / 2.0));
        zoom100Btn.setOnAction(e -> {
            viewportController.setZoom(1.0);
            viewportController.centerViewport();
        });
        centerBtn.setOnAction(e -> viewportController.centerViewport());
        fitBtn.setOnAction(e -> viewportController.fitToViewport(20, drawCanvas.getWidth(), drawCanvas.getHeight()));

        // 우하단 레전드(오버레이)
        VBox legendBox = new VBox(4);
        legendBox.setPadding(new Insets(6));
        legendBox.setBackground(new Background(new BackgroundFill(
                Color.rgb(255, 255, 255, 0.92), new CornerRadii(10), Insets.EMPTY)));
        legendBox.setBorder(new Border(new BorderStroke(Color.web(BORDER_SOFT),
                BorderStrokeStyle.SOLID, new CornerRadii(10), new BorderWidths(1))));
        legendCanvas = new Canvas(LEGEND_W, LEGEND_H);
        legendLabel = new Label("—   |   -96 dBm     -53 dBm     -10 dBm");
        legendBox.getChildren().addAll(legendCanvas, legendLabel);
        legendBox.setMouseTransparent(true);
        legendBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(legendBox, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(legendBox, new Insets(0, 18, 18, 0));
        center.getChildren().add(legendBox);

        // 하단 도킹 레전드
        legendDockBar = new HBox(10);
        legendDockBar.setPadding(new Insets(6));
        legendDockBar.setAlignment(Pos.CENTER_RIGHT);
        legendDockBar.setBackground(new Background(new BackgroundFill(
                Color.rgb(255, 255, 255, 0.9), new CornerRadii(4), Insets.EMPTY)));
        legendCanvasDock = new Canvas(LEGEND_W, LEGEND_H);
        legendLabelDock = new Label("");
        legendDockBar.getChildren().addAll(legendCanvasDock, legendLabelDock);
        legendDockBar.setVisible(false);

        // 호버 툴팁 (고정: 우상단)
        hoverTip = new Label();
        hoverTip.setStyle("-fx-background-color: rgba(17,24,39,0.85); -fx-text-fill: white; -fx-padding: 8 10; -fx-background-radius: 10; -fx-font-size: 12px;");
        hoverTip.setVisible(false);
        hoverTip.setMouseTransparent(true);
        hoverTip.setMaxWidth(420);
        hoverTip.setWrapText(true);
        StackPane.setAlignment(hoverTip, Pos.TOP_RIGHT);
        StackPane.setMargin(hoverTip, new Insets(14, 14, 0, 0));
        center.getChildren().add(hoverTip);

        // floorplan 바인딩
        floorplan.addListener((o, ov, img) -> {
            baseImageView.setImage(img);
            if (img != null) {
                drawCanvas.setWidth(img.getWidth());
                drawCanvas.setHeight(img.getHeight());

                // 원본 콘텐츠 크기 갱신 + 로드 시 줌 초기화
                viewportController.setBaseContentSize(img.getWidth(), img.getHeight());
                viewportController.setZoom(1.0);
                viewportController.updateViewportSize();

                // 스케일 상태 초기화
                scaleController.clear();

                // 가운데로 (레이아웃이 잡힌 다음)
                Platform.runLater(viewportController::centerViewport);
                redraw();
            }
        });

        // ===== 인터랙션 =====

        // 클릭 시 스케일 점/AP 추가 및 AP편집, 벽 두 점 클릭
        drawCanvas.setOnMouseClicked(e -> {
            double x = e.getX(), y = e.getY();
            if (panning || spaceDown) {
                e.consume();
                return;
            }

            // Shift + 클릭시 AP추가 (AP 모드에서만)
            if (tool.get() == Tool.AP && e.isShiftDown() && e.getButton() == MouseButton.PRIMARY) {
                apController.addAPAt(x, y);
                return;
            }

            // WALL 모드: 두 점 클릭으로 벽 생성 (WallDrawController)
            if (tool.get() == Tool.WALL && e.getButton() == MouseButton.PRIMARY) {
                WallMaterial chosen = defaultWallMaterial.get();
                double customDb = defaultWallDb.get();

                boolean created = wallDrawController.onClick(x, y, walls, chosen, customDb);
                if (created && heatmapImage != null) generateHeatmapAsync();

                redraw();
                return;
            }

            if (tool.get() == Tool.SCALE) {
                // SCALE: WALL처럼 두 점 선분으로 지정(미리보기 포함)
                scaleController.onClick(x, y);
                redraw();
                return;
            } else if (tool.get() == Tool.AP) {
                // AP모드일때 : 클릭 시 AP 추가, 더블클릭시 편집
                if (e.getButton() == MouseButton.PRIMARY) apController.addAPAt(x, y);
                if (e.getClickCount() == 2) {
                    AP near = apController.findApNear(x, y, 10);
                    if (near != null) apController.editAp(primaryStage, near);
                }
            }
        });

        // 호버: 마우스 호버시 해당 위치의 최강 RSSI계산 및 벽 미리보기
        drawCanvas.setOnMouseMoved(e -> {
            // WALL 미리보기: 첫 점을 찍은 상태에서 마우스 이동 시 선 갱신
            if (tool.get() == Tool.WALL && wallDrawController.isDrawing()) {
                wallDrawController.onMove(e.getX(), e.getY());
                redraw();
                // 미리보기 중에는 RSSI 호버를 굳이 갱신하지 않음
                return;
            }
            // SCALE 미리보기: 첫 점을 찍은 상태에서 마우스 이동 시 선 갱신
            if (tool.get() == Tool.SCALE && scaleController.isDrawing()) {
                scaleController.onMove(e.getX(), e.getY());
                redraw();
                return;
            }
            if (Double.isNaN(scaleMPerPx.get()) || aps.isEmpty()) {
                hoverRssiStrongest.set(Double.NaN);
                hoverTip.setVisible(false);
                redrawLegend();
                return;
            }

            syncEnvParams();

            var list = env.sampleRssiAllAt((int) e.getX(), (int) e.getY());
            if (list.isEmpty()) {
                hoverTip.setVisible(false);
                hoverRssiStrongest.set(Double.NaN);
                redrawLegend();
                return;
            }

            // 레전드 포인터는 여전히 최강값 사용
            hoverRssiStrongest.set(list.get(0).rssiDbm);

            int maxLines = Math.min(8, list.size()); // 보기 좋게 8개 제한
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxLines; i++) {
                var r = list.get(i);
                sb.append(String.format("%s (%s) : %.1f dBm", r.ssid, r.band.label, r.rssiDbm));
                if (i < maxLines - 1) sb.append("\n");
            }

            hoverTip.setText(sb.toString());
            hoverTip.setVisible(true);
            redrawLegend();
        });

        // 캔버스 밖 호버값 초기화
        drawCanvas.setOnMouseExited(e -> {
            hoverTip.setVisible(false);
            hoverRssiStrongest.set(Double.NaN);
            redrawLegend();
        });

        // 우클릭 드래그로 팬 (캔버스가 최상단이라 여기서 받아야 안정적)
        drawCanvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            boolean startPan = (e.getButton() == MouseButton.SECONDARY)
                    || (spaceDown && e.getButton() == MouseButton.PRIMARY);
            if (startPan) {
                panning = true;
                panButton = e.getButton();
                panStartSceneX = e.getSceneX();
                panStartSceneY = e.getSceneY();
                panStartH = canvasSP.getHvalue();
                panStartV = canvasSP.getVvalue();
                drawCanvas.setCursor(Cursor.CLOSED_HAND);
                e.consume();
            }
        });

        drawCanvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (!panning) return;

            Bounds vp = canvasSP.getViewportBounds();
            double contentW = viewportPane.getPrefWidth();
            double contentH = viewportPane.getPrefHeight();

            double denomX = contentW - vp.getWidth();
            double denomY = contentH - vp.getHeight();
            if (denomX <= 0 || denomY <= 0) return;

            double dx = e.getSceneX() - panStartSceneX;
            double dy = e.getSceneY() - panStartSceneY;

            canvasSP.setHvalue(clamp01(panStartH - dx / denomX));
            canvasSP.setVvalue(clamp01(panStartV - dy / denomY));
            e.consume();
        });

        drawCanvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (panning && e.getButton() == panButton) {
                panning = false;
                drawCanvas.setCursor(spaceDown ? Cursor.OPEN_HAND : Cursor.DEFAULT);
                e.consume();
            }
        });

        // ===== Zoom / Pan handlers =====
        // Ctrl/Cmd + 휠(또는 트랙패드 스크롤) = 줌
        canvasSP.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() || e.isShortcutDown()) {
                double factor = (e.getDeltaY() > 0) ? 1.10 : (1.0 / 1.10);
                viewportController.zoomAt(factor, e.getSceneX(), e.getSceneY());
                e.consume();
            }
        });

        // 트랙패드 핀치 줌
        canvasSP.addEventFilter(ZoomEvent.ZOOM, e -> {
            viewportController.zoomAt(e.getZoomFactor(), e.getSceneX(), e.getSceneY());
            e.consume();
        });


        // 창 크기별 레전드 우하단 배치
        ChangeListener<Number> relayout = (obs, ov, nv) -> {
            double width = stage.getWidth();
            boolean tight = width < 980;
            if (tight) {
                if (!legendDockBar.isVisible()) {
                    legendDockBar.setVisible(true);
                    root.setBottom(legendDockBar);
                }
            } else {
                if (legendDockBar.isVisible()) {
                    legendDockBar.setVisible(false);
                    root.setBottom(null);
                }
            }
            redrawLegend();
        };
        stage.widthProperty().addListener(relayout);
        stage.heightProperty().addListener(relayout);
        relayout.changed(null, null, null);

        // 고정 파라미터 강제
        pathLossN.set(FIXED_PATH_LOSS_N);
        smoothRadiusPx.set(FIXED_SMOOTH_RADIUS_PX);
        stage.setTitle("Wi-Fi Heatmap");

        Scene scene = new Scene(root, 1200, 860);
        stage.setScene(scene);

// Space 누르는 동안 팬 모드
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                spaceDown = true;
                if (!panning) drawCanvas.setCursor(Cursor.OPEN_HAND);
                e.consume();
            }
        });
        scene.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                spaceDown = false;
                if (!panning) drawCanvas.setCursor(Cursor.DEFAULT);
                e.consume();
            }
        });

        stage.show();
        Platform.runLater(viewportController::centerViewport);
    }

    // ===== UI =====
    private ToolBar buildToolbar(Stage stage) {
        Button open = new Button("평면도 열기");
        styleFlatButton(open);
        open.setOnAction(e -> openFloorplan(stage));

        toolToggleGroup = new ToggleGroup();
        tScaleBtn = new ToggleButton("스케일");
        tAPBtn = new ToggleButton("AP배치");
        tWallBtn = new ToggleButton("벽그리기");

        tScaleBtn.setToggleGroup(toolToggleGroup);
        tAPBtn.setToggleGroup(toolToggleGroup);
        tWallBtn.setToggleGroup(toolToggleGroup);

        styleToggle(tScaleBtn);
        styleToggle(tAPBtn);
        styleToggle(tWallBtn);

        // 기본은 VIEW 모드: 아무 것도 선택하지 않음
        toolToggleGroup.selectToggle(null);
        tool.set(Tool.VIEW);

        toolToggleGroup.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null) tool.set(Tool.VIEW);
            else tool.set(nv == tScaleBtn ? Tool.SCALE : nv == tAPBtn ? Tool.AP : Tool.WALL);

            // 모드 전환 시 미리보기 상태 정리
            wallDrawController.clearInteractive();
            scaleController.clearInteractive();
            redraw();
        });

        Button gen = new Button("히트맵 생성");
        styleAccentButton(gen);
        gen.setOnAction(e -> generateHeatmapAsync());

        Button clear = new Button("히트맵 클리어");
        styleFlatButton(clear);
        clear.setOnAction(e -> {
            heatmapImage = null;
            redraw();
        });

        ToolBar bar = new ToolBar(open, new Separator(), tScaleBtn, tAPBtn, tWallBtn, new Separator(), gen, clear);
        bar.setStyle("-fx-background-color: " + BG_APP + "; -fx-border-color: " + BORDER_SOFT + "; -fx-border-width: 0 0 1 0; -fx-padding: 8 10;" );
        return bar;
    }

    // 좌측 패널 생성
    private VBox buildLeftPanel(Stage owner) {
        VBox box = new VBox(14);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: " + BG_PANEL + ";");

        // 스케일관련
        Label s1 = new Label("두 점 클릭 후 실제거리 입력");
        s1.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 12px;");
        TextField realM = new TextField();
        styleTextField(realM);
        realM.textProperty().bindBidirectional(calibRealMeters, new javafx.util.converter.NumberStringConverter());
        // 입력칸은 너무 길지 않게 (NetSpot 느낌)
        realM.setPrefWidth(150);
        realM.setMaxWidth(180);
        Label sNow = new Label();
        sNow.textProperty().bind(new SimpleStringProperty("현재 m/px: ").concat(scaleMPerPx.asString("%.5f")));
        sNow.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 12px;");
        Button resetPts = new Button("보정 점 초기화");
        styleFlatButton(resetPts);
        resetPts.setOnAction(e -> {
            scaleController.clear();
            redraw();
        });
        Button applyScale = new Button("적용");
        styleAccentButton(applyScale);
        applyScale.setOnAction(e -> {
            boolean ok = scaleController.applyFromCurrentPoints();
            if (ok) {
                new Alert(Alert.AlertType.INFORMATION,
                        String.format("스케일 적용 완료!\n1px = %.5f m", scaleMPerPx.get()),
                        ButtonType.OK).showAndWait();

                // 스케일 적용 후 VIEW 모드로 복귀
                tool.set(Tool.VIEW);
                if (toolToggleGroup != null) toolToggleGroup.selectToggle(null);

                if (heatmapImage != null) generateHeatmapAsync();
                redraw();
            } else {
                showError("두 점을 먼저 클릭하고, 실제거리(m)도 올바르게 입력하세요.");
            }
        });
        // 입력칸과 버튼을 한 줄에 배치

        // ===== 벽(재질 프리셋 + 기본 dB) =====
        Label wTitle = new Label("벽(수동 그리기)");

        ComboBox<WallMaterial> wallMat = new ComboBox<>();
        wallMat.getItems().setAll(WallMaterial.values());
        wallMat.setValue(defaultWallMaterial.get());
        // NetSpot-ish: flat control look
        wallMat.setStyle("-fx-background-color: " + BG_APP + "; -fx-border-color: " + BORDER_SOFT + "; -fx-border-radius: 8; -fx-background-radius: 8;");
        // 표시 텍스트: Name (2.4/5dB)
        wallMat.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(WallMaterial item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.labelWithAttn());
            }
        });
        wallMat.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(WallMaterial item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.labelWithAttn());
            }
        });
        installComboPopupStyle(wallMat);
        wallMat.setMaxWidth(Double.MAX_VALUE);

        Spinner<Double> wallDb = new Spinner<>(0.0, 40.0, defaultWallDb.get(), 0.5);
        wallDb.setEditable(true);
        styleSpinner(wallDb);
        wallDb.setMaxWidth(Double.MAX_VALUE);

        // ✅ 프리셋 선택 시 Spinner 변경 이벤트가 "사용자 수정"으로 오해되어 CUSTOM으로 튕기는 현상 방지:
        //    - 프리셋으로 Spinner 값을 세팅할 때는 Spinner 리스너를 잠깐 제거했다가 다시 붙임.
        Runnable applyPresetToSpinner = () -> {
            WallMaterial m = wallMat.getValue();
            if (m == null) return;

            // Spinner 리스너 잠시 제거
            if (wallDbListener != null) wallDb.valueProperty().removeListener(wallDbListener);

            double v = m.defaultAttenuationDb();
            defaultWallMaterial.set(m);
            defaultWallDb.set(v);
            wallDb.getValueFactory().setValue(v);

            // Spinner 리스너 복구
            if (wallDbListener != null) wallDb.valueProperty().addListener(wallDbListener);
        };

        wallMatListener = (ObservableValue<? extends WallMaterial> obs, WallMaterial ov, WallMaterial nv) -> {
            if (nv == null) return;
            applyPresetToSpinner.run();
        };
        wallMat.valueProperty().addListener(wallMatListener);

        wallDbListener = (obs, ov, nv) -> {
            if (nv == null) return;

            // 일단 값 반영
            defaultWallDb.set(nv);

            WallMaterial selected = wallMat.getValue();
            if (selected == null) selected = WallMaterial.CUSTOM;

            // ✅ 프리셋의 기본값과 동일하면: 프리셋 유지(= CUSTOM으로 바꾸지 않음)
            double presetDb = selected.defaultAttenuationDb();
            if (selected != WallMaterial.CUSTOM && Math.abs(nv - presetDb) < 1e-9) {
                defaultWallMaterial.set(selected);
                return;
            }

            // ✅ 여기까지 왔으면: 사용자가 프리셋 기본값과 다르게 바꾼 것 → CUSTOM 전환
            if (defaultWallMaterial.get() != WallMaterial.CUSTOM) {
                defaultWallMaterial.set(WallMaterial.CUSTOM);

                wallMat.valueProperty().removeListener(wallMatListener);
                wallMat.getSelectionModel().select(WallMaterial.CUSTOM);
                wallMat.valueProperty().addListener(wallMatListener);
            }
        };
        wallDb.valueProperty().addListener(wallDbListener);

        // 초기 1회 동기화
        applyPresetToSpinner.run();

        Label wlLbl = new Label("벽 목록 (더블클릭=편집)");
        wlLbl.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 12px;");
        ListView<Wall> wallList = new ListView<>(walls);
        wallList.setStyle("-fx-background-color: " + BG_APP + "; -fx-border-color: " + BORDER_SOFT + "; -fx-border-radius: 8; -fx-background-radius: 8;");
        wallList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Wall item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
                setPadding(new Insets(6, 8, 6, 8));
            }
        });

        wallList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Wall sel = wallList.getSelectionModel().getSelectedItem();
                if (sel != null) editWallDialog(sel);
            }
        });

        Button delWall = new Button("선택 벽 삭제");
        styleFlatButton(delWall);
        delWall.setOnAction(e -> {
            Wall w = wallList.getSelectionModel().getSelectedItem();
            if (w != null) {
                walls.remove(w);
                redraw();
                if (heatmapImage != null) generateHeatmapAsync();
            }
        });

        // AP 목록
        Label apLbl = new Label("AP 목록 (더블클릭=편집, ⌫=삭제)");
        apLbl.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 12px;");
        ListView<AP> apList = apController.buildApListView(owner);
        apList.setStyle("-fx-background-color: " + BG_APP + "; -fx-border-color: " + BORDER_SOFT + "; -fx-border-radius: 8; -fx-background-radius: 8;");

        // separator defaults are a bit strong; soften globally within this VBox
        box.getChildren().addListener((javafx.collections.ListChangeListener<Node>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (Node n : c.getAddedSubList()) {
                        if (n instanceof Separator sep) {
                            sep.setStyle("-fx-opacity: 0.55;");
                        }
                    }
                }
            }
        });

        // ----- 스케일 카드 폼 (입력 필드가 라벨 아래, 입력칸+버튼 한 줄) -----
        Label distLbl = new Label("두 점 실제거리(m)");
        distLbl.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 12px;");
        HBox scaleInputRow = new HBox(8, realM, applyScale);
        scaleInputRow.setAlignment(Pos.CENTER_LEFT);

        VBox scaleForm = new VBox(6, distLbl, scaleInputRow);

        VBox scaleCard = card(
                "스케일 보정",
                s1,
                scaleForm,
                resetPts,
                sNow
        );

        // ----- 벽 카드 폼 (입력 필드가 라벨 아래) -----
        Label matLbl = new Label("재질");
        matLbl.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 12px;");
        VBox matForm = new VBox(6, matLbl, wallMat);

        Label attLbl = new Label("기본 감쇠(dB)");
        attLbl.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 12px;");
        VBox attForm = new VBox(6, attLbl, wallDb);

        VBox wallCard = card(
                "벽(수동 그리기)",
                matForm,
                attForm,
                wlLbl,
                wallList,
                delWall
        );

        VBox apCard = card(
                "AP",
                apLbl,
                apList
        );

        // 카드 내부 입력 컨트롤들이 가로로 최대한 늘어나도록
        VBox.setVgrow(wallList, Priority.ALWAYS);
        VBox.setVgrow(apList, Priority.ALWAYS);

        box.getChildren().addAll(scaleCard, wallCard, apCard);
        return box;
    }

    // 벽 편집 다이얼로그 (재질 + dB) - 동일한 "CUSTOM 튕김" 방지 로직 포함
    private void editWallDialog(Wall w) {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("벽 편집");

        ComboBox<WallMaterial> mat = new ComboBox<>();
        mat.getItems().setAll(WallMaterial.values());
        mat.setValue(w.getMaterial());
        installComboPopupStyle(mat);

        Spinner<Double> db = new Spinner<>(0.0, 40.0, w.attenuationDb, 0.5);
        db.setEditable(true);

        final boolean[] syncing = {false};

        mat.valueProperty().addListener((obs, ov, nv) -> {
            if (nv == null) return;
            syncing[0] = true;
            try {
                w.setMaterial(nv);
                db.getValueFactory().setValue(w.attenuationDb);
            } finally {
                syncing[0] = false;
            }
        });

        db.valueProperty().addListener((obs, ov, nv) -> {
            if (nv == null) return;
            if (syncing[0]) return;

            WallMaterial selected = mat.getValue();
            if (selected == null) selected = WallMaterial.CUSTOM;

            // ✅ 프리셋 기본값이면 프리셋 유지
            if (selected != WallMaterial.CUSTOM && Math.abs(nv - selected.defaultAttenuationDb()) < 1e-9) {
                w.setMaterial(selected);
                return;
            }

            // ✅ 프리셋 기본값과 다르면 CUSTOM
            w.setAttenuationDb(nv);
            mat.getSelectionModel().select(WallMaterial.CUSTOM);
        });


        GridPane gp = new GridPane();
        gp.setHgap(10);
        gp.setVgap(10);
        gp.setPadding(new Insets(12));
        gp.add(new Label("재질"), 0, 0);
        gp.add(mat, 1, 0);
        gp.add(new Label("감쇠(dB)"), 0, 1);
        gp.add(db, 1, 1);

        dlg.getDialogPane().setContent(gp);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        dlg.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                redraw();
                if (heatmapImage != null) generateHeatmapAsync();
            }
            return null;
        });

        dlg.showAndWait();
    }

    // 평면도 이미지 로드
    private void openFloorplan(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            floorplanBI = ImageIO.read(f);
            if (floorplanBI == null) throw new IOException("이미지 로드 실패");
            floorplan.set(SwingFXUtils.toFXImage(floorplanBI, null));
            heatmapImage = null;
            scaleController.clear();
            walls.clear();
            if (Double.isNaN(scaleMPerPx.get())) scaleMPerPx.set(0.05);
        } catch (Exception ex) {
            showError("이미지 로드 실패: " + ex.getMessage());
        }
    }


    // ===== 한 점에서의 RSSI 샘플링 =====
    private double sampleHybridRSSI(int px, int py) {
        if (Double.isNaN(scaleMPerPx.get()) || aps.isEmpty()) return Double.NaN;
        syncEnvParams();
        return env.sampleRssiAt(px, py);
    }

    // 히트맵 생성
    private void generateHeatmapAsync() {
        if (floorplan.get() == null) {
            showError("평면도를 먼저 로드하세요.");
            return;
        }
        if (Double.isNaN(scaleMPerPx.get())) {
            showError("스케일 보정이 필요합니다.");
            return;
        }
        if (aps.isEmpty()) {
            showError("AP를 하나 이상 배치하세요.");
            return;
        }

        final int step = gridStepPx.get();
        final int w = (int) floorplan.get().getWidth();
        final int h = (int) floorplan.get().getHeight();

        // 환경 파라미터 최신화
        syncEnvParams();
        HeatmapGenerator generator = new HeatmapGenerator(env);

        CompletableFuture
                .supplyAsync(() -> generator.generate(
                        w,
                        h,
                        step,
                        legendMin.get(),
                        legendMax.get(),
                        smoothRadiusPx.get()
                ), exec)
                .thenAccept(img -> Platform.runLater(() -> {
                    heatmapImage = img;
                    redraw();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> showError("히트맵 실패: " + ex.getMessage()));
                    return null;
                });
    }

    // ===== 그리기 =====
    private void redraw() {
        if (g == null || drawCanvas == null) return;
        g.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
        if (heatmapImage != null) g.drawImage(heatmapImage, 0, 0);

        // 벽(재질별 색상 + 미리보기)
        wallDrawController.draw(g, walls, tool.get() == Tool.WALL);

        // AP
        for (AP ap : aps) {
            if (!ap.enabled) continue;
            double r = 6;
            g.setFill(Color.DODGERBLUE);
            g.fillOval(ap.x - r, ap.y - r, 2 * r, 2 * r);
            g.setStroke(Color.WHITE);
            g.setLineWidth(2.0);
            g.strokeOval(ap.x - r, ap.y - r, 2 * r, 2 * r);
            g.setFill(Color.BLACK);
            g.fillText(ap.name, ap.x + r + 4, ap.y - r - 2);
        }

        // 스케일(선/점 + 미리보기)
        scaleController.draw(g, tool.get() == Tool.SCALE);

        redrawLegend();
    }

    // ===== Legend =====
    private void redrawLegend() {
        updateLegendCanvas(legendCanvas, legendLabel);
        updateLegendCanvas(legendCanvasDock, legendLabelDock);
    }

    private void updateLegendCanvas(Canvas canvas, Label label) {
        if (canvas == null || label == null) return;
        GraphicsContext lg = canvas.getGraphicsContext2D();
        lg.clearRect(0, 0, LEGEND_W, LEGEND_H);
        for (int x = 0; x < LEGEND_W; x++) {
            double t = (double) x / (LEGEND_W - 1);
            double v = legendMin.get() + t * (legendMax.get() - legendMin.get());
            Color c = WifiMath.rssiToColor(v, legendMin.get(), legendMax.get());
            lg.setStroke(c);
            lg.strokeLine(x, 0, x, LEGEND_H);
        }
        lg.setStroke(Color.gray(0, 0.6));
        lg.strokeRect(0.5, 0.5, LEGEND_W - 1, LEGEND_H - 1);

        double v = hoverRssiStrongest.get();
        if (!Double.isNaN(v)) {
            double t = (v - legendMin.get()) / (legendMax.get() - legendMin.get());
            t = Math.max(0, Math.min(1, t));
            double x = t * (LEGEND_W - 1);
            lg.setStroke(Color.BLACK);
            lg.setLineWidth(1.5);
            lg.strokeLine(x, 0, x, LEGEND_H);
            double y = LEGEND_H;
            lg.setFill(Color.BLACK);
            lg.fillPolygon(new double[]{x - 5, x + 5, x}, new double[]{y + 6, y + 6, y + 1}, 3);
        }

        String left = String.format("%.0f dBm", legendMin.get());
        String mid = String.format("%.0f dBm", (legendMin.get() + legendMax.get()) / 2.0);
        String right = String.format("%.0f dBm", legendMax.get());
        label.setText(
                (Double.isNaN(v) ? "—" : String.format("%.1f dBm", v))
                        + "   |   " + left + "     " + mid + "     " + right
        );
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ===== Viewport Controller (Zoom/Pan) =====

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }

    @Override
    public void stop() {
        exec.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}