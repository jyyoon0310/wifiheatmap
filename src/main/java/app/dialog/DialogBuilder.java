package app.dialog;

import app.ui.Styles;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;

/**
 * 다이얼로그 보일러플레이트 통합.
 *
 * <p>여러 다이얼로그가 다음 3줄을 반복 작성한다:
 * <pre>
 *   dlg.getDialogPane().setContent(root);
 *   dlg.getDialogPane().getButtonTypes().setAll(...);
 *   Styles.styleDialog(dlg);
 * </pre>
 *
 * <p>이 헬퍼를 쓰면 한 줄로 줄어들고, {@link Styles#styleDialog(Dialog)} 자동 호출이
 * 보장된다 — 호출자가 잊을 수 없다(CLAUDE.md 규칙).
 *
 * <h3>다이얼로그가 매우 다양해 모든 패턴을 추출하지 않음</h3>
 * 헤더/풋터/섹션 같은 디자인 헬퍼는 의도적으로 빼고, "content + buttons + styleDialog"
 * 묶음만 통합한다. 각 다이얼로그의 콘텐츠는 자체 구성하되 마무리 부분만 일원화.
 */
public final class DialogBuilder {

    private DialogBuilder() {}

    /**
     * 다이얼로그의 콘텐츠 + 버튼 + 스타일을 한 번에 적용. owner는 호출자가 별도 설정.
     *
     * <pre>{@code
     * DialogBuilder.applyStandardLayout(dlg, root, ButtonType.OK, ButtonType.CANCEL);
     * }</pre>
     */
    public static void applyStandardLayout(Dialog<?> dlg, Node content, ButtonType... buttons) {
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getButtonTypes().setAll(buttons);
        Styles.styleDialog(dlg);
    }

    /**
     * 다이얼로그 컨텐츠용 표준 VBox 컨테이너 — padding + transparent bg.
     * spacing 만 호출자가 결정.
     *
     * <pre>{@code
     * VBox root = DialogBuilder.contentBox(14);
     * }</pre>
     */
    public static VBox contentBox(double spacing) {
        VBox box = new VBox(spacing);
        box.setPadding(new Insets(18));
        box.setStyle("-fx-background-color:transparent;");
        return box;
    }
}
