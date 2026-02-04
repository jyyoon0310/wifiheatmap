package app.ui;

import app.model.AP;
import app.model.AppState;
import app.model.Band;
import app.model.RadioConfig;
import app.model.WifiEnvironment;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class LeftPanel {

    private final VBox root = new VBox(14);

    // 스케일 입력
    private final TextField realMetersField = new TextField("5");
    private final Button applyScaleBtn = new Button("적용");
    private final Button resetScaleBtn = new Button("보정 점 초기화");

    // AP 상세 편집
    private final VBox apDetailsCard;
    private final TextField apNameField = new TextField();
    private final CheckBox apEnabledCheck = new CheckBox("AP 활성화");
    private final Label apCoordLabel = new Label("-");

    private final TextField ssid24Field = new TextField();
    private final Spinner<Double> tx24Spinner = createDoubleSpinner(0.0, 40.0, 18.0, 1.0);
    private final Spinner<Double> gain24Spinner = createDoubleSpinner(0.0, 15.0, 2.0, 0.5);

    private final TextField ssid5Field = new TextField();
    private final Spinner<Double> tx5Spinner = createDoubleSpinner(0.0, 40.0, 18.0, 1.0);
    private final Spinner<Double> gain5Spinner = createDoubleSpinner(0.0, 15.0, 2.0, 0.5);

    private final TextField ssid6Field = new TextField();
    private final Spinner<Double> tx6Spinner = createDoubleSpinner(0.0, 40.0, 18.0, 1.0);
    private final Spinner<Double> gain6Spinner = createDoubleSpinner(0.0, 15.0, 2.0, 0.5);

    private final Button applyApBtn = new Button("Apply");
    private final Button deleteApBtn = new Button("Delete");

    // 외부에서 주입받을 핸들러(컨트롤러가 연결)
    private Runnable onApplyScale = () -> {};
    private Runnable onResetScale = () -> {};
    private Runnable onApChanged = () -> {};
    private Runnable onClearApSelection = () -> {};

    private WifiEnvironment envRef;
    private AP currentAp = null;

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

        VBox scaleCard = Styles.card(
                "스케일 보정",
                hint,
                new VBox(6, distLbl, row),
                resetScaleBtn
        );

        Styles.styleTextField(apNameField);
        Styles.styleTextField(ssid24Field);
        Styles.styleTextField(ssid5Field);
        Styles.styleTextField(ssid6Field);
        Styles.styleSpinner(tx24Spinner);
        Styles.styleSpinner(gain24Spinner);
        Styles.styleSpinner(tx5Spinner);
        Styles.styleSpinner(gain5Spinner);
        Styles.styleSpinner(tx6Spinner);
        Styles.styleSpinner(gain6Spinner);
        Styles.styleAccentButton(applyApBtn);
        Styles.styleFlatButton(deleteApBtn);

        Label coordTitle = new Label("좌표(px)");
        coordTitle.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 12px;");
        apCoordLabel.setStyle("-fx-text-fill: " + Styles.TEXT_MAIN + "; -fx-font-size: 12px;");

        VBox band24 = createBandEditor("2.4 GHz", ssid24Field, tx24Spinner, gain24Spinner);
        VBox band5 = createBandEditor("5 GHz", ssid5Field, tx5Spinner, gain5Spinner);
        VBox band6 = createBandEditor("6 GHz", ssid6Field, tx6Spinner, gain6Spinner);

        HBox apButtons = new HBox(8, applyApBtn, deleteApBtn);
        apDetailsCard = Styles.card(
                "AP Details",
                new VBox(6, new Label("이름"), apNameField),
                apEnabledCheck,
                new VBox(4, coordTitle, apCoordLabel),
                band24,
                band5,
                band6,
                apButtons
        );

        applyApBtn.setOnAction(e -> applyApEdits());
        deleteApBtn.setOnAction(e -> deleteCurrentAp());

        root.getChildren().addAll(scaleCard, apDetailsCard);
        setSelectedAp(null);
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
        bind(state, env, applyScale, resetScale, null, null);
    }

    public void bind(AppState state,
                     WifiEnvironment env,
                     Runnable applyScale,
                     Runnable resetScale,
                     Runnable apChanged,
                     Runnable clearApSelection) {

        if (applyScale != null) this.onApplyScale = applyScale;
        if (resetScale != null) this.onResetScale = resetScale;
        if (apChanged != null) this.onApChanged = apChanged;
        if (clearApSelection != null) this.onClearApSelection = clearApSelection;
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
        if (ap == currentAp) {
            if (ap != null) {
                apCoordLabel.setText(String.format("%.1f, %.1f", ap.x, ap.y));
            }
            return;
        }

        currentAp = ap;
        boolean hasSelection = (currentAp != null);

        apDetailsCard.setManaged(hasSelection);
        apDetailsCard.setVisible(hasSelection);
        applyApBtn.setDisable(!hasSelection);
        deleteApBtn.setDisable(!hasSelection);

        if (!hasSelection) {
            apNameField.clear();
            apEnabledCheck.setSelected(false);
            apCoordLabel.setText("-");
            fillBandFields(Band.GHZ_24, null);
            fillBandFields(Band.GHZ_5, null);
            fillBandFields(Band.GHZ_6, null);
            return;
        }

        apNameField.setText(currentAp.name == null ? "" : currentAp.name);
        apEnabledCheck.setSelected(currentAp.enabled);
        apCoordLabel.setText(String.format("%.1f, %.1f", currentAp.x, currentAp.y));
        fillBandFields(Band.GHZ_24, currentAp.radios.get(Band.GHZ_24));
        fillBandFields(Band.GHZ_5, currentAp.radios.get(Band.GHZ_5));
        fillBandFields(Band.GHZ_6, currentAp.radios.get(Band.GHZ_6));
    }

    public TextField getRealMetersField() {
        return realMetersField;
    }

    private VBox createBandEditor(String title, TextField ssid, Spinner<Double> tx, Spinner<Double> gain) {
        Label t = new Label(title);
        t.setStyle("-fx-text-fill: " + Styles.TEXT_MAIN + "; -fx-font-size: 12px; -fx-font-weight: 600;");
        Label ssidLabel = new Label("SSID");
        ssidLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");
        Label txLabel = new Label("Tx(dBm)");
        txLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");
        Label gainLabel = new Label("Gain(dBi)");
        gainLabel.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 11px;");

        HBox row = new HBox(8, tx, gain);
        return new VBox(6, t, ssidLabel, ssid, new HBox(8, txLabel, gainLabel), row);
    }

    private static Spinner<Double> createDoubleSpinner(double min, double max, double initial, double step) {
        Spinner<Double> sp = new Spinner<>(min, max, initial, step);
        sp.setEditable(true);
        return sp;
    }

    private void applyApEdits() {
        if (currentAp == null) return;

        currentAp.name = safeName(apNameField.getText(), currentAp.name);
        currentAp.enabled = apEnabledCheck.isSelected();

        writeBand(Band.GHZ_24, ssid24Field, tx24Spinner, gain24Spinner);
        writeBand(Band.GHZ_5, ssid5Field, tx5Spinner, gain5Spinner);
        writeBand(Band.GHZ_6, ssid6Field, tx6Spinner, gain6Spinner);

        onApChanged.run();
    }

    private void deleteCurrentAp() {
        if (currentAp == null || envRef == null) return;

        envRef.getAps().remove(currentAp);
        currentAp = null;
        setSelectedAp(null);
        onClearApSelection.run();
        onApChanged.run();
    }

    private void fillBandFields(Band band, RadioConfig rc) {
        TextField ssid = getSsidField(band);
        Spinner<Double> tx = getTxSpinner(band);
        Spinner<Double> gain = getGainSpinner(band);

        if (rc == null) {
            ssid.clear();
            tx.getValueFactory().setValue(0.0);
            gain.getValueFactory().setValue(0.0);
            return;
        }

        ssid.setText(rc.ssid == null ? "" : rc.ssid);
        tx.getValueFactory().setValue(rc.txPowerDbm);
        gain.getValueFactory().setValue(rc.antennaGain);
    }

    private void writeBand(Band band, TextField ssidField, Spinner<Double> txSpinner, Spinner<Double> gainSpinner) {
        RadioConfig rc = currentAp.radios.get(band);
        if (rc == null) return;

        rc.ssid = ssidField.getText() == null ? "" : ssidField.getText().trim();
        rc.txPowerDbm = spinnerValue(txSpinner);
        rc.antennaGain = spinnerValue(gainSpinner);
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
}
