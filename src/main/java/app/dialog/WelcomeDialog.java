package app.dialog;

import app.ui.Icons;
import app.ui.Sheet;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;

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
     * 시작 화면을 띄운다 — 선택지를 누르면 바로 그 흐름으로 들어간다(따로 "계속" 버튼 없음).
     *
     * @param owner       오너 윈도우 (null 가능)
     * @param recentFile  최근 사용 파일 (있으면 "불러오기" 설명에 이름 표시)
     * @return            사용자의 선택. 닫았으면 EMPTY_START.
     */
    public static Result show(Window owner, File recentFile) {
        Choice[] picked = {Choice.EMPTY_START};
        Stage[] stageRef = {null};

        boolean hasRecent = (recentFile != null && recentFile.exists());
        VBox options = new VBox(4,
                Sheet.optionRow(Icons.IMAGE, "새 평면도로 시작",
                        "도면 이미지(PNG, JPG)를 골라 처음부터 만들어요.",
                        () -> { picked[0] = Choice.NEW_FLOORPLAN; stageRef[0].close(); }),
                Sheet.optionRow(Icons.FOLDER_OPEN, "저장한 작업 이어서 하기",
                        hasRecent ? "최근 작업: " + recentFile.getName() : ".wifisettings 파일을 열어요.",
                        () -> { picked[0] = Choice.OPEN_FILE; stageRef[0].close(); }),
                Sheet.optionRow(Icons.CROP_FREE, "빈 화면에서 시작",
                        "나중에 메뉴에서 평면도를 열어요.",
                        () -> { picked[0] = Choice.EMPTY_START; stageRef[0].close(); }));

        CheckBox dontShow = Sheet.checkRow("다음부터 이 화면 보지 않기");
        VBox.setMargin(dontShow, new Insets(16, 0, 0, 12));
        VBox content = new VBox(options, dontShow);

        javafx.scene.layout.VBox card = Sheet.card("어떻게 시작할까요?",
                "평면도를 올리면 공유기 자리와 신호 세기를 미리 볼 수 있어요.", content, 440);
        stageRef[0] = Sheet.createStage(owner, card, true);
        stageRef[0].showAndWait();
        return new Result(picked[0], dontShow.isSelected());
    }
}
