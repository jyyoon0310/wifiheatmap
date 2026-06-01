package app.dialog;

import app.model.AppState;
import app.ui.Styles;
import app.ui.Themed;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * 연구·실험용 고급 설정 다이얼로그.
 *
 * <p>일반 사용자가 보기엔 너무 자세한 옵션들 — Legacy/FDTD 엔진 직접 선택, CPU/GPU 솔버 모드,
 * 경로손실 지수, 스무딩 반경 등 — 을 여기에 모아 두고 평소엔 UI에서 숨긴다.</p>
 *
 * <p>활성화하면 {@code AppState.advancedMode = true} 가 되고, {@link app.ui.TopToolbar}의
 * 히트맵 엔진 콤보와 솔버 모드 콤보가 다시 노출된다.</p>
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
                "체크 시 상단 도구모음에 히트맵 엔진 콤보(Legacy/DPM/FDTD)와\n"
              + "솔버 가속 모드 콤보(CPU/GPU)가 다시 나타납니다.");

        // ── 2) 히트맵 엔진 선택 (고급 모드에서만 의미 있음) ────────────────
        Label engineLbl = styledHeader("히트맵 엔진");

        ComboBox<AppState.HeatmapModel> engineCombo = new ComboBox<>();
        engineCombo.getItems().setAll(AppState.HeatmapModel.values());
        engineCombo.setConverter(new StringConverter<>() {
            @Override public String toString(AppState.HeatmapModel m) {
                if (m == null) return "";
                return switch (m) {
                    case DPM      -> "DPM (권장) — 빠르고 정확한 지배경로 모델";
                    case LEGACY   -> "Legacy (비교용) — 경로손실 + 반사·회절";
                    case FDTD_TEZ -> "FDTD (실험) — Maxwell 시간 진전";
                };
            }
            @Override public AppState.HeatmapModel fromString(String s) { return null; }
        });
        engineCombo.getSelectionModel().select(state.getHeatmapModel());
        engineCombo.setMaxWidth(Double.MAX_VALUE);
        engineCombo.setStyle(Styles.comboBase());
        Styles.installComboPopupStyle(engineCombo);

        // ── 3) 솔버 가속 모드 ───────────────────────────────────────
        Label solverLbl = styledHeader("솔버 가속");

        ComboBox<AppState.HeatmapSolverMode> solverCombo = new ComboBox<>();
        solverCombo.getItems().setAll(AppState.HeatmapSolverMode.values());
        solverCombo.setConverter(new StringConverter<>() {
            @Override public String toString(AppState.HeatmapSolverMode m) {
                return (m == AppState.HeatmapSolverMode.GPU) ? "GPU (실험)" : "CPU";
            }
            @Override public AppState.HeatmapSolverMode fromString(String s) { return null; }
        });
        solverCombo.getSelectionModel().select(state.getHeatmapSolverMode());
        solverCombo.setMaxWidth(Double.MAX_VALUE);
        solverCombo.setStyle(Styles.comboBase());
        Styles.installComboPopupStyle(solverCombo);

        // ── 4) 물리 파라미터 ───────────────────────────────────────
        Label physicsLbl = styledHeader("전파 모델 파라미터");

        Label nLbl = styledSubLabel("경로손실 지수 N (실내 기본 3.5)");
        Spinner<Double> nSpinner = new Spinner<>(1.5, 6.0, state.getPathLossN(), 0.1);
        nSpinner.setEditable(true);
        nSpinner.setMaxWidth(Double.MAX_VALUE);
        Styles.styleSpinner(nSpinner);

        Label smoothLbl = styledSubLabel("스무딩 반경 (px, 클수록 부드러움)");
        Spinner<Integer> smoothSpinner = new Spinner<>(0, 32, state.getSmoothRadiusPx(), 1);
        smoothSpinner.setEditable(true);
        smoothSpinner.setMaxWidth(Double.MAX_VALUE);
        Styles.styleSpinner(smoothSpinner);

        HBox physicsRow = new HBox(10, vbox(nLbl, nSpinner), vbox(smoothLbl, smoothSpinner));
        HBox.setHgrow(physicsRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(physicsRow.getChildren().get(1), Priority.ALWAYS);

        // ── 5) FDTD 정보 (읽기 전용) ───────────────────────────────
        Label fdtdLbl = styledHeader("FDTD 솔버 (읽기 전용)");
        Label fdtdInfo = styledSubLabel(
                "• 격자 해상도: dx = λ/8 ~ λ/15 (자동)\n"
              + "• 시간 스텝: CFL 안전 마진 99%\n"
              + "• PML 두께: 최소 8 셀 / target reflection 10⁻⁸\n"
              + "• 배치 히트맵: 6,000 step  ·  실시간 오버레이: 가변");

        // ── 컨트롤 활성/비활성 (고급 모드 토글에 따라) ───────────────
        Runnable applyEnable = () -> {
            boolean adv = advancedToggle.isSelected();
            engineCombo.setDisable(!adv);
            solverCombo.setDisable(!adv);
        };
        applyEnable.run();
        advancedToggle.setOnAction(e -> applyEnable.run());

        // ── 조립 ────────────────────────────────────────────────
        root.getChildren().addAll(
                advancedToggle, advHint,
                new Separator(),
                engineLbl, engineCombo,
                solverLbl, solverCombo,
                new Separator(),
                physicsLbl, physicsRow,
                new Separator(),
                fdtdLbl, fdtdInfo
        );

        DialogBuilder.applyStandardLayout(dlg, root, ButtonType.APPLY, ButtonType.CANCEL);

        dlg.setResultConverter(bt -> {
            if (bt == ButtonType.APPLY) {
                state.setAdvancedMode(advancedToggle.isSelected());
                if (advancedToggle.isSelected()) {
                    AppState.HeatmapModel selectedModel = engineCombo.getValue();
                    if (selectedModel != null) state.setHeatmapModel(selectedModel);
                    AppState.HeatmapSolverMode solverMode = solverCombo.getValue();
                    if (solverMode != null) state.setHeatmapSolverMode(solverMode);
                } else {
                    // 고급 모드 OFF → 안전한 기본값으로 복귀
                    state.setHeatmapModel(AppState.HeatmapModel.DPM);
                }
                Double nv = nSpinner.getValue();
                if (nv != null) state.setPathLossN(nv);
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

    private static VBox vbox(javafx.scene.Node... nodes) {
        VBox b = new VBox(4, nodes);
        return b;
    }
}
