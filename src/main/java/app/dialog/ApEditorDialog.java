package app.dialog;

import app.model.RadioConfig;
import app.model.AP;
import app.model.Band;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

public class ApEditorDialog {

    /** 결과 타입: OK / DELETE / CANCEL */
    public enum Result { OK, DELETE, CANCEL }

    public static Result show(Window owner,
                              AP ap,
                              Runnable onChanged // 값 바뀌었을 때(OK/DELETE) 호출
    ) {
        Dialog<Result> dlg = new Dialog<>();
        dlg.setTitle("AP 편집: " + ap.name);
        if (owner != null) dlg.initOwner(owner);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));

        // 상단: 물리 AP 공통
        GridPane top = new GridPane();
        top.setHgap(10);
        top.setVgap(10);

        TextField apName = new TextField(ap.name);
        CheckBox apEnabled = new CheckBox("AP 활성화");
        apEnabled.setSelected(ap.enabled);

        Spinner<Double> sx = new Spinner<>(0.0, 100000.0, ap.x, 1.0);
        Spinner<Double> sy = new Spinner<>(0.0, 100000.0, ap.y, 1.0);
        sx.setEditable(true);
        sy.setEditable(true);

        int tr = 0;
        top.add(new Label("AP 이름"), 0, tr);
        top.add(apName, 1, tr++);
        top.add(apEnabled, 1, tr++);
        top.add(new Label("X(px)"), 0, tr);
        top.add(sx, 1, tr++);
        top.add(new Label("Y(px)"), 0, tr);
        top.add(sy, 1, tr++);

        root.setTop(top);

        // 중앙: 밴드별 탭
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        for (Band band : Band.values()) {
            RadioConfig rc = ap.radios.get(band);
            if (rc == null) continue;

            Tab tab = new Tab(band.label);
            tab.setContent(buildRadioEditor(ap, rc, onChanged)); // 실시간 반영 포함
            tabs.getTabs().add(tab);
        }

        root.setCenter(tabs);
        dlg.getDialogPane().setContent(root);

        ButtonType BTN_DELETE = new ButtonType("삭제", ButtonBar.ButtonData.LEFT);
        dlg.getDialogPane().getButtonTypes().addAll(BTN_DELETE, ButtonType.CANCEL, ButtonType.OK);

        dlg.setResultConverter(bt -> {
            if (bt == BTN_DELETE) {
                if (onChanged != null) onChanged.run();
                return Result.DELETE;
            }
            if (bt == ButtonType.OK) {
                commitSpinner(sx);
                commitSpinner(sy);

                ap.name = apName.getText().trim().isEmpty() ? ap.name : apName.getText().trim();
                ap.enabled = apEnabled.isSelected();
                ap.x = sx.getValue();
                ap.y = sy.getValue();

                if (onChanged != null) onChanged.run();
                return Result.OK;
            }
            return Result.CANCEL;
        });

        return dlg.showAndWait().orElse(Result.CANCEL);
    }

    private static <T> void commitSpinner(Spinner<T> spinner) {
        if (!spinner.isEditable()) return;
        String text = spinner.getEditor().getText();
        SpinnerValueFactory<T> vf = spinner.getValueFactory();
        if (vf == null) return;
        try {
            if (vf instanceof SpinnerValueFactory.DoubleSpinnerValueFactory df) {
                double v = Double.parseDouble(text);
                df.setValue(v);
            } else if (vf instanceof SpinnerValueFactory.IntegerSpinnerValueFactory inf) {
                int v = Integer.parseInt(text);
                inf.setValue(v);
            }
        } catch (NumberFormatException ignored) { }
    }

    /** 밴드별 설정 UI 한 장 */
    private static Node buildRadioEditor(AP ap, RadioConfig rc, Runnable onChanged) {
        GridPane gp = new GridPane();
        gp.setHgap(10);
        gp.setVgap(10);
        gp.setPadding(new Insets(10));

        CheckBox radioEnabled = new CheckBox("이 밴드 활성화");
        radioEnabled.setSelected(rc.enabled);

        TextField ssid = new TextField(rc.ssid);

        Slider txSlider = new Slider(0, 40, rc.txPowerDbm);
        txSlider.setShowTickMarks(true);
        txSlider.setMajorTickUnit(10);
        txSlider.setBlockIncrement(1);
        Label txVal = new Label(String.format("%.0f dBm", txSlider.getValue()));
        txSlider.valueProperty().addListener((o, ov, nv) -> txVal.setText(String.format("%.0f dBm", nv.doubleValue())));

        Spinner<Double> gain = new Spinner<>(0.0, 15.0, rc.antennaGain, 0.5);
        gain.setEditable(true);

        ComboBox<String> mode = new ComboBox<>();
        ComboBox<Integer> channel = new ComboBox<>();
        ComboBox<Integer> width = new ComboBox<>();
        ComboBox<String> security = new ComboBox<>();

        security.getItems().setAll("Open", "WPA2", "WPA3");

        // 밴드별 옵션
        if (rc.band == Band.GHZ_24) {
            mode.getItems().setAll("b", "g", "n", "ax");
            channel.getItems().setAll(1,2,3,4,5,6,7,8,9,10,11);
            width.getItems().setAll(20, 40);
        } else if (rc.band == Band.GHZ_5) {
            mode.getItems().setAll("a", "n", "ac", "ax");
            channel.getItems().setAll(36, 40, 44, 48, 149, 153, 157, 161);
            width.getItems().setAll(20, 40, 80);
        } else {
            mode.getItems().setAll("ax");
            channel.getItems().setAll(1, 5, 9, 13, 17);
            width.getItems().setAll(20, 40, 80);
        }

        selectOrFirst(mode, rc.mode);
        selectOrFirstInt(channel, rc.channel);
        selectOrFirstInt(width, rc.channelWidth);
        security.getSelectionModel().select(rc.security == null ? "WPA2" : rc.security);

        // 실시간 반영
        radioEnabled.selectedProperty().addListener((o, ov, nv) -> {
            rc.enabled = nv;
            if (onChanged != null) onChanged.run();
        });

        ssid.textProperty().addListener((o, ov, nv) -> rc.ssid = nv);

        txSlider.valueProperty().addListener((o, ov, nv) -> {
            rc.txPowerDbm = Math.round(nv.doubleValue());
            if (onChanged != null) onChanged.run();
        });

        gain.valueProperty().addListener((o, ov, nv) -> {
            rc.antennaGain = nv;
            if (onChanged != null) onChanged.run();
        });

        mode.valueProperty().addListener((o, ov, nv) -> {
            rc.mode = nv;
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

        security.valueProperty().addListener((o, ov, nv) -> {
            rc.security = nv;
            if (onChanged != null) onChanged.run();
        });

        int r = 0;
        gp.add(radioEnabled, 0, r++, 2, 1);
        gp.add(new Label("SSID"), 0, r); gp.add(ssid, 1, r++);
        gp.add(new Label("Tx Power"), 0, r); gp.add(new HBox(10, txSlider, txVal), 1, r++);
        gp.add(new Label("Gain(dBi)"), 0, r); gp.add(gain, 1, r++);
        gp.add(new Label("Mode"), 0, r); gp.add(mode, 1, r++);
        gp.add(new Label("Channel"), 0, r); gp.add(channel, 1, r++);
        gp.add(new Label("Width"), 0, r); gp.add(width, 1, r++);
        gp.add(new Label("Security"), 0, r); gp.add(security, 1, r++);

        return gp;
    }

    private static void selectOrFirst(ComboBox<String> cb, String value) {
        if (value != null && cb.getItems().contains(value)) cb.getSelectionModel().select(value);
        else if (!cb.getItems().isEmpty()) cb.getSelectionModel().select(0);
    }

    private static void selectOrFirstInt(ComboBox<Integer> cb, Integer value) {
        if (value != null && cb.getItems().contains(value)) cb.getSelectionModel().select(value);
        else if (!cb.getItems().isEmpty()) cb.getSelectionModel().select(0);
    }
}