package app.ui;

import app.model.AppState;
import app.model.WifiEnvironment;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class LeftPanel {

    private final VBox root = new VBox(14);

    // 스케일 UI
    private final TextField realMetersTf = new TextField();
    private final Button applyScaleBtn = new Button("적용");
    private final Button resetScaleBtn = new Button("보정 점 초기화");
    private final Label scaleNowLbl = new Label();

    public LeftPanel(Stage owner) {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: " + Styles.BG_PANEL + ";");

        Styles.styleTextField(realMetersTf);
        realMetersTf.setPrefWidth(150);
        realMetersTf.setMaxWidth(180);

        Styles.styleAccentButton(applyScaleBtn);
        Styles.styleFlatButton(resetScaleBtn);

        scaleNowLbl.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 12px;");

        Label hint = new Label("두 점 클릭 후 실제거리 입력");
        hint.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 12px;");

        VBox scaleCard = Styles.card(
                "스케일 보정",
                hint,
                new VBox(6,
                        labelSub("두 점 실제거리(m)"),
                        row(realMetersTf, applyScaleBtn)
                ),
                resetScaleBtn,
                scaleNowLbl
        );

        root.getChildren().addAll(scaleCard);
    }

    public Node getNode() {
        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setPrefViewportWidth(360);
        sp.setStyle("-fx-background: " + Styles.BG_PANEL + "; -fx-background-color: " + Styles.BG_PANEL + ";" +
                "-fx-border-color: " + Styles.BORDER_SOFT + "; -fx-border-width: 0 1 0 0;");
        return sp;
    }

    // Controller가 여기 바인딩해줌(LeftPanel은 로직 모름)
    public void bind(AppState state, WifiEnvironment env,
                     Runnable onApplyScale,
                     Runnable onResetScale) {

        // 값 바인딩
        realMetersTf.textProperty().bindBidirectional(state.calibRealMetersProperty(), new javafx.util.converter.NumberStringConverter());
        scaleNowLbl.textProperty().bind(new javafx.beans.property.SimpleStringProperty("현재 m/px: ")
                .concat(state.scaleMPerPxProperty().asString("%.5f")));

        applyScaleBtn.setOnAction(e -> { if (onApplyScale != null) onApplyScale.run(); });
        resetScaleBtn.setOnAction(e -> { if (onResetScale != null) onResetScale.run(); });
    }

    private static Label labelSub(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + Styles.TEXT_SUB + "; -fx-font-size: 12px;");
        return l;
    }

    private static Node row(Node a, Node b) {
        javafx.scene.layout.HBox h = new javafx.scene.layout.HBox(8, a, b);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return h;
    }
}