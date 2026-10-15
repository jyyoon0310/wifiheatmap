package app.dialog;

import app.model.AppState;
import app.ui.Sheet;
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
        Sheet.Switch toggle = new Sheet.Switch(state.isAdvancedMode());
        VBox content = new VBox(16,
                Sheet.settingRow("고급 모드",
                        "DPM 탐색 통계와 전파 흐름 세부 정보를 화면에 함께 보여 줘요.", toggle),
                Sheet.infoBox("FDTD 파동 시뮬레이션\n"
                        + "격자 해상도 λ/8 이상 (자동) · 시간 간격 CFL 0.9\n"
                        + "흡수 경계(PML) 8칸 이상 · 자리 추천 정밀 검증 6,000 스텝"));

        String choice = Sheet.show(owner, "고급 설정", "연구·실험용 정보를 더 보여 줄 수 있어요.",
                content, 420, true,
                Sheet.Action.secondary("cancel", "취소"),
                Sheet.Action.primary("apply", "적용"));
        if ("apply".equals(choice)) {
            state.setAdvancedMode(toggle.isOn());
        }
    }
}
