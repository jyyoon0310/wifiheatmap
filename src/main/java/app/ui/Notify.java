package app.ui;

import javafx.stage.Window;

/**
 * 안내·오류 팝업 — {@link Sheet} 한 장(제목 + 본문 + 확인 버튼).
 *
 * <p>예전엔 OS 기본 Alert(제목 막대·아이콘·작은 버튼)를 썼다. 지금은 모든 팝업이 같은 시트 모양이다.</p>
 */
public final class Notify {

    private Notify() {}

    /** 정보 안내. */
    public static void info(Window owner, String title, String body) {
        Sheet.notice(owner, title, body);
    }

    /** 오류 안내. */
    public static void error(Window owner, String title, String body) {
        Sheet.notice(owner, title, body);
    }
}
