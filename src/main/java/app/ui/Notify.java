package app.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;


/**
 * 알림 다이얼로그(Alert) 보일러플레이트 통합.
 *
 * <p>기존엔 각 호출 지점이 다음 6줄을 반복 작성했다:
 * <pre>
 *   Alert a = new Alert(AlertType.ERROR, msg, ButtonType.OK);
 *   a.initOwner(stage);
 *   Styles.styleAlert(a);
 *   a.showAndWait();
 * </pre>
 *
 * <p>이 헬퍼를 쓰면 한 줄로 줄어들고, CLAUDE.md 규칙인
 * {@link Styles#styleAlert(Alert)} 자동 적용이 보장된다 — 호출자가 잊을 수 없다.
 *
 * <p>owner 윈도우는 nullable. null이면 다이얼로그가 owner 없이 뜬다.
 */
public final class Notify {

    private Notify() {}

    /** 정보 알림. */
    public static void info(Window owner, String message) {
        show(Alert.AlertType.INFORMATION, owner, message);
    }

    /** 오류 알림. */
    public static void error(Window owner, String message) {
        show(Alert.AlertType.ERROR, owner, message);
    }

    // ── 내부 헬퍼 ───────────────────────────────────────────────────────────

    private static void show(Alert.AlertType type, Window owner, String message) {
        Alert a = new Alert(type, message, ButtonType.OK);
        if (owner != null) a.initOwner(owner);
        Styles.styleAlert(a);
        a.showAndWait();
    }
}
