package app.model;

import javafx.scene.paint.Color;

/**
 * 신호 세기 등급 — 히트맵 색, 하단 범례, 결과 카드, 호버 표시가 모두 이 정의를 따른다.
 *
 * <p>예전엔 세 곳이 각자 하드코딩되어 있어서, 결과 카드는 "-55 dBm 이상 = 주황(강함)"인데
 * 히트맵은 -96~-10 dBm 에 그라데이션을 고르게 펴서 -55 dBm 을 초록으로 칠했다.
 * 등급 이름도 화면마다 달랐다("약함/주의" vs "불량/주의").</p>
 *
 * <p>히트맵 그라데이션은 각 등급 구간의 <b>중앙</b>에 그 등급 색을 고정한다 —
 * 구간 안의 값은 그 등급 색으로 보이고, 경계에서만 이웃 색과 섞인다.</p>
 */
public enum RssiLevel {
    STRONG("강함",     -55.0,                    "#F0632D"),
    GOOD  ("양호",     -65.0,                    "#F6D32D"),
    WEAK  ("약함",     -75.0,                    "#2FD44A"),
    DEAD  ("사각지대", Double.NEGATIVE_INFINITY, "#2D6CF6");

    public final String label;
    /** 이 등급의 하한 [dBm] (이상이면 이 등급). */
    public final double minDbm;
    public final String hex;

    RssiLevel(String label, double minDbm, String hex) {
        this.label = label;
        this.minDbm = minDbm;
        this.hex = hex;
    }

    public Color color() { return Color.web(hex); }

    /** RSSI → 등급. NaN/무한대는 사각지대. */
    public static RssiLevel of(double rssiDbm) {
        if (!Double.isFinite(rssiDbm)) return DEAD;
        for (RssiLevel lv : values()) {
            if (rssiDbm >= lv.minDbm) return lv;
        }
        return DEAD;
    }

    // ── 범례 범위 (하단 범례 바가 보여주는 구간) ─────────────────────────
    public static final double LEGEND_MIN_DBM = -90.0;
    public static final double LEGEND_MAX_DBM = -30.0;

    // ── 연속 그라데이션 정지점 [dBm, 색] — 각 등급 구간 중앙에 등급 색 ──────
    // 사각지대(-90 기준) → 하늘(경계 완충) → 약함(-70) → 양호(-60) → 강함(-50) → 강함 심화(-35)
    // 마지막 정지점은 같은 "강함" 등급 안의 농도 변화다. 한 대로 집 전체가 강함인 경우(DPM 에서 흔함)
    // 지도 전체가 단색 주황이 되어 AP 주변과 먼 곳의 차이가 안 보이던 문제를 막는다.
    private static final double[] STOP_DBM = { -90.0,     -80.0,     -70.0,      -60.0,      -50.0,      -35.0 };
    private static final String[] STOP_HEX = { DEAD.hex, "#34D1FF", WEAK.hex,   GOOD.hex,   STRONG.hex, "#C9262B" };
    private static final Color[]  STOP_COLOR = new Color[STOP_HEX.length];
    static {
        for (int i = 0; i < STOP_HEX.length; i++) STOP_COLOR[i] = Color.web(STOP_HEX[i]);
    }

    /** RSSI → 연속 색 (불투명). 범위 밖은 양 끝 색으로 고정. */
    public static Color gradientColor(double rssiDbm) {
        if (!Double.isFinite(rssiDbm) || rssiDbm <= STOP_DBM[0]) return STOP_COLOR[0];
        int last = STOP_DBM.length - 1;
        if (rssiDbm >= STOP_DBM[last]) return STOP_COLOR[last];
        int i = 0;
        while (rssiDbm > STOP_DBM[i + 1]) i++;
        double u = (rssiDbm - STOP_DBM[i]) / (STOP_DBM[i + 1] - STOP_DBM[i]);
        return STOP_COLOR[i].interpolate(STOP_COLOR[i + 1], u);
    }

    /** 하단 범례 바용 CSS linear-gradient — {@link #gradientColor} 와 같은 정지점. */
    public static String legendGradientCss() {
        StringBuilder sb = new StringBuilder("linear-gradient(to right");
        double span = LEGEND_MAX_DBM - LEGEND_MIN_DBM;
        for (int i = 0; i < STOP_DBM.length; i++) {
            double pct = 100.0 * (STOP_DBM[i] - LEGEND_MIN_DBM) / span;
            sb.append(String.format(", %s %.1f%%", STOP_HEX[i], Math.max(0, Math.min(100, pct))));
        }
        sb.append(", ").append(STOP_HEX[STOP_HEX.length - 1]).append(" 100%)");
        return sb.toString();
    }

    /** 이 등급이 범례 바에서 차지하는 비율 [0,1] (범례 아래 이름 칸 배치용). */
    public double legendShare() {
        double hi = switch (this) {
            case STRONG -> LEGEND_MAX_DBM;
            case GOOD   -> STRONG.minDbm;
            case WEAK   -> GOOD.minDbm;
            case DEAD   -> WEAK.minDbm;
        };
        double lo = (this == DEAD) ? LEGEND_MIN_DBM : minDbm;
        return (hi - lo) / (LEGEND_MAX_DBM - LEGEND_MIN_DBM);
    }
}
