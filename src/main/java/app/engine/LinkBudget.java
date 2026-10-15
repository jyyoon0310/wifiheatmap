package app.engine;

import app.model.RadioConfig;

/**
 * 링크 버짓 — "경로 손실이 이만큼일 때 수신 신호 세기는 몇 dBm 인가"를 정하는 단일 출처.
 *
 * <p>신호 지도 색, 마우스 위치 신호값, 공유기 자리 추천이 모두 이 식을 쓴다. 예전엔 같은 식이
 * 세 군데(HeatmapGenerator, MainController 호버, ApRecommender)에 따로 적혀 있어 한 곳만 고치면
 * 지도 색과 숫자가 어긋날 수 있었다. 보정(시스템 손실 등)을 넣을 때도 여기 한 곳만 고친다.</p>
 *
 * <pre>
 *   EIRP     = 송신 출력 + 안테나 이득
 *   RSSI     = EIRP − 대역폭 보정 − 경로 손실
 * </pre>
 */
public final class LinkBudget {
    private LinkBudget() {}

    /** 송신 출력 + 안테나 이득 [dBm]. 라디오 설정이 없으면 기본값(17 + 5 = 22). */
    public static double eirpDbm(RadioConfig rc) {
        return (rc != null)
                ? rc.txPowerDbm + rc.antennaGain
                : RadioConfig.FIXED_TX_POWER_DBM + RadioConfig.DEFAULT_ANTENNA_GAIN_DBI;
    }

    /** 수신 신호 세기 [dBm]. (계산 순서는 기존 결과와 비트 단위로 같도록 유지) */
    public static double rssiDbm(RadioConfig rc, double pathLossDb) {
        return eirpDbm(rc) - pathLossDb - (rc != null ? rc.bandwidthPenaltyDb() : 0.0);
    }
}
