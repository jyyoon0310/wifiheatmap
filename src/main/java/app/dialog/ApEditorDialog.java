package app.dialog;

import app.model.RadioConfig;
import app.model.AP;
import app.model.Band;
import app.ui.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

public class ApEditorDialog {

    public enum Result { OK, DELETE, CANCEL }

    public static Result show(Window owner, AP ap, Runnable onChanged) {
        Dialog<Result> dlg = new Dialog<>();
        dlg.setTitle("AP 편집: " + ap.name);
        if (owner != null) dlg.initOwner(owner);

        // ── Root layout ───────────────────────────────────────────────────
        VBox root = new VBox(14);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color:transparent;");

        // ── AP 기본 정보 ──────────────────────────────────────────────────
        Label apNameLbl = styledSubLabel("AP 이름");
        TextField apName = new TextField(ap.name);
        Styles.styleTextField(apName);

        CheckBox apEnabled = new CheckBox("AP 활성화");
        Runnable applyEnabled = () -> apEnabled.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:12px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        applyEnabled.run();
        Styles.addThemeListener(applyEnabled);
        apEnabled.setSelected(ap.enabled);

        Label xLbl = styledSubLabel("X (px)");
        Label yLbl = styledSubLabel("Y (px)");
        Label hLbl = styledSubLabel("높이 (m)");

        Spinner<Double> sx = createDoubleSpinner(0, 100000, ap.x, 1.0);
        Spinner<Double> sy = createDoubleSpinner(0, 100000, ap.y, 1.0);
        Spinner<Double> sh = createDoubleSpinner(0.1, 30.0, ap.heightM, 0.1);
        Styles.styleSpinner(sx);
        Styles.styleSpinner(sy);
        Styles.styleSpinner(sh);
        sx.setMaxWidth(Double.MAX_VALUE);
        sy.setMaxWidth(Double.MAX_VALUE);
        sh.setMaxWidth(Double.MAX_VALUE);

        HBox coordRow = new HBox(10,
                vbox(xLbl, sx), vbox(yLbl, sy), vbox(hLbl, sh));
        HBox.setHgrow(coordRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(coordRow.getChildren().get(1), Priority.ALWAYS);
        HBox.setHgrow(coordRow.getChildren().get(2), Priority.ALWAYS);
        for (Node n : coordRow.getChildren())
            HBox.setHgrow(n, Priority.ALWAYS);

        // ── 밴드별 탭 ─────────────────────────────────────────────────────
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setStyle("-fx-background-color:transparent;");

        for (Band band : Band.values()) {
            RadioConfig rc = ap.radios.get(band);
            if (rc == null) continue;
            Tab tab = new Tab(band.label);
            tab.setContent(buildRadioEditor(rc, onChanged));
            tabs.getTabs().add(tab);
        }

        root.getChildren().addAll(
                vbox(apNameLbl, apName),
                apEnabled,
                coordRow,
                new Separator(),
                tabs
        );

        // ── Separator 스타일 ──────────────────────────────────────────────
        for (Node n : root.getChildren()) {
            if (n instanceof Separator sep) {
                Runnable r = () -> sep.setStyle("-fx-background-color:" + Styles.borderSoft() + ";");
                r.run();
                Styles.addThemeListener(r);
            }
        }

        dlg.getDialogPane().setContent(root);
        dlg.getDialogPane().setPrefWidth(440);

        // ── Buttons ───────────────────────────────────────────────────────
        ButtonType BTN_DELETE = new ButtonType("삭제", ButtonBar.ButtonData.LEFT);
        dlg.getDialogPane().getButtonTypes().addAll(BTN_DELETE, ButtonType.CANCEL, ButtonType.OK);

        // ── Glass dialog styling ──────────────────────────────────────────
        Styles.styleDialogPane(dlg.getDialogPane());

        dlg.setResultConverter(bt -> {
            if (bt == BTN_DELETE) {
                if (onChanged != null) onChanged.run();
                return Result.DELETE;
            }
            if (bt == ButtonType.OK) {
                commitSpinner(sx); commitSpinner(sy); commitSpinner(sh);
                ap.name = apName.getText().trim().isEmpty() ? ap.name : apName.getText().trim();
                ap.enabled = apEnabled.isSelected();
                ap.x = sx.getValue();
                ap.y = sy.getValue();
                ap.heightM = sh.getValue();
                if (onChanged != null) onChanged.run();
                return Result.OK;
            }
            return Result.CANCEL;
        });

        return dlg.showAndWait().orElse(Result.CANCEL);
    }

    // ── Radio band editor ─────────────────────────────────────────────────────
    private static Node buildRadioEditor(RadioConfig rc, Runnable onChanged) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12, 4, 4, 4));
        box.setStyle("-fx-background-color:transparent;");

        CheckBox radioEnabled = new CheckBox("이 밴드 활성화");
        radioEnabled.setSelected(rc.enabled);
        Runnable applyEnabled = () -> radioEnabled.setStyle(
                "-fx-text-fill:" + Styles.textMain() + ";" +
                "-fx-font-size:12px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        applyEnabled.run();
        Styles.addThemeListener(applyEnabled);

        Label ssidLbl = styledSubLabel("SSID");
        TextField ssid = new TextField(rc.ssid);
        Styles.styleTextField(ssid);
        ssid.setMaxWidth(Double.MAX_VALUE);

        Label txLbl = styledSubLabel("Tx Power");
        Label txVal = new Label(String.format("%.0f dBm (고정)", RadioConfig.FIXED_TX_POWER_DBM));
        Runnable applyTxVal = () -> txVal.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:12px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        applyTxVal.run();
        Styles.addThemeListener(applyTxVal);

        Label gainLbl = styledSubLabel("안테나 Gain (dBi)");
        Spinner<Double> gain = createDoubleSpinner(0.0, 15.0, rc.antennaGain, 0.5);
        Styles.styleSpinner(gain);
        gain.setMaxWidth(Double.MAX_VALUE);
        gain.setEditable(true);

        Label chLbl = styledSubLabel("Channel");
        ComboBox<Integer> channel = new ComboBox<>();
        Label bwLbl = styledSubLabel("Bandwidth (MHz)");
        ComboBox<Integer> width = new ComboBox<>();

        if (rc.band == Band.GHZ_24) {
            channel.getItems().setAll(1,2,3,4,5,6,7,8,9,10,11);
            width.getItems().setAll(20, 40);
        } else if (rc.band == Band.GHZ_5) {
            channel.getItems().setAll(36,40,44,48,149,153,157,161);
            width.getItems().setAll(20, 40, 80);
        } else {
            channel.getItems().setAll(1, 5, 9, 13, 17);
            width.getItems().setAll(20, 40, 80);
        }
        selectOrFirstInt(channel, rc.channel);
        selectOrFirstInt(width, rc.channelWidth);
        channel.setMaxWidth(Double.MAX_VALUE);
        width.setMaxWidth(Double.MAX_VALUE);

        Runnable applyChannel = () -> channel.setStyle(Styles.comboBase());
        applyChannel.run();
        Styles.addThemeListener(applyChannel);
        Styles.installComboPopupStyle(channel);

        Runnable applyWidth = () -> width.setStyle(Styles.comboBase());
        applyWidth.run();
        Styles.addThemeListener(applyWidth);
        Styles.installComboPopupStyle(width);

        HBox chRow = new HBox(10, vbox(chLbl, channel), vbox(bwLbl, width));
        for (Node n : chRow.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);

        box.getChildren().addAll(
                radioEnabled,
                vbox(ssidLbl, ssid),
                hbox(vbox(txLbl, txVal), vbox(gainLbl, gain)),
                chRow
        );

        // Listeners
        radioEnabled.selectedProperty().addListener((o, ov, nv) -> {
            rc.enabled = nv;
            if (onChanged != null) onChanged.run();
        });
        ssid.textProperty().addListener((o, ov, nv) -> rc.ssid = nv);
        gain.valueProperty().addListener((o, ov, nv) -> {
            rc.antennaGain = nv;
            if (onChanged != null) onChanged.run();
        });
        channel.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) rc.channel = nv;
            if (onChanged != null) onChanged.run();
        });
        width.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) rc.channelWidth = nv;
            if (onChanged != null) onChanged.run();
        });

        return box;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static Label styledSubLabel(String text) {
        Label l = new Label(text);
        Runnable r = () -> l.setStyle(
                "-fx-text-fill:" + Styles.textSub() + ";" +
                "-fx-font-size:11px;" +
                "-fx-font-family:" + Styles.FONT_STACK + ";");
        r.run();
        Styles.addThemeListener(r);
        return l;
    }

    private static VBox vbox(Node... children) {
        VBox b = new VBox(4, children);
        VBox.setVgrow(b, Priority.NEVER);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    private static HBox hbox(Node... children) {
        HBox b = new HBox(10, children);
        for (Node n : children) HBox.setHgrow(n, Priority.ALWAYS);
        return b;
    }

    private static Spinner<Double> createDoubleSpinner(double min, double max, double init, double step) {
        Spinner<Double> sp = new Spinner<>(min, max, init, step);
        sp.setEditable(true);
        return sp;
    }

    private static <T> void commitSpinner(Spinner<T> spinner) {
        if (!spinner.isEditable()) return;
        String text = spinner.getEditor().getText();
        SpinnerValueFactory<T> vf = spinner.getValueFactory();
        if (vf == null) return;
        try {
            if (vf instanceof SpinnerValueFactory.DoubleSpinnerValueFactory df)
                df.setValue(Double.parseDouble(text));
            else if (vf instanceof SpinnerValueFactory.IntegerSpinnerValueFactory inf)
                inf.setValue(Integer.parseInt(text));
        } catch (NumberFormatException ignored) {}
    }

    private static void selectOrFirstInt(ComboBox<Integer> cb, Integer value) {
        if (value != null && cb.getItems().contains(value))
            cb.getSelectionModel().select(value);
        else if (!cb.getItems().isEmpty())
            cb.getSelectionModel().select(0);
    }
}
