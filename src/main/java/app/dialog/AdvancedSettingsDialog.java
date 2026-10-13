package app.dialog;

import app.model.AppState;
import app.ui.Styles;
import app.ui.Themed;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * 연구·실험용 고급 설정 다이얼로그.
 *
 * <p>활성화하면 {@code AppState.advancedMode = true} 가 되고, {@link app.ui.TopToolbar}에
 * 연구용 버튼(AP 추천·히트맵 생성·지우기, 솔버 제어)이 다시 노출된다.</p>
 *
 * <p>히트맵 엔진은 DPM 고정이다. 예전의 엔진 선택(Legacy/DPM/FDTD)·솔버 가속(CPU/GPU)·
 * 경로손실 지수 n·스무딩 반경은 모두 Legacy 경로에만 영향을 주거나(n, 솔버) 값을 저장조차
 * 하지 않아(스무딩) DPM 결과를 바꾸지 못했으므로 제거했다. Legacy 는 발표 수치 재현과
 * 회귀 테스트용 기준선으로 엔진에만 남아 있다.</p>
 */
public final class AdvancedSettingsDialog {

    private AdvancedSettingsDialog() {}

    public static void show(Window owner, AppState state) {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("고급 설정 (연구·실험 모드)");
        if (owner != null) dlg.initOwner(owner);

        VBox root = DialogBuilder.contentBox(14);

        // ── 1) 고급 모드 토글 ─────────────────────────────────────────
        CheckBox advancedToggle = new CheckBox("고급 모드 활성화");
        styleCheck(advancedToggle);
        advancedToggle.setSelected(state.isAdvancedMode());

        Label advHint = styledSubLabel(
                "체크 시 상단 도구모음에 연구용 버튼(히트맵 생성·지우기 등)과\n"
              + "솔버 제어 버튼이 나타납니다.");

        // ── 2) FDTD 정보 (읽기 전용) ───────────────────────────────
        Label fdtdLbl = styledHeader("FDTD 솔버 (읽기 전용)");
        Label fdtdInfo = styledSubLabel(
                "• 격자 해상도: dx = λ/8 ~ λ/15 (자동)\n"
              + "• 시간 스텝: CFL 안전 마진 99%\n"
              + "• PML 두께: 최소 8 셀 / target reflection 10⁻⁸\n"
              + "• AP 추천 검증: 6,000 step  ·  실시간 오버레이: 가변");

        // ── 조립 ────────────────────────────────────────────────
        root.getChildren().addAll(
                advancedToggle, advHint,
                new Separator(),
                fdtdLbl, fdtdInfo
        );

        DialogBuilder.applyStandardLayout(dlg, root, ButtonType.APPLY, ButtonType.CANCEL);

        dlg.setResultConverter(bt -> {
            if (bt == ButtonType.APPLY) {
                state.setAdvancedMode(advancedToggle.isSelected());
            }
            return null;
        });

        dlg.showAndWait();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────
    private static Label styledHeader(String text) {
        return Themed.bind(new Label(text),
                () -> "-fx-text-fill:" + Styles.textMain() + ";"
                    + "-fx-font-size:13px;"
                    + "-fx-font-weight:bold;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
    }

    private static Label styledSubLabel(String text) {
        return Themed.subLabel(text);
    }

    private static void styleCheck(CheckBox cb) {
        Themed.bind(cb,
                () -> "-fx-text-fill:" + Styles.textMain() + ";"
                    + "-fx-font-size:13px;"
                    + "-fx-font-weight:600;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
    }

}
