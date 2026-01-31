package app.ui;

import app.model.AppState;
import app.model.WifiEnvironment;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

    // 외부에서 주입받을 핸들러(컨트롤러가 연결)
    private Runnable onApplyScale = () -> {};
    private Runnable onResetScale = () -> {};

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

        root.getChildren().addAll(scaleCard);

        // TODO: 다음 단계에서 "벽 카드", "AP 카드"도 여기로 합류시키면 됨.
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

        if (applyScale != null) this.onApplyScale = applyScale;
        if (resetScale != null) this.onResetScale = resetScale;

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

    public TextField getRealMetersField() {
        return realMetersField;
    }
}