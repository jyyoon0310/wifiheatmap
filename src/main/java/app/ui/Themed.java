package app.ui;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;

import java.util.function.Supplier;

/**
 * 자주 등장하는 "테마 토글에 반응하는 라벨/박스/체크박스" 팩토리.
 *
 * <p>리팩터링 전 코드는 라벨 하나당 다음과 같은 5~6줄 보일러플레이트가 반복됐다:
 * <pre>
 *   Label l = new Label(text);
 *   Runnable apply = () -> l.setStyle(
 *       "-fx-text-fill:" + Styles.textSub() + ";" +
 *       "-fx-font-size:11px;" +
 *       "-fx-font-family:" + Styles.FONT_STACK + ";");
 *   apply.run();
 *   Styles.addThemeListener(apply);
 * </pre>
 *
 * <p>이 헬퍼를 쓰면 한 줄로 줄어든다:
 * <pre>
 *   Label l = Themed.subLabel(text);
 * </pre>
 *
 * <p>내부적으로 동일하게 {@link Styles#addThemeListener(Runnable)}을 사용하므로
 * lifecycle(누수 가능성 포함)은 기존과 같다.</p>
 *
 * <h3>스타일 카테고리</h3>
 * <ul>
 *   <li>{@link #mainLabel(String)} — textMain + FONT_SIZE_LABEL</li>
 *   <li>{@link #subLabel(String)} — textSub + FONT_SIZE_SUB</li>
 *   <li>{@link #titleLabel(String)} — textMain + FONT_SIZE_TITLE + bold</li>
 *   <li>{@link #headingLabel(String)} — textMain + FONT_SIZE_HEADING + bold</li>
 *   <li>{@link #tinyLabel(String)} — textSub + FONT_SIZE_TINY</li>
 *   <li>{@link #subCheckBox(String)} — textSub + FONT_SIZE_SUB</li>
 *   <li>{@link #bind(Node, Supplier)} — 임의 노드용 fallback</li>
 * </ul>
 *
 * 더 복잡한 다중 색·다중 weight 라벨은 호출자가 직접 작성하거나
 * {@link #bind(Node, Supplier)}로 임의 스타일 식을 바인딩한다.
 */
public final class Themed {

    private Themed() {}

    // ── 라벨 팩토리 ─────────────────────────────────────────────────────────

    /** 본문 라벨 — textMain + FONT_SIZE_LABEL. */
    public static Label mainLabel(String text) {
        return styledLabel(text,
                () -> "-fx-text-fill:" + Styles.textMain() + ";"
                    + "-fx-font-size:" + Styles.FONT_SIZE_LABEL + "px;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
    }

    /** 보조 라벨 — textSub + FONT_SIZE_SUB. 카드 부제·툴팁·도움말 등에 사용. */
    public static Label subLabel(String text) {
        return styledLabel(text,
                () -> "-fx-text-fill:" + Styles.textSub() + ";"
                    + "-fx-font-size:" + Styles.FONT_SIZE_SUB + "px;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
    }

    /** 카드 타이틀 — textMain + FONT_SIZE_TITLE + bold. */
    public static Label titleLabel(String text) {
        return styledLabel(text,
                () -> "-fx-text-fill:" + Styles.textMain() + ";"
                    + "-fx-font-size:" + Styles.FONT_SIZE_TITLE + "px;"
                    + "-fx-font-weight:600;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
    }

    /** 다이얼로그 헤더 — textMain + FONT_SIZE_HEADING + bold. */
    public static Label headingLabel(String text) {
        return styledLabel(text,
                () -> "-fx-text-fill:" + Styles.textMain() + ";"
                    + "-fx-font-size:" + Styles.FONT_SIZE_HEADING + "px;"
                    + "-fx-font-weight:bold;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
    }

    /** HUD/디버그용 작은 라벨 — textSub + FONT_SIZE_TINY. */
    public static Label tinyLabel(String text) {
        return styledLabel(text,
                () -> "-fx-text-fill:" + Styles.textSub() + ";"
                    + "-fx-font-size:" + Styles.FONT_SIZE_TINY + "px;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
    }

    /** 체크박스 — textSub + FONT_SIZE_SUB ("다음부터 안 보기" 같은 작은 옵션용). */
    public static CheckBox subCheckBox(String text) {
        CheckBox c = new CheckBox(text);
        bind(c, () -> "-fx-text-fill:" + Styles.textSub() + ";"
                    + "-fx-font-size:" + Styles.FONT_SIZE_SUB + "px;"
                    + "-fx-font-family:" + Styles.FONT_STACK + ";");
        return c;
    }

    // ── 임의 노드 — fallback ──────────────────────────────────────────────

    /**
     * 임의 노드에 테마 의존 스타일을 바인딩. 즉시 1회 적용하고 테마 변경 때마다 재적용.
     *
     * <pre>{@code
     * Themed.bind(myCard, () -> "-fx-background-color:" + Styles.bgRow() + ";");
     * }</pre>
     */
    public static <T extends Node> T bind(T node, Supplier<String> styleSupplier) {
        Runnable apply = () -> node.setStyle(styleSupplier.get());
        apply.run();
        Styles.addThemeListener(apply);
        return node;
    }

    // ── 내부 헬퍼 ───────────────────────────────────────────────────────────

    private static Label styledLabel(String text, Supplier<String> styleSupplier) {
        Label l = new Label(text);
        bind(l, styleSupplier);
        return l;
    }
}
