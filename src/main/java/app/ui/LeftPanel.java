package app.ui;
import app.model.AP;
import app.model.ApPreset;
import app.model.AppState;
import app.model.Band;
import app.model.RadioConfig;
import app.model.RssiResult;
import app.model.Wall;
import app.model.WallMaterial;
import app.model.WifiEnvironment;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TextField;
import javafx.scene.control.Separator;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.util.StringConverter;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;

import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class LeftPanel {

    private final VBox root = new VBox(14);

    // RSSI 표시
    private final VBox rssiCard;
    private final VBox rssiRows = new VBox(4);

    // 결과 보기 카드 — 큰 헤드라인 + 비율 색바 + 범례 + 코칭 메시지
    // 색바 순서는 좌→우 "약함→강함" — BottomBar legend와 동일 방향
    private final VBox resultCard;
    private final Label resultHeadlineLabel = new Label("—");       // 큰 %
    private final Label resultHeadlineSubLabel = new Label("");      // "신호 양호 공간 비율"
    private final Label resultCoachLabel = new Label("");            // 색바 아래 코칭 메시지
    private final GridPane resultBarRow = new GridPane();            // 4단계 비율 색바 (색만)
    private final StackPane[] resultBarCells = new StackPane[4];     // 약함/주의/양호/강함
    private final ColumnConstraints[] resultBarCols = new ColumnConstraints[4]; // 칸별 비율(%)
    private final FlowPane resultLegend = new FlowPane(12, 4);       // 색바 아래 범례
    private final HBox[]  resultLegendItems  = new HBox[4];          // 범례 항목 (점+텍스트)
    private final Label[] resultLegendLabels = new Label[4];         // 범례 항목 텍스트

    // 스케일 입력
    private final VBox scaleCard;
    private final TextField realMetersField = new TextField("5");
    private final Button applyScaleBtn = new Button("적용");
    private final Button resetScaleBtn = new Button("보정 점 초기화");

    // AP 상세 편집
    private final VBox apDetailsCard;
    private final ComboBox<ApPreset> apPresetCombo = new ComboBox<>();
    private java.util.function.Consumer<ApPreset> onApPresetChanged;
    private final TextField apNameField = new TextField();
    private final CheckBox apEnabledCheck = new CheckBox("AP 활성화");
    private final TextField apHeightField = new TextField("1.0");
    private final Label apCoordLabel = new Label("-");

    private final TextField ssid24Field = new TextField();
    private final Spinner<Double> tx24Spinner = createDoubleSpinner(0.0, 40.0, RadioConfig.FIXED_TX_POWER_DBM, 1.0);
    private final Spinner<Double> gain24Spinner = createDoubleSpinner(0.0, 15.0, RadioConfig.DEFAULT_ANTENNA_GAIN_DBI, 0.5);
    private final ComboBox<Integer> ch24Combo = createIntCombo(channels24());
    private final ComboBox<Integer> bw24Combo = createIntCombo(20, 40);
    private final CheckBox enabled24Check = new CheckBox("활성화");

    private final TextField ssid5Field = new TextField();
    private final Spinner<Double> tx5Spinner = createDoubleSpinner(0.0, 40.0, RadioConfig.FIXED_TX_POWER_DBM, 1.0);
    private final Spinner<Double> gain5Spinner = createDoubleSpinner(0.0, 15.0, RadioConfig.DEFAULT_ANTENNA_GAIN_DBI, 0.5);
    private final ComboBox<Integer> ch5Combo = createIntCombo(channels5());
    private final ComboBox<Integer> bw5Combo = createIntCombo(20, 40, 80, 160);
    private final CheckBox enabled5Check = new CheckBox("활성화");

    private final TextField ssid6Field = new TextField();
    private final Spinner<Double> tx6Spinner = createDoubleSpinner(0.0, 40.0, RadioConfig.FIXED_TX_POWER_DBM, 1.0);
    private final Spinner<Double> gain6Spinner = createDoubleSpinner(0.0, 15.0, RadioConfig.DEFAULT_ANTENNA_GAIN_DBI, 0.5);
    private final ComboBox<Integer> ch6Combo = createIntCombo(channels6());
    private final ComboBox<Integer> bw6Combo = createIntCombo(20, 40, 80, 160, 320);
    private final CheckBox enabled6Check = new CheckBox("활성화");

    private final Button applyApBtn = new Button("적용");
    private final Button deleteApBtn = new Button("삭제");

    // Wall 상세 편집
    private final VBox wallDetailsCard;
    private final TextField wallNameField = new TextField();
    private final Label wallInfoLabel = new Label("-");
    private final ComboBox<WallMaterial> wallMaterialCombo = new ComboBox<>();
    private final TextField wall24Field = new TextField();
    private final TextField wall5Field = new TextField();
    private final Button applyWallBtn = new Button("적용");
    private final Button deleteWallBtn = new Button("삭제");
    private final VBox wallListCard;
    private final ListView<Wall> wallListView = new ListView<>();

    // 벽 그리기 완료 → AP 추천 진입 (WALL 모드에서만 노출)
    private VBox wallDoneCard;
    private final Button wallDoneBtn = new Button("벽 그리기 완료 → AP 추천");
    private Runnable onWallDone = () -> {};

    // Solver 패널
    private final VBox solverCard;
    private final ComboBox<Integer> solverCellCombo = createIntCombo(2, 3, 4, 6, 8, 10);
    private final ComboBox<Integer> solverSubStepsCombo = createIntCombo(1, 2, 4, 6, 8);
    private final ComboBox<Integer> solverRenderSkipCombo = createIntCombo(1, 2, 3, 4, 5);
    private final CheckBox solverOverlayCheck = new CheckBox("오버레이 표시");

    private final Label solverStateLabel = new Label("대기");
    private final Label solverStatsLabel = new Label("step 0 / time 0 ns / fps -");
    private final Label solverDebugLabel = new Label("debug: -");

    // 외부에서 주입받을 핸들러(컨트롤러가 연결)
    private Runnable onApplyScale = () -> {};
    private Runnable onResetScale = () -> {};
    private Runnable onApChanged = () -> {};
    private Runnable onClearApSelection = () -> {};
    private Runnable onWallChanged = () -> {};
    private Runnable onClearWallSelection = () -> {};
    private Runnable onSolverConfigChanged = () -> {};
    private Runnable onSolverOverlayChanged = () -> {};
    private Consumer<Wall> onSelectWall = (w) -> {};

    private List<RssiResult> lastRssiResults = List.of();

    private WifiEnvironment envRef;
    private AP currentAp = null;
    private Wall currentWall = null;
    private boolean syncingWallSelection = false;
    private boolean solverToolActive = false;
    private boolean scaleVisible = false;
    private boolean wallToolActive = false;

    public LeftPanel() {
        root.setPadding(new Insets(10));
        Themed.bind(root, () -> "-fx-background-color: " + Styles.bgPanel() + ";");

        // --- Scale card ---
        Label hint = Themed.bind(new Label("두 점 클릭 후 실제거리 입력"),
                () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 12px;");

        Label distLbl = Themed.bind(new Label("두 점 실제거리(m)"),
                () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 12px;");

        Styles.styleTextField(realMetersField);
        realMetersField.setPrefWidth(150);

        Styles.styleAccentButton(applyScaleBtn);
        applyScaleBtn.setOnAction(e -> onApplyScale.run());

        Styles.styleFlatButton(resetScaleBtn);
        resetScaleBtn.setOnAction(e -> onResetScale.run());

        HBox row = new HBox(8, realMetersField, applyScaleBtn);
        row.setFillHeight(true);
        HBox.setHgrow(realMetersField, Priority.NEVER);

        rssiCard = Styles.card(
                "신호 강도 (마우스 위치)",
                rssiRows
        );

        // ── 결과 보기 카드 ──────────────────────────────────────────────
        resultCard = buildResultCard();
        rssiRows.setPadding(new Insets(2, 0, 0, 0));
        updateRssiRows(List.of());

        scaleCard = Styles.card(
                "스케일 보정",
                hint,
                new VBox(6, distLbl, row),
                resetScaleBtn
        );

        styleIntCombo(solverCellCombo);
        styleIntCombo(solverSubStepsCombo);
        styleIntCombo(solverRenderSkipCombo);

        solverOverlayCheck.setSelected(true);
        Themed.bind(solverOverlayCheck, () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 12px;");
        Themed.bind(solverStateLabel,  () -> "-fx-text-fill: " + Styles.textMain() + "; -fx-font-size: 12px; -fx-font-weight: 600;");
        Themed.bind(solverStatsLabel,  () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 11px;");
        Themed.bind(solverDebugLabel,  () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 10px; -fx-font-family: 'Menlo';");
        solverDebugLabel.setWrapText(true);

        solverCellCombo.getSelectionModel().select(Integer.valueOf(4));
        solverSubStepsCombo.getSelectionModel().select(Integer.valueOf(2));
        solverRenderSkipCombo.getSelectionModel().select(Integer.valueOf(2));

        solverOverlayCheck.selectedProperty().addListener((o, ov, nv) -> onSolverOverlayChanged.run());

        // 솔버 카드 정적 레이블들 (간결한 설명)
        Label[] solverInfoLabels = {
            new Label("전파 흐름 보기 (실시간 파동 시뮬레이션)"),
            new Label("화면 위에 전파의 강약을 색으로 표시")
        };
        for (Label lbl : solverInfoLabels) {
            Themed.bind(lbl, () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 11px;");
        }

        solverCard = Styles.card(
                "전파 흐름",
                solverInfoLabels[0],
                solverInfoLabels[1],
                solverOverlayCheck,
                solverStateLabel,
                solverStatsLabel,
                solverDebugLabel
        );

        Styles.styleTextField(apNameField);
        Styles.styleTextField(apHeightField);
        Styles.styleTextField(ssid24Field);
        Styles.styleTextField(ssid5Field);
        Styles.styleTextField(ssid6Field);
        Styles.styleSpinner(tx24Spinner);
        Styles.styleSpinner(gain24Spinner);
        styleChannelCombo(ch24Combo, Band.GHZ_24);
        styleIntCombo(bw24Combo);
        Styles.styleSpinner(tx5Spinner);
        Styles.styleSpinner(gain5Spinner);
        styleChannelCombo(ch5Combo, Band.GHZ_5);
        styleIntCombo(bw5Combo);
        Styles.styleSpinner(tx6Spinner);
        Styles.styleSpinner(gain6Spinner);
        styleChannelCombo(ch6Combo, Band.GHZ_6);
        styleIntCombo(bw6Combo);
        Styles.styleAccentButton(applyApBtn);
        Styles.styleFlatButton(deleteApBtn);
        Styles.styleTextField(wall24Field);
        Styles.styleTextField(wall5Field);
        Styles.styleAccentButton(applyWallBtn);
        Styles.styleFlatButton(deleteWallBtn);

        Label coordTitle = Themed.bind(new Label("좌표(px)"),
                () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 12px;");
        Themed.bind(apCoordLabel,
                () -> "-fx-text-fill: " + Styles.textMain() + "; -fx-font-size: 12px;");

        VBox band24 = createBandEditor("2.4 GHz", enabled24Check, ssid24Field, tx24Spinner, gain24Spinner, ch24Combo, bw24Combo);
        VBox band5 = createBandEditor("5 GHz", enabled5Check, ssid5Field, tx5Spinner, gain5Spinner, ch5Combo, bw5Combo);
        VBox band6 = createBandEditor("6 GHz", enabled6Check, ssid6Field, tx6Spinner, gain6Spinner, ch6Combo, bw6Combo);

        ToggleButton t24 = new ToggleButton("2.4GHz");
        ToggleButton t5 = new ToggleButton("5GHz");
        ToggleButton t6 = new ToggleButton("6GHz");
        t24.setMaxWidth(Double.MAX_VALUE);
        t5.setMaxWidth(Double.MAX_VALUE);
        t6.setMaxWidth(Double.MAX_VALUE);
        ToggleGroup tg = new ToggleGroup();
        t24.setToggleGroup(tg);
        t5.setToggleGroup(tg);
        t6.setToggleGroup(tg);
        t24.setSelected(true);

        HBox bandSwitcher = new HBox(6, t24, t5, t6);
        HBox.setHgrow(t24, Priority.ALWAYS);
        HBox.setHgrow(t5, Priority.ALWAYS);
        HBox.setHgrow(t6, Priority.ALWAYS);
        Themed.bind(bandSwitcher, () ->
                "-fx-background-color: " + Styles.bgTabBar() + ";" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 4;");

        applyBandTabStyle(t24, true);
        applyBandTabStyle(t5, false);
        applyBandTabStyle(t6, false);
        Styles.addThemeListener(() -> {
            applyBandTabStyle(t24, t24.isSelected());
            applyBandTabStyle(t5, t5.isSelected());
            applyBandTabStyle(t6, t6.isSelected());
        });

        StackPane bandContent = new StackPane(band24, band5, band6);
        setBandPaneVisible(band24, true);
        setBandPaneVisible(band5, false);
        setBandPaneVisible(band6, false);

        tg.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null) {
                t24.setSelected(true);
                return;
            }

            applyBandTabStyle(t24, t24.isSelected());
            applyBandTabStyle(t5, t5.isSelected());
            applyBandTabStyle(t6, t6.isSelected());

            setBandPaneVisible(band24, nv == t24);
            setBandPaneVisible(band5, nv == t5);
            setBandPaneVisible(band6, nv == t6);
        });

        HBox apButtons = new HBox(8, applyApBtn, deleteApBtn);

        // AP 카드 레이블 + CheckBox 테마 리스너
        Label presetLbl = new Label("공유기 프리셋");
        Label apNameLbl = new Label("이름");
        Label apHeightLbl = new Label("AP 높이(m)");
        for (Label lbl : new Label[]{presetLbl, apNameLbl, apHeightLbl}) {
            Themed.bind(lbl, () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 11px;");
        }
        Themed.bind(apEnabledCheck,
                () -> "-fx-text-fill: " + Styles.textMain() + "; -fx-font-size: 12px;");

        // 공유기 프리셋 ComboBox
        apPresetCombo.getItems().addAll(ApPreset.values());
        apPresetCombo.setValue(ApPreset.CUSTOM);
        apPresetCombo.setStyle(Styles.comboBase());
        apPresetCombo.setMaxWidth(Double.MAX_VALUE);
        Styles.addThemeListener(() -> apPresetCombo.setStyle(Styles.comboBase()));
        Styles.installComboPopupStyle(apPresetCombo);
        apPresetCombo.setOnAction(e -> {
            ApPreset preset = apPresetCombo.getValue();
            if (preset != null && preset != ApPreset.CUSTOM && onApPresetChanged != null) {
                onApPresetChanged.accept(preset);
            }
        });

        apDetailsCard = Styles.card(
                "AP Details",
                new VBox(6, presetLbl, apPresetCombo),
                new VBox(6, apNameLbl, apNameField),
                apEnabledCheck,
                new VBox(4, apHeightLbl, apHeightField),
                new VBox(4, coordTitle, apCoordLabel),
                bandSwitcher,
                bandContent,
                apButtons
        );

        applyApBtn.setOnAction(e -> applyApEdits());
        deleteApBtn.setOnAction(e -> deleteCurrentAp());

        wallMaterialCombo.getItems().addAll(WallMaterial.values());
        wallMaterialCombo.setConverter(new StringConverter<>() {
            @Override public String toString(WallMaterial object) {
                return object == null ? "" : object.labelWithAttn();
            }
            @Override public WallMaterial fromString(String string) {
                return null;
            }
        });
        wallMaterialCombo.getSelectionModel().select(WallMaterial.CONCRETE_WALL);
        wallMaterialCombo.setMaxWidth(Double.MAX_VALUE);
        Runnable applyWallMatCombo = () -> wallMaterialCombo.setStyle(Styles.comboBase());
        applyWallMatCombo.run();
        Styles.addThemeListener(applyWallMatCombo);
        Styles.installComboPopupStyle(wallMaterialCombo);
        wallMaterialCombo.valueProperty().addListener((o, ov, nv) -> {
            if (nv == null) return;
            if (nv != WallMaterial.CUSTOM) {
                wall24Field.setText(String.format("%.1f", nv.defaultAttenuation24Db()));
                wall5Field.setText(String.format("%.1f", nv.defaultAttenuation5Db()));
            }
        });

        Label wallNameLbl = new Label("이름 (선택)");
        Label wallInfoLbl = new Label("위치/길이");
        Label wallMatLbl  = new Label("재질");
        Label wall24Lbl   = new Label("2.4GHz 감쇠(dB)");
        Label wall5Lbl    = new Label("5GHz 감쇠(dB)");
        for (Label lbl : new Label[]{wallNameLbl, wallInfoLbl, wallMatLbl, wall24Lbl, wall5Lbl}) {
            Themed.bind(lbl, () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 11px;");
        }
        Styles.styleTextField(wallNameField);
        wallNameField.setPromptText("예) 외벽, 침실 경계");

        Themed.bind(wallInfoLabel,
                () -> "-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 11px; -fx-font-family: monospace;");
        wallInfoLabel.setWrapText(true);

        wallDetailsCard = Styles.card(
                "Wall Details",
                new VBox(4, wallNameLbl, wallNameField),
                new VBox(4, wallInfoLbl, wallInfoLabel),
                new Separator(),
                new VBox(4, wallMatLbl, wallMaterialCombo),
                new VBox(4, wall24Lbl, wall24Field),
                new VBox(4, wall5Lbl, wall5Field),
                new HBox(8, applyWallBtn, deleteWallBtn)
        );
        applyWallBtn.setOnAction(e -> applyWallEdits());
        deleteWallBtn.setOnAction(e -> deleteCurrentWall());

        wallListView.setPrefHeight(160);
        wallListView.setFocusTraversable(false);
        wallListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        wallListView.setPlaceholder(styledPlaceholder("벽이 없습니다."));
        wallListView.setCellFactory(lv -> new ListCell<>() {
            {
                // hover + selection → restyle the cell
                hoverProperty().addListener((o, ov, nv) -> restyle());
                selectedProperty().addListener((o, ov, nv) -> restyle());
                Styles.addThemeListener(this::restyle);
            }
            @Override
            protected void updateItem(Wall item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color:transparent;");
                    return;
                }
                String matLabel = item.getMaterial() == null ? "벽" : item.getMaterial().labelKo();
                String name = (item.label != null && !item.label.isBlank()) ? item.label : null;
                String display = name != null
                        ? String.format("%d. %s  –  %s", getIndex() + 1, name, matLabel)
                        : String.format("벽 %d  –  %s", getIndex() + 1, matLabel);
                setText(display);
                restyle();
            }
            private void restyle() {
                boolean sel = isSelected();
                boolean hov = isHover();
                String bg;
                String fg;
                if (sel) {
                    bg = Styles.isDark() ? "rgba(10,132,255,0.22)" : "rgba(0,122,255,0.13)";
                    fg = Styles.accent();
                } else if (hov) {
                    bg = Styles.isDark() ? "rgba(255,255,255,0.07)" : "rgba(0,0,0,0.05)";
                    fg = Styles.textMain();
                } else {
                    bg = "transparent";
                    fg = Styles.textMain();
                }
                setStyle(
                    "-fx-background-color:" + bg + ";" +
                    "-fx-text-fill:" + fg + ";" +
                    "-fx-background-radius:8;" +
                    "-fx-padding:6 10;" +
                    "-fx-font-size:12px;" +
                    "-fx-font-family:" + Styles.FONT_STACK + ";"
                );
            }
        });
        wallListView.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (syncingWallSelection) return;
            setSelectedWall(nv);
            onSelectWall.accept(nv);
        });
        // glass container: transparent bg, no border (CSS handles it)
        Themed.bind(wallListView, () ->
                "-fx-background-color:transparent;" +
                "-fx-border-color:transparent;");

        wallListCard = Styles.card(
                "벽 목록",
                wallListView
        );

        // ── 벽 그리기 완료 버튼 카드 (WALL 모드에서만 노출) ──────────────
        Styles.styleAccentButton(wallDoneBtn);
        wallDoneBtn.setMaxWidth(Double.MAX_VALUE);
        wallDoneBtn.setOnAction(e -> onWallDone.run());
        Label wallDoneHint = subLbl("벽을 다 그렸다면 눌러서 와이파이 쓸 공간을 고르고 공유기 위치를 추천받으세요.");
        wallDoneHint.setWrapText(true);
        wallDoneCard = Styles.card("다음 단계", wallDoneBtn, wallDoneHint);

        root.getChildren().addAll(resultCard, rssiCard, scaleCard, wallDoneCard, solverCard, apDetailsCard, wallListCard, wallDetailsCard);
        Styles.addThemeListener(() -> updateRssiRows(lastRssiResults));
        setScaleVisible(false);
        setSolverToolActive(false);
        setSelectedAp(null);
        setSelectedWall(null);
        setCardVisible(resultCard, false);  // 결과 생기기 전까지 숨김
    }

    /**
     * 결과 보기 카드 — 일반 사용자도 한눈에 이해할 수 있도록 단순화.
     *
     * <p>레이아웃:
     * <pre>
     *   ┌ 측정 결과 ────────────────────────────────────┐
     *   │ 36%                                          │  ← 큰 헤드라인 (양호+ 비율)
     *   │ 신호가 양호한 공간 비율                        │
     *   │                                              │
     *   │ [▓▓▓▓▓▓▓▓▓░░░░░░] 비율 색바 (좌→우 약→강)     │  ← 색만 표시
     *   │ ● 약함 46%  ● 주의 18%  ● 양호 24%  ● 강함 12%│  ← 범례 (색점+이름+%)
     *   │                                              │
     *   │ 신호가 약한 곳이 많습니다. 공유기를 추가하거나   │  ← 코칭 메시지 (wrap)
     *   │ 옮겨보세요.                                   │
     *   └──────────────────────────────────────────────┘
     * </pre>
     *
     * <p>색상은 히트맵 컬러맵({@code WifiMath.rssiToColor})과 일관:
     * <ul>
     *   <li>약함 → 파랑 (#2D6CF6)</li>
     *   <li>주의 → 녹색 (#2FD44A)</li>
     *   <li>양호 → 노랑 (#F6D32D)</li>
     *   <li>강함 → 주황 (#F0632D)</li>
     * </ul>
     */
    private VBox buildResultCard() {
        // ── 큰 헤드라인 ─────────────────────────────────────────────
        Themed.bind(resultHeadlineLabel, () ->
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:" + Styles.FONT_SIZE_DISPLAY + "px;" +
                "-fx-font-weight:bold;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");

        Themed.bind(resultHeadlineSubLabel, () ->
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:" + Styles.FONT_SIZE_SUB + "px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        resultHeadlineSubLabel.setWrapText(true);

        VBox headlineBox = new VBox(2, resultHeadlineLabel, resultHeadlineSubLabel);

        // ── 비율 색바 (좌→우 약→강, 히트맵 색상과 일관) — 색만 표시 ──
        // 수치/이름은 아래 범례에서 읽히므로, 막대는 비율을 색 면적으로만 전달
        String[] gradeNames  = {"약함", "주의", "양호", "강함"};
        String[] gradeColors = Styles.rssiColorsBlueToRed();  // [dead, weak, good, strong]

        // GridPane + ColumnConstraints.percentWidth → 실제 폭과 무관하게 비율 정확
        resultBarRow.setMaxWidth(Double.MAX_VALUE);
        for (int i = 0; i < 4; i++) {
            StackPane cell = new StackPane();
            cell.setStyle("-fx-background-color:" + gradeColors[i] + ";");
            cell.setPrefHeight(18);
            cell.setMinWidth(0);
            cell.setMaxWidth(Double.MAX_VALUE);
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(25);  // setMeasurementResult에서 실제 비율로 갱신
            resultBarCells[i] = cell;
            resultBarCols[i]  = cc;
            resultBarRow.getColumnConstraints().add(cc);
            resultBarRow.add(cell, i, 0);
        }
        // 좌우 끝 둥근 모서리
        resultBarCells[0].setStyle(resultBarCells[0].getStyle()
                + "-fx-background-radius: 6 0 0 6;");
        resultBarCells[3].setStyle(resultBarCells[3].getStyle()
                + "-fx-background-radius: 0 6 6 0;");

        // ── 색바 아래 범례 (색점 + 이름 + %) ─────────────────────────
        for (int i = 0; i < 4; i++) {
            Region dot = new Region();
            dot.setMinSize(9, 9);
            dot.setPrefSize(9, 9);
            dot.setMaxSize(9, 9);
            dot.setStyle("-fx-background-color:" + gradeColors[i] + ";"
                    + "-fx-background-radius: 3;");
            Label txt = new Label(gradeNames[i]);
            Themed.bind(txt, () ->
                    "-fx-text-fill:" + Styles.textSub() + ";" +
                    "-fx-font-size:11px;" +
                    "-fx-font-family:" + Styles.FONT_STACK + ";");
            HBox item = new HBox(5, dot, txt);
            item.setAlignment(Pos.CENTER_LEFT);
            resultLegendLabels[i] = txt;
            resultLegendItems[i]  = item;
            resultLegend.getChildren().add(item);
        }

        // ── 색바 아래 코칭 메시지 (두 줄까지 wrap) ────────────────────
        resultCoachLabel.setWrapText(true);
        Themed.bind(resultCoachLabel, () ->
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:" + Styles.FONT_SIZE_SUB + "px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");

        return Styles.card(
                "측정 결과",
                headlineBox,
                new VBox(6, resultBarRow, resultLegend),
                resultCoachLabel
        );
    }

    private Label subLbl(String t) {
        return Themed.subLabel(t);
    }

    /**
     * 결과 카드에 측정 수치를 표시한다. 히트맵 생성 후 호출.
     *
     * @param coveragePercent  신호가 양호한(≥ −65 dBm) 영역 비율 (%)
     * @param gradeFractions   [약함, 주의, 양호, 강함] 비율 (합=1.0). 좌→우(약→강) 순서.
     */
    public void setMeasurementResult(double coveragePercent, double[] gradeFractions) {
        // 안내 상태에서 숨겼던 요소 복원
        setNodeShown(resultHeadlineLabel, true);
        setNodeShown(resultHeadlineSubLabel, true);
        setNodeShown(resultBarRow, true);
        setNodeShown(resultLegend, true);

        resultHeadlineLabel.setText(String.format("%.0f%%", coveragePercent));
        resultHeadlineSubLabel.setText("신호가 양호한 공간 비율");

        // 코칭 메시지 — 비율에 따라 자연어로 (색바 아래에 별도 라인으로 wrap 표시)
        String coachMsg;
        if (coveragePercent >= 80) {
            coachMsg = "신호 분포가 좋습니다. 현재 공유기 배치로 충분합니다.";
        } else if (coveragePercent >= 50) {
            coachMsg = "공간의 절반 정도만 신호가 양호합니다. 공유기 위치를 조정해 보세요.";
        } else {
            coachMsg = "신호가 약한 곳이 많습니다. 공유기를 추가하거나 다른 위치로 옮겨 보세요.";
        }
        resultCoachLabel.setText(coachMsg);

        if (gradeFractions != null && gradeFractions.length >= 4) {
            String[] names = {"약함", "주의", "양호", "강함"};
            for (int i = 0; i < 4; i++) {
                StackPane cell = resultBarCells[i];
                double frac = Math.max(0.0, gradeFractions[i]);
                int pct = (int) Math.round(frac * 100.0);

                // 비율이 거의 0이면 색바 셀 + 범례 항목 모두 숨김 (노이즈 제거)
                boolean shown = frac > 0.005;
                cell.setVisible(shown);
                cell.setManaged(shown);
                resultLegendItems[i].setVisible(shown);
                resultLegendItems[i].setManaged(shown);

                // 색바 칸 폭 = 비율 (percentWidth는 실제 폭과 무관하게 정확)
                resultBarCols[i].setPercentWidth(frac * 100.0);

                if (!shown) continue;
                // 수치는 범례에서만 표시 — 색바는 비율 면적만 전달
                resultLegendLabels[i].setText(names[i] + " " + pct + "%");
            }
        }
        setCardVisible(resultCard, true);
    }

    /** 결과 데이터를 지운다. 클리어 또는 환경 변경 시 호출. */
    public void clearMeasurementResult() {
        resultHeadlineLabel.setText("—");
        resultHeadlineSubLabel.setText("");
        resultCoachLabel.setText("");
        setCardVisible(resultCard, false);
    }

    /**
     * 사용 공간이 지정되지 않았을 때 — 수치/색바 대신 안내 메시지만 표시한다.
     * 측정 영역 없이 비율을 보여주면 오해를 부르므로 결과는 숨긴다.
     */
    public void showMeasurementGuidance(String msg) {
        setNodeShown(resultHeadlineLabel, false);
        setNodeShown(resultHeadlineSubLabel, false);
        setNodeShown(resultBarRow, false);
        setNodeShown(resultLegend, false);
        resultCoachLabel.setText(msg);
        setCardVisible(resultCard, true);
    }

    private static void setNodeShown(javafx.scene.Node n, boolean shown) {
        n.setVisible(shown);
        n.setManaged(shown);
    }

    public Parent getRoot() {
        return root;
    }

    /**
     * MainController에서 상태/환경과 연결해줄 때 사용
     * - 지금 단계에선 스케일 UI만 연결해둠
     */
    public void bind(AppState state,
                     WifiEnvironment env,
                     Runnable applyScale,
                     Runnable resetScale) {
        bind(state, env, applyScale, resetScale, null, null, null, null);
    }

    public void bind(AppState state,
                     WifiEnvironment env,
                     Runnable applyScale,
                     Runnable resetScale,
                     Runnable apChanged,
                     Runnable clearApSelection) {
        bind(state, env, applyScale, resetScale, apChanged, clearApSelection, null, null);
    }

    public void bind(AppState state,
                     WifiEnvironment env,
                     Runnable applyScale,
                     Runnable resetScale,
                     Runnable apChanged,
                     Runnable clearApSelection,
                     Runnable wallChanged,
                     Runnable clearWallSelection) {

        if (applyScale != null) this.onApplyScale = applyScale;
        if (resetScale != null) this.onResetScale = resetScale;
        if (apChanged != null) this.onApChanged = apChanged;
        if (clearApSelection != null) this.onClearApSelection = clearApSelection;
        if (wallChanged != null) this.onWallChanged = wallChanged;
        if (clearWallSelection != null) this.onClearWallSelection = clearWallSelection;
        this.envRef = env;

        // 숫자 입력값을 state에 반영 (엔터/포커스아웃 때도 반영하고 싶으면 리스너 추가하면 됨)
        realMetersField.textProperty().addListener((o, ov, nv) -> {
            try {
                double v = Double.parseDouble(nv.trim());
                state.setCalibRealMeters(v);
            } catch (Exception ignored) {
                // 입력 중엔 무시
            }
        });

        // 초기값도 state에 1회 반영
        try {
            state.setCalibRealMeters(Double.parseDouble(realMetersField.getText().trim()));
        } catch (Exception ignored) {}
    }

    public void setSelectedAp(AP ap) {
        if (ap == currentAp && ap != null) {
            apCoordLabel.setText(String.format("%.1f, %.1f", ap.x, ap.y));
            return;
        }

        currentAp = ap;
        apPresetCombo.setValue(ApPreset.CUSTOM);
        refreshApFields();
    }

    /** 현재 선택된 AP의 필드를 강제로 다시 채운다 (프리셋 적용 후 갱신용). */
    public void refreshApFields() {
        boolean hasSelection = (currentAp != null);

        applyApBtn.setDisable(!hasSelection);
        deleteApBtn.setDisable(!hasSelection);

        if (!hasSelection) {
            apNameField.clear();
            apEnabledCheck.setSelected(false);
            apHeightField.setText("1.0");
            apCoordLabel.setText("-");
            fillBandFields(Band.GHZ_24, null);
            fillBandFields(Band.GHZ_5, null);
            fillBandFields(Band.GHZ_6, null);
        } else {
            apNameField.setText(currentAp.name == null ? "" : currentAp.name);
            apEnabledCheck.setSelected(currentAp.enabled);
            apHeightField.setText(String.format("%.2f", currentAp.heightM));
            apCoordLabel.setText(String.format("%.1f, %.1f", currentAp.x, currentAp.y));
            fillBandFields(Band.GHZ_24, currentAp.radios.get(Band.GHZ_24));
            fillBandFields(Band.GHZ_5, currentAp.radios.get(Band.GHZ_5));
            fillBandFields(Band.GHZ_6, currentAp.radios.get(Band.GHZ_6));
        }
        applySectionVisibility();
    }

    public void setSelectedWall(Wall wall) {
        if (wall == currentWall && wall != null) return;

        currentWall = wall;
        boolean hasSelection = currentWall != null;
        applyWallBtn.setDisable(!hasSelection);
        deleteWallBtn.setDisable(!hasSelection);

        if (!hasSelection) {
            wallNameField.clear();
            wallInfoLabel.setText("-");
            wallMaterialCombo.getSelectionModel().select(WallMaterial.CONCRETE_WALL);
            wall24Field.clear();
            wall5Field.clear();
            selectWallInList(null);
        } else {
            wallNameField.setText(currentWall.label == null ? "" : currentWall.label);
            wallInfoLabel.setText(buildWallInfoText(currentWall));
            wallMaterialCombo.getSelectionModel().select(currentWall.getMaterial());
            wall24Field.setText(String.format("%.1f", currentWall.attenuationDb24));
            wall5Field.setText(String.format("%.1f", currentWall.attenuationDb5));
            selectWallInList(currentWall);
        }
        applySectionVisibility();
    }

    public void setWalls(List<Wall> walls) {
        syncingWallSelection = true;
        wallListView.getItems().setAll(walls == null ? List.of() : walls);
        syncingWallSelection = false;

        if (currentWall != null && !wallListView.getItems().contains(currentWall)) {
            currentWall = null;
            setSelectedWall(null);
            onClearWallSelection.run();
            return;
        }
        selectWallInList(currentWall);
    }

    public void setOnSelectWall(Consumer<Wall> onSelectWall) {
        if (onSelectWall != null) this.onSelectWall = onSelectWall;
    }

    public TextField getRealMetersField() {
        return realMetersField;
    }

    public void setScaleVisible(boolean visible) {
        this.scaleVisible = visible;
        applySectionVisibility();
    }

    public void setWallToolActive(boolean active) {
        this.wallToolActive = active;
        applySectionVisibility();
    }

    public void setOnWallDone(Runnable r) {
        if (r != null) this.onWallDone = r;
    }

    public void setSolverToolActive(boolean active) {
        this.solverToolActive = active;
        applySectionVisibility();
    }

    public int getSolverCellPx() {
        return 4;
    }

    public int getSolverSubSteps() {
        return 2;
    }

    public int getSolverRenderSkip() {
        return 2;
    }

    public boolean isSolverOverlayEnabled() {
        return solverOverlayCheck.isSelected();
    }

    public void setSolverStatus(boolean running, long step, double timeNs, double fps) {
        solverStateLabel.setText(running ? "실행중" : "대기");
        String fpsTxt = Double.isFinite(fps) && fps > 0.0 ? String.format("%.1f", fps) : "-";
        solverStatsLabel.setText(String.format("step %d / time %.0f ns / fps %s", step, timeNs, fpsTxt));
    }

    public void setSolverDebug(String debugText) {
        String txt = (debugText == null || debugText.isBlank()) ? "debug: -" : debugText;
        solverDebugLabel.setText(txt);
    }

    public void setOnSolverConfigChanged(Runnable onSolverConfigChanged) {
        if (onSolverConfigChanged != null) this.onSolverConfigChanged = onSolverConfigChanged;
    }

    public void setOnSolverOverlayChanged(Runnable onSolverOverlayChanged) {
        if (onSolverOverlayChanged != null) this.onSolverOverlayChanged = onSolverOverlayChanged;
    }

    public void setRssiResults(List<RssiResult> rows) {
        updateRssiRows(rows == null ? List.of() : rows);
    }

    public void setOnApPresetChanged(java.util.function.Consumer<ApPreset> c) {
        this.onApPresetChanged = c;
    }

    public boolean isShowPathsEnabled() {
        return false;
    }

    public void setOnShowPathsChanged(Runnable onShowPathsChanged) {
        // 전파 경로 표시 기능 제거: no-op
    }

    private void applySectionVisibility() {
        if (solverToolActive) {
            setCardVisible(solverCard, true);
            setCardVisible(rssiCard, false);
            setCardVisible(scaleCard, false);
            setCardVisible(wallDoneCard, false);
            setCardVisible(apDetailsCard, false);
            setCardVisible(wallListCard, false);
            setCardVisible(wallDetailsCard, false);
            return;
        }

        setCardVisible(solverCard, false);
        setCardVisible(rssiCard, true);
        setCardVisible(scaleCard, scaleVisible);
        setCardVisible(wallDoneCard, wallToolActive);
        setCardVisible(apDetailsCard, currentAp != null);
        setCardVisible(wallListCard, true);
        setCardVisible(wallDetailsCard, currentWall != null);
    }

    private static void setCardVisible(VBox card, boolean visible) {
        if (card == null) return;
        card.setVisible(visible);
        card.setManaged(visible);
    }

    private VBox createBandEditor(String title,
                                  CheckBox enabled,
                                  TextField ssid,
                                  Spinner<Double> tx,
                                  Spinner<Double> gain,
                                  ComboBox<Integer> channel,
                                  ComboBox<Integer> bandwidth) {
        Label t = Themed.bind(new Label(title),
                () -> "-fx-text-fill: " + Styles.textMain() + "; -fx-font-size: 12px; -fx-font-weight: 600;");
        Label ssidLabel = Themed.subLabel("SSID");
        Label txLabel   = Themed.subLabel("Tx(dBm, 고정 17)");
        Label gainLabel = Themed.subLabel("Gain(dBi)");
        Label chLabel   = Themed.subLabel("Channel");
        Label bwLabel   = Themed.subLabel("Bandwidth(MHz)");

        enabled.setSelected(true);
        Themed.bind(enabled, () -> "-fx-text-fill: " + Styles.textMain() + "; -fx-font-size: 12px;");

        ssid.setMaxWidth(Double.MAX_VALUE);
        tx.setMaxWidth(Double.MAX_VALUE);
        tx.setDisable(true);
        tx.setEditable(false);
        gain.setMaxWidth(Double.MAX_VALUE);
        channel.setMaxWidth(Double.MAX_VALUE);
        bandwidth.setMaxWidth(Double.MAX_VALUE);

        HBox head = new HBox(8, t, enabled);
        head.setFillHeight(true);

        VBox txCol = new VBox(4, txLabel, tx);
        VBox gainCol = new VBox(4, gainLabel, gain);
        HBox.setHgrow(txCol, Priority.ALWAYS);
        HBox.setHgrow(gainCol, Priority.ALWAYS);
        HBox row = new HBox(8, txCol, gainCol);

        VBox chCol = new VBox(4, chLabel, channel);
        VBox bwCol = new VBox(4, bwLabel, bandwidth);
        HBox.setHgrow(chCol, Priority.ALWAYS);
        HBox.setHgrow(bwCol, Priority.ALWAYS);
        HBox row2 = new HBox(8, chCol, bwCol);

        return new VBox(
                6,
                head,
                ssidLabel, ssid,
                row,
                row2
        );
    }

    private static Spinner<Double> createDoubleSpinner(double min, double max, double initial, double step) {
        Spinner<Double> sp = new Spinner<>(min, max, initial, step);
        sp.setEditable(true);
        return sp;
    }

    private static ComboBox<Integer> createIntCombo(Integer... values) {
        ComboBox<Integer> cb = new ComboBox<>();
        cb.getItems().addAll(values);
        if (!cb.getItems().isEmpty()) cb.getSelectionModel().selectFirst();
        return cb;
    }

    private void styleIntCombo(ComboBox<Integer> cb) {
        cb.setMinWidth(0);
        cb.setPrefWidth(120);
        cb.setMaxWidth(Double.MAX_VALUE);
        Runnable apply = () -> cb.setStyle(Styles.comboBase());
        apply.run();
        if (Styles.registerOnce(cb)) Styles.addThemeListener(apply);
        Styles.installComboPopupStyle(cb);
    }

    private void styleTextCombo(ComboBox<String> cb) {
        cb.setMinWidth(0);
        cb.setPrefWidth(160);
        cb.setMaxWidth(Double.MAX_VALUE);
        Runnable apply = () -> cb.setStyle(Styles.comboBase());
        apply.run();
        if (Styles.registerOnce(cb)) Styles.addThemeListener(apply);
        Styles.installComboPopupStyle(cb);
    }

    private void styleChannelCombo(ComboBox<Integer> cb, Band band) {
        styleIntCombo(cb);

        StringConverter<Integer> converter = new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                if (value == null) return "";
                double ghz = RadioConfig.centerFreqGhz(band, value);
                return String.format("ch%d = %.3fGHz", value, ghz);
            }

            @Override
            public Integer fromString(String string) {
                if (string == null) return null;
                String digits = string.replaceAll("[^0-9]", "");
                if (digits.isEmpty()) return null;
                try { return Integer.parseInt(digits); }
                catch (Exception ignored) { return null; }
            }
        };

        cb.setConverter(converter);
        cb.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : converter.toString(item));
            }
        });
        cb.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : converter.toString(item));
            }
        });
    }

    private static Integer[] channels24() {
        Integer[] arr = new Integer[14];
        for (int i = 0; i < 14; i++) arr[i] = i + 1;
        return arr;
    }

    private static Integer[] channels5() {
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
        for (int ch = 36; ch <= 64; ch += 4) list.add(ch);
        for (int ch = 100; ch <= 144; ch += 4) list.add(ch);
        for (int ch = 149; ch <= 177; ch += 4) list.add(ch);
        return list.toArray(new Integer[0]);
    }

    private static Integer[] channels6() {
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
        for (int ch = 1; ch <= 233; ch += 4) list.add(ch);
        return list.toArray(new Integer[0]);
    }

    private static void applyBandTabStyle(ToggleButton b, boolean selected) {
        b.setMinWidth(70);
        b.setStyle(
                "-fx-font-size: 12px;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 4 10;" +
                (selected
                        ? "-fx-background-color: " + Styles.accent() + "; -fx-text-fill: white; -fx-border-color: transparent;"
                        : "-fx-background-color: transparent; -fx-text-fill: " + Styles.textMain() + "; -fx-border-color: transparent;")
        );
    }

    private static void setBandPaneVisible(VBox pane, boolean visible) {
        pane.setVisible(visible);
        pane.setManaged(visible);
    }

    private void applyApEdits() {
        if (currentAp == null) return;

        currentAp.name = safeName(apNameField.getText(), currentAp.name);
        currentAp.enabled = apEnabledCheck.isSelected();
        currentAp.heightM = Math.max(0.1, parseDoubleOr(apHeightField.getText(), currentAp.heightM));

        writeBand(Band.GHZ_24, ssid24Field, tx24Spinner, gain24Spinner);
        writeBand(Band.GHZ_5, ssid5Field, tx5Spinner, gain5Spinner);
        writeBand(Band.GHZ_6, ssid6Field, tx6Spinner, gain6Spinner);

        currentAp = null;
        setSelectedAp(null);
        onClearApSelection.run();
        onApChanged.run();
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "적용되었습니다!", ButtonType.OK);
        Styles.styleAlert(alert);
        alert.showAndWait();
    }

    private void deleteCurrentAp() {
        if (currentAp == null || envRef == null) return;

        envRef.getAps().remove(currentAp);
        currentAp = null;
        setSelectedAp(null);
        onClearApSelection.run();
        onApChanged.run();
    }

    private void applyWallEdits() {
        if (currentWall == null) return;

        // 이름 저장
        currentWall.label = wallNameField.getText() == null ? "" : wallNameField.getText().trim();

        WallMaterial selected = wallMaterialCombo.getValue();
        if (selected == null) selected = currentWall.getMaterial();

        if (selected != null && selected != WallMaterial.CUSTOM) {
            currentWall.setMaterial(selected);
        } else {
            double a24 = parseDoubleOr(wall24Field.getText(), currentWall.attenuationDb24);
            double a5 = parseDoubleOr(wall5Field.getText(), currentWall.attenuationDb5);
            currentWall.setAttenuationDb(a24, a5);
        }

        // 리스트 셀 갱신 (이름 변경 반영)
        wallListView.refresh();
        onWallChanged.run();
    }

    /** 벽의 좌표/길이 정보 문자열을 생성한다. */
    private String buildWallInfoText(Wall w) {
        double scale = (envRef != null && Double.isFinite(envRef.getScaleMPerPx()) && envRef.getScaleMPerPx() > 0)
                ? envRef.getScaleMPerPx() : 0.0;
        if (scale <= 0) {
            // 스케일 미설정: 픽셀 단위 표시
            double lenPx = Math.hypot(w.x2 - w.x1, w.y2 - w.y1);
            return String.format("시작  (%.0f, %.0f) px%n끝    (%.0f, %.0f) px%n길이  %.0f px",
                    w.x1, w.y1, w.x2, w.y2, lenPx);
        }
        double x1m = w.x1 * scale, y1m = w.y1 * scale;
        double x2m = w.x2 * scale, y2m = w.y2 * scale;
        double lenM = Math.hypot(w.x2 - w.x1, w.y2 - w.y1) * scale;
        return String.format("시작  (%.2f, %.2f) m%n끝    (%.2f, %.2f) m%n길이  %.2f m",
                x1m, y1m, x2m, y2m, lenM);
    }

    private void deleteCurrentWall() {
        if (currentWall == null || envRef == null) return;
        envRef.getWalls().remove(currentWall);
        currentWall = null;
        setSelectedWall(null);
        onClearWallSelection.run();
        onWallChanged.run();
    }

    private void selectWallInList(Wall wall) {
        syncingWallSelection = true;
        if (wall == null) {
            wallListView.getSelectionModel().clearSelection();
        } else {
            wallListView.getSelectionModel().select(wall);
        }
        syncingWallSelection = false;
    }

    private void fillBandFields(Band band, RadioConfig rc) {
        TextField ssid = getSsidField(band);
        Spinner<Double> tx = getTxSpinner(band);
        Spinner<Double> gain = getGainSpinner(band);
        ComboBox<Integer> channel = getChannelCombo(band);
        ComboBox<Integer> bandwidth = getBandwidthCombo(band);
        CheckBox enabled = getEnabledCheck(band);

        if (rc == null) {
            ssid.clear();
            tx.getValueFactory().setValue(RadioConfig.FIXED_TX_POWER_DBM);
            gain.getValueFactory().setValue(RadioConfig.DEFAULT_ANTENNA_GAIN_DBI);
            selectComboValue(channel, defaultChannel(band));
            selectComboValue(bandwidth, defaultBandwidth(band));
            enabled.setSelected(false);
            return;
        }

        enabled.setSelected(rc.enabled);
        ssid.setText(rc.ssid == null ? "" : rc.ssid);
        tx.getValueFactory().setValue(RadioConfig.FIXED_TX_POWER_DBM);
        gain.getValueFactory().setValue(rc.antennaGain);
        selectComboValue(channel, rc.channel);
        selectComboValue(bandwidth, rc.channelWidth);
    }

    private void writeBand(Band band, TextField ssidField, Spinner<Double> txSpinner, Spinner<Double> gainSpinner) {
        RadioConfig rc = currentAp.radios.get(band);
        if (rc == null) return;

        rc.enabled = getEnabledCheck(band).isSelected();
        rc.ssid = ssidField.getText() == null ? "" : ssidField.getText().trim();
        rc.txPowerDbm = RadioConfig.FIXED_TX_POWER_DBM;
        rc.antennaGain = spinnerValue(gainSpinner);
        rc.channel = comboValue(getChannelCombo(band), defaultChannel(band));
        rc.channelWidth = comboValue(getBandwidthCombo(band), defaultBandwidth(band));
    }

    private static double spinnerValue(Spinner<Double> spinner) {
        commitSpinner(spinner);
        Double value = spinner.getValue();
        return value == null ? 0.0 : value;
    }

    private static <T> void commitSpinner(Spinner<T> spinner) {
        if (!spinner.isEditable()) return;
        String text = spinner.getEditor().getText();
        SpinnerValueFactory<T> vf = spinner.getValueFactory();
        if (vf == null) return;
        try {
            if (vf instanceof SpinnerValueFactory.DoubleSpinnerValueFactory df) {
                df.setValue(Double.parseDouble(text));
            } else if (vf instanceof SpinnerValueFactory.IntegerSpinnerValueFactory inf) {
                inf.setValue(Integer.parseInt(text));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static String safeName(String input, String fallback) {
        if (input == null) return fallback;
        String trimmed = input.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private TextField getSsidField(Band band) {
        return switch (band) {
            case GHZ_24 -> ssid24Field;
            case GHZ_5 -> ssid5Field;
            case GHZ_6 -> ssid6Field;
        };
    }

    private Spinner<Double> getTxSpinner(Band band) {
        return switch (band) {
            case GHZ_24 -> tx24Spinner;
            case GHZ_5 -> tx5Spinner;
            case GHZ_6 -> tx6Spinner;
        };
    }

    private Spinner<Double> getGainSpinner(Band band) {
        return switch (band) {
            case GHZ_24 -> gain24Spinner;
            case GHZ_5 -> gain5Spinner;
            case GHZ_6 -> gain6Spinner;
        };
    }

    private ComboBox<Integer> getChannelCombo(Band band) {
        return switch (band) {
            case GHZ_24 -> ch24Combo;
            case GHZ_5 -> ch5Combo;
            case GHZ_6 -> ch6Combo;
        };
    }

    private ComboBox<Integer> getBandwidthCombo(Band band) {
        return switch (band) {
            case GHZ_24 -> bw24Combo;
            case GHZ_5 -> bw5Combo;
            case GHZ_6 -> bw6Combo;
        };
    }

    private CheckBox getEnabledCheck(Band band) {
        return switch (band) {
            case GHZ_24 -> enabled24Check;
            case GHZ_5 -> enabled5Check;
            case GHZ_6 -> enabled6Check;
        };
    }

    private static int defaultChannel(Band band) {
        return switch (band) {
            case GHZ_24 -> 1;
            case GHZ_5 -> 36;
            case GHZ_6 -> 1;
        };
    }

    private static int defaultBandwidth(Band band) {
        return switch (band) {
            case GHZ_24 -> 20;
            case GHZ_5, GHZ_6 -> 80;
        };
    }

    private static void selectComboValue(ComboBox<Integer> cb, int value) {
        if (!cb.getItems().contains(value)) {
            cb.getItems().add(value);
        }
        cb.getSelectionModel().select(Integer.valueOf(value));
    }

    private static int comboValue(ComboBox<Integer> cb, int fallback) {
        Integer v = cb.getValue();
        return (v == null) ? fallback : v;
    }

    private static double parseDoubleOr(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int parseIntOr(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void updateRssiRows(List<RssiResult> rows) {
        lastRssiResults = (rows == null) ? List.of() : rows;
        rssiRows.getChildren().clear();

        if (lastRssiResults.isEmpty()) {
            Label empty = new Label("마우스를 캔버스 위로 올리면 SSID, 주파수, RSSI가 표시됩니다.");
            empty.setWrapText(true);
            empty.setStyle("-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 11px;");
            rssiRows.getChildren().add(empty);
            return;
        }

        List<RssiResult> sorted = lastRssiResults.stream()
                .sorted(Comparator
                        .comparing((RssiResult r) -> safeApName(r.apName))
                        .thenComparingInt(r -> bandOrder(r.band))
                        .thenComparing(Comparator.comparingDouble((RssiResult r) -> r.rssiDbm).reversed()))
                .limit(10)
                .toList();

        Map<String, List<RssiResult>> grouped = new LinkedHashMap<>();
        for (RssiResult r : sorted) {
            grouped.computeIfAbsent(safeApName(r.apName), k -> new java.util.ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<RssiResult>> e : grouped.entrySet()) {
            rssiRows.getChildren().add(createRssiApHeaderRow(e.getKey()));
            for (RssiResult r : e.getValue()) {
                rssiRows.getChildren().add(createRssiRow(safe(r.ssid) + " / " + r.band.label, r.rssiDbm));
            }
        }
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "(SSID 없음)" : s;
    }

    private static String safeApName(String s) {
        return (s == null || s.isBlank()) ? "AP" : s;
    }

    private HBox createRssiRow(String leftText, double dbm) {
        Label left = new Label(leftText);
        left.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:12px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label right = new Label(formatDbm(dbm));
        right.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");

        HBox row = new HBox(8, left, spacer, right);
        row.setStyle(
                "-fx-alignment:center-left;" +
                "-fx-padding:5 10;" +
                "-fx-background-color:" + Styles.bgRow() + ";" +
                "-fx-background-radius:9;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:9;" +
                "-fx-border-width:0.5;");
        return row;
    }

    private static String formatDbm(double dbm) {
        if (!Double.isFinite(dbm)) return "-";
        return String.format("%.1f dBm", dbm);
    }

    private HBox createRssiApHeaderRow(String apName) {
        Label left = new Label(apName);
        left.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:12px;" +
                "-fx-font-weight:700;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");

        HBox row = new HBox(left);
        row.setStyle(
                "-fx-alignment:center-left;" +
                "-fx-padding:5 10;" +
                "-fx-background-color:" + Styles.bgRowHdr() + ";" +
                "-fx-background-radius:9;" +
                "-fx-border-color:" + Styles.borderSoft() + ";" +
                "-fx-border-radius:9;" +
                "-fx-border-width:0.5;");
        return row;
    }

    private static int bandOrder(Band band) {
        return switch (band) {
            case GHZ_24 -> 0;
            case GHZ_5 -> 1;
            case GHZ_6 -> 2;
        };
    }

    private static Label styledPlaceholder(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: " + Styles.textSub() + "; -fx-font-size: 11px;");
        return label;
    }
}
