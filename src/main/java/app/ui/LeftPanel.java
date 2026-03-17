package app.ui;
import app.model.AP;
import app.model.AppState;
import app.model.Band;
import app.model.RadioConfig;
import app.model.RssiResult;
import app.model.Wall;
import app.model.WallMaterial;
import app.model.WifiEnvironment;
import javafx.geometry.Insets;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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

    // 스케일 입력
    private final VBox scaleCard;
    private final TextField realMetersField = new TextField("5");
    private final Button applyScaleBtn = new Button("적용");
    private final Button resetScaleBtn = new Button("보정 점 초기화");

    // AP 상세 편집
    private final VBox apDetailsCard;
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

    private final Button applyApBtn = new Button("Apply");
    private final Button deleteApBtn = new Button("Delete");

    // Wall 상세 편집
    private final VBox wallDetailsCard;
    private final ComboBox<WallMaterial> wallMaterialCombo = new ComboBox<>();
    private final TextField wall24Field = new TextField();
    private final TextField wall5Field = new TextField();
    private final Button applyWallBtn = new Button("Apply");
    private final Button deleteWallBtn = new Button("Delete");
    private final VBox wallListCard;
    private final ListView<Wall> wallListView = new ListView<>();

    // Solver 패널
    private final VBox solverCard;
    private final ComboBox<Integer> solverCellCombo = createIntCombo(2, 3, 4, 6, 8, 10);
    private final ComboBox<Integer> solverSubStepsCombo = createIntCombo(1, 2, 4, 6, 8);
    private final ComboBox<Integer> solverRenderSkipCombo = createIntCombo(1, 2, 3, 4, 5);
    private final ComboBox<String> solverBandCombo = new ComboBox<>();
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

    private WifiEnvironment envRef;
    private AP currentAp = null;
    private Wall currentWall = null;
    private boolean syncingWallSelection = false;
    private boolean solverToolActive = false;
    private boolean scaleVisible = false;

    public LeftPanel() {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: " + Styles.BG_PANEL + ";");

        // --- Scale card ---
        Label hint = new Label("두 점 클릭 후 실제거리 입력");
        hint.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 12px;");

        Label distLbl = new Label("두 점 실제거리(m)");
        distLbl.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 12px;");

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
                "RSSI (Mouse)",
                rssiRows
        );
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
        styleTextCombo(solverBandCombo);

        solverOverlayCheck.setSelected(true);
        solverOverlayCheck.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 12px;");
        solverStateLabel.setStyle("-fx-text-fill: " + Styles.TEXT_MAIN + "; -fx-font-size: 12px; -fx-font-weight: 600;");
        solverStatsLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");
        solverDebugLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 10px; -fx-font-family: 'Menlo';");
        solverDebugLabel.setWrapText(true);

        solverBandCombo.getItems().setAll("All (2.4/5/6)", "2.4GHz", "5GHz", "6GHz");
        solverBandCombo.getSelectionModel().selectFirst();
        solverCellCombo.getSelectionModel().select(Integer.valueOf(4));
        solverSubStepsCombo.getSelectionModel().select(Integer.valueOf(2));
        solverRenderSkipCombo.getSelectionModel().select(Integer.valueOf(2));

        solverBandCombo.valueProperty().addListener((o, ov, nv) -> onSolverConfigChanged.run());
        solverOverlayCheck.selectedProperty().addListener((o, ov, nv) -> onSolverOverlayChanged.run());

        solverCard = Styles.card(
                "Solver",
                new Label("모델: 2D 파동 Solver 오버레이"),
                new Label("표시: Power (time-avg |u|^2)"),
                new VBox(4, new Label("표시 밴드"), solverBandCombo),
                new Label("격자 크기(px): 4 (고정)"),
                new Label("Sub-steps/frame: 자동"),
                new Label("Render every N frame: 자동"),
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

        Label coordTitle = new Label("좌표(px)");
        coordTitle.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 12px;");
        apCoordLabel.setStyle("-fx-text-fill: " + Styles.TEXT_MAIN + "; -fx-font-size: 12px;");

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
        bandSwitcher.setStyle(
                "-fx-background-color: #EEF1F5;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 4;"
        );

        applyBandTabStyle(t24, true);
        applyBandTabStyle(t5, false);
        applyBandTabStyle(t6, false);

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
        apDetailsCard = Styles.card(
                "AP Details",
                new VBox(6, new Label("이름"), apNameField),
                apEnabledCheck,
                new VBox(4, new Label("AP 높이(m)"), apHeightField),
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
        wallMaterialCombo.setStyle(
                "-fx-background-color: " + Styles.BG_APP + ";" +
                "-fx-border-color: " + Styles.BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-font-size: 12px;"
        );
        Styles.installComboPopupStyle(wallMaterialCombo);
        wallMaterialCombo.valueProperty().addListener((o, ov, nv) -> {
            if (nv == null) return;
            if (nv != WallMaterial.CUSTOM) {
                wall24Field.setText(String.format("%.1f", nv.defaultAttenuation24Db()));
                wall5Field.setText(String.format("%.1f", nv.defaultAttenuation5Db()));
            }
        });

        wallDetailsCard = Styles.card(
                "Wall Details",
                new VBox(4, new Label("재질"), wallMaterialCombo),
                new VBox(4, new Label("2.4GHz 감쇠(dB)"), wall24Field),
                new VBox(4, new Label("5GHz 감쇠(dB)"), wall5Field),
                new HBox(8, applyWallBtn, deleteWallBtn)
        );
        applyWallBtn.setOnAction(e -> applyWallEdits());
        deleteWallBtn.setOnAction(e -> deleteCurrentWall());

        wallListView.setPrefHeight(160);
        wallListView.setFocusTraversable(false);
        wallListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        wallListView.setPlaceholder(styledPlaceholder("벽이 없습니다."));
        wallListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Wall item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String label = item.getMaterial() == null ? "벽" : item.getMaterial().labelKo();
                setText(String.format("벽 %d - %s", getIndex() + 1, label));
            }
        });
        wallListView.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (syncingWallSelection) return;
            setSelectedWall(nv);
            onSelectWall.accept(nv);
        });
        wallListView.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-border-color: " + Styles.BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;"
        );

        wallListCard = Styles.card(
                "Walls",
                wallListView
        );

        root.getChildren().addAll(rssiCard, scaleCard, solverCard, apDetailsCard, wallListCard, wallDetailsCard);
        setScaleVisible(false);
        setSolverToolActive(false);
        setSelectedAp(null);
        setSelectedWall(null);
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
            wallMaterialCombo.getSelectionModel().select(WallMaterial.CONCRETE_WALL);
            wall24Field.clear();
            wall5Field.clear();
            selectWallInList(null);
        } else {
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

    public Band getSolverDisplayBand() {
        String v = solverBandCombo.getValue();
        if (v == null || v.startsWith("All")) return null;
        if (v.startsWith("2.4")) return Band.GHZ_24;
        if (v.startsWith("5")) return Band.GHZ_5;
        if (v.startsWith("6")) return Band.GHZ_6;
        return null;
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
            setCardVisible(apDetailsCard, false);
            setCardVisible(wallListCard, false);
            setCardVisible(wallDetailsCard, false);
            return;
        }

        setCardVisible(solverCard, false);
        setCardVisible(rssiCard, true);
        setCardVisible(scaleCard, scaleVisible);
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
        Label t = new Label(title);
        t.setStyle("-fx-text-fill: " + Styles.TEXT_MAIN + "; -fx-font-size: 12px; -fx-font-weight: 600;");
        Label ssidLabel = new Label("SSID");
        ssidLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");
        Label txLabel = new Label("Tx(dBm, 고정 17)");
        txLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");
        Label gainLabel = new Label("Gain(dBi)");
        gainLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");
        Label chLabel = new Label("Channel");
        chLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");
        Label bwLabel = new Label("Bandwidth(MHz)");
        bwLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");

        enabled.setSelected(true);

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

    private static void styleIntCombo(ComboBox<Integer> cb) {
        cb.setMinWidth(0);
        cb.setPrefWidth(120);
        cb.setMaxWidth(Double.MAX_VALUE);
        cb.setStyle(
                "-fx-background-color: " + Styles.BG_APP + ";" +
                "-fx-text-fill: " + Styles.TEXT_MAIN + ";" +
                "-fx-border-color: " + Styles.BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 1 2;" +
                "-fx-font-size: 12px;"
        );
        Styles.installComboPopupStyle(cb);
    }

    private static void styleTextCombo(ComboBox<String> cb) {
        cb.setMinWidth(0);
        cb.setPrefWidth(160);
        cb.setMaxWidth(Double.MAX_VALUE);
        cb.setStyle(
                "-fx-background-color: " + Styles.BG_APP + ";" +
                "-fx-text-fill: " + Styles.TEXT_MAIN + ";" +
                "-fx-border-color: " + Styles.BORDER_SOFT + ";" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 1 2;" +
                "-fx-font-size: 12px;"
        );
        Styles.installComboPopupStyle(cb);
    }

    private static void styleChannelCombo(ComboBox<Integer> cb, Band band) {
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
                        ? "-fx-background-color: " + Styles.ACCENT + "; -fx-text-fill: white; -fx-border-color: transparent;"
                        : "-fx-background-color: transparent; -fx-text-fill: " + Styles.TEXT_MAIN + "; -fx-border-color: transparent;")
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
        new Alert(Alert.AlertType.INFORMATION, "적용되었습니다!", ButtonType.OK).showAndWait();
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

        WallMaterial selected = wallMaterialCombo.getValue();
        if (selected == null) selected = currentWall.getMaterial();

        if (selected != null && selected != WallMaterial.CUSTOM) {
            // 프리셋 재질 선택 시에는 프리셋 재질 상태를 유지한다.
            currentWall.setMaterial(selected);
        } else {
            double a24 = parseDoubleOr(wall24Field.getText(), currentWall.attenuationDb24);
            double a5 = parseDoubleOr(wall5Field.getText(), currentWall.attenuationDb5);
            currentWall.setAttenuationDb(a24, a5);
        }

        onWallChanged.run();
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
        rssiRows.getChildren().clear();

        if (rows.isEmpty()) {
            Label empty = new Label("마우스를 캔버스 위로 올리면 SSID, 주파수, RSSI가 표시됩니다.");
            empty.setWrapText(true);
            empty.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");
            rssiRows.getChildren().add(empty);
            return;
        }

        List<RssiResult> sorted = rows.stream()
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
        left.setStyle("-fx-text-fill: " + Styles.TEXT_MAIN + "; -fx-font-size: 12px;");

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label right = new Label(formatDbm(dbm));
        right.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");

        HBox row = new HBox(8, left, spacer, right);
        row.setStyle(
                "-fx-alignment: center-left;" +
                "-fx-padding: 5 8;" +
                "-fx-background-color: #ECECEF;" +
                "-fx-background-radius: 8;"
        );
        return row;
    }

    private static String formatDbm(double dbm) {
        if (!Double.isFinite(dbm)) return "-";
        return String.format("%.1f dBm", dbm);
    }

    private HBox createRssiApHeaderRow(String apName) {
        Label left = new Label(apName);
        left.setStyle("-fx-text-fill: " + Styles.TEXT_MAIN + "; -fx-font-size: 12px; -fx-font-weight: 600;");

        HBox row = new HBox(left);
        row.setStyle(
                "-fx-alignment: center-left;" +
                "-fx-padding: 5 8;" +
                "-fx-background-color: #D9D9DD;" +
                "-fx-background-radius: 8;"
        );
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
        label.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");
        return label;
    }
}
