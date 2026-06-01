package app.dialog;

import app.ui.Styles;
import app.ui.Themed;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.File;
import java.util.Optional;

/**
 * 앱 시작 시 표시되는 환영 다이얼로그.
 *
 * <p>일반 사용자가 막막함을 느끼지 않도록 다음 세 가지 시작 흐름을 명시한다:
 * <ol>
 *   <li>새 평면도 불러오기 — 이미지 파일 선택 후 작업 시작</li>
 *   <li>최근 작업 이어하기 — 최근 저장한 .wfm/.properties 파일 열기</li>
 *   <li>빈 화면에서 시작 — 다이얼로그 닫고 알아서 진행</li>
 * </ol>
 *
 * <p>"다음부터 안 보기"를 체크하면 사용자 prefs에 저장돼 다음 실행부터 자동으로 닫힌다.</p>
 */
public final class WelcomeDialog {

    /** 사용자가 선택한 시작 방식 */
    public enum Choice {
        NEW_FLOORPLAN,          // 새 평면도 불러오기
        OPEN_FILE,              // 저장된 작업 파일 불러오기 (FileChooser로 선택)
        EMPTY_START             // 빈 상태로 시작
    }

    public static final class Result {
        public final Choice choice;
        public final boolean dontShowAgain;

        public Result(Choice choice, boolean dontShowAgain) {
            this.choice = choice;
            this.dontShowAgain = dontShowAgain;
        }
    }

    private WelcomeDialog() {}

    /**
     * 환영 다이얼로그를 띄운다.
     *
     * @param owner       오너 윈도우 (null 가능)
     * @param recentFile  최근 사용 파일 (있으면 "불러오기" 카드에 hint로 표시)
     * @return            사용자의 선택. 닫혔으면 EMPTY_START 반환.
     */
    public static Result show(Window owner, File recentFile) {
        Dialog<Result> dlg = new Dialog<>();
        dlg.setTitle("Wi-Fi Heatmap에 오신 것을 환영합니다");
        if (owner != null) dlg.initOwner(owner);

        VBox root = new VBox(14);
        root.setPadding(new Insets(22, 26, 22, 26));
        root.setStyle("-fx-background-color:transparent;");
        root.setPrefWidth(480);

        // ── 헤더 ────────────────────────────────────────────────────────
        Label heading = Themed.headingLabel("어떻게 시작할까요?");

        Label sub = subLabel(
                "도면을 올려 공유기 위치를 시뮬레이션하고 신호 세기 지도를 만들어 보세요.");

        // ── 3개 선택지 카드 ─────────────────────────────────────────────
        OptionCard newCard = new OptionCard(
                "📂", "새 평면도 불러오기",
                "PNG, JPG 형식의 도면 이미지를 선택해 새로 시작합니다.",
                Choice.NEW_FLOORPLAN, true);

        boolean hasRecent = (recentFile != null && recentFile.exists());
        String openSub = hasRecent
                ? "저장된 .wifisettings 파일을 선택해 작업을 이어합니다. (최근: "
                        + recentFile.getName() + ")"
                : "저장된 .wifisettings 파일을 선택해 작업을 이어합니다.";
        OptionCard openCard = new OptionCard(
                "📁", "저장된 작업 불러오기",
                openSub,
                Choice.OPEN_FILE, true);

        OptionCard emptyCard = new OptionCard(
                "✨", "빈 화면에서 시작",
                "이 창을 닫고 메뉴에서 직접 평면도를 선택합니다.",
                Choice.EMPTY_START, true);

        ToggleGroup group = new ToggleGroup();
        newCard.bindGroup(group);
        openCard.bindGroup(group);
        emptyCard.bindGroup(group);
        // 기본 선택: 새 평면도
        newCard.select();

        // ── 하단: "다음부터 안 보기" + 버튼 ─────────────────────────────
        CheckBox dontShowCheck = Themed.subCheckBox("다음 실행부터 이 창을 보지 않기");

        root.getChildren().addAll(
                heading, sub,
                newCard.node(), openCard.node(), emptyCard.node(),
                new Separator(),
                dontShowCheck
        );

        ButtonType continueBtn = new ButtonType("계속", ButtonBar.ButtonData.OK_DONE);
        DialogBuilder.applyStandardLayout(dlg, root, continueBtn);

        dlg.setResultConverter(bt -> {
            Choice picked = newCard.isSelected() ? Choice.NEW_FLOORPLAN
                    : openCard.isSelected()    ? Choice.OPEN_FILE
                    : Choice.EMPTY_START;
            return new Result(picked, dontShowCheck.isSelected());
        });

        Optional<Result> r = dlg.showAndWait();
        return r.orElse(new Result(Choice.EMPTY_START, false));
    }

    // ── 선택 카드 헬퍼 ─────────────────────────────────────────────────────
    private static final class OptionCard {
        private final RadioButton radio = new RadioButton();
        private final HBox box;

        OptionCard(String emoji, String title, String desc, Choice choice, boolean enabled) {
            Label emo = new Label(emoji);
            emo.setStyle("-fx-font-size:24px; -fx-padding:0 8 0 0;");  // 이모지는 테마와 무관

            Label titleLbl = Themed.titleLabel(title);
            Label descLbl  = Themed.subLabel(desc);
            descLbl.setWrapText(true);
            descLbl.setMaxWidth(320);   // 부모 너비 제약 — wrap이 실제로 동작하도록
            titleLbl.setWrapText(true);
            titleLbl.setMaxWidth(320);

            VBox textCol = new VBox(2, titleLbl, descLbl);
            textCol.setMaxWidth(320);
            HBox.setHgrow(textCol, Priority.ALWAYS);

            box = new HBox(10, radio, emo, textCol);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new Insets(12, 14, 12, 12));
            Themed.bind(box, () ->
                    "-fx-background-color:" + Styles.bgRow() + ";" +
                    "-fx-background-radius:8;" +
                    "-fx-border-color:" + Styles.borderSoft() + ";" +
                    "-fx-border-radius:8;" +
                    "-fx-border-width:0.5;");

            radio.setUserData(choice);
            if (!enabled) {
                radio.setDisable(true);
                box.setOpacity(0.5);
            } else {
                box.setOnMouseClicked(e -> radio.setSelected(true));
            }
        }

        void bindGroup(ToggleGroup g) { radio.setToggleGroup(g); }
        void select()                  { radio.setSelected(true); }
        boolean isSelected()           { return radio.isSelected(); }
        javafx.scene.Node node()       { return box; }
    }

    private static Label subLabel(String text) {
        Label l = Themed.bind(new Label(text),
                () -> "-fx-text-fill:" + Styles.textSub() + ";"
                    + "-fx-font-size:12px;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
        l.setWrapText(true);
        return l;
    }
}
