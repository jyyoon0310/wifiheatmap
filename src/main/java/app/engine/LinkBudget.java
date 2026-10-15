package app.engine;

import app.model.RadioConfig;

/**
 * 링크 버짓 — "경로 손실이 이만큼일 때 수신 신호 세기는 몇 dBm 인가"를 정하는 단일 출처.
 *
 * <p>신호 지도 색, 마우스 위치 신호값, 공유기 자리 추천이 모두 이 식을 쓴다.</p>
 *
 * <pre>
 *   RSSI = EIRP − 경로 손실 − 수신 장비 손실
 *   EIRP = 송신 출력 + 안테나 이득
 * </pre>
 *
 * <h3>수신 장비 손실 (근거: 문헌 실측값, 우리 측정에 맞춘 값 아님)</h3>
 * <p>DPM 논문의 경로 손실은 안테나·장비를 뺀 값이다 (Plets 2012: 등방성 기준 PL, n=2·PL₀=40dB@1m).
 * 실제 수신 세기에는 받는 쪽 항이 더 붙는다 — Khrouf 2008 Eq.7: P_r = P_e + G_e + G_r − L.
 * 상용 공유기(TP-Link Archer C7)와 상용 단말로 잰 5GHz 실측 모델(TMB, Adame·Carrascosa·Bellalta 2018,
 * arXiv:1812.00667)의 1m 손실 L₀ = 54.12 dB 는 자유공간(46.73 dB @5.18GHz)보다 7.39 dB 크다.
 * 이 차이를 상용 장비(단말 안테나 효율·실장 손실 등)의 몫으로 본다.</p>
 *
 * <h3>대역폭 보정은 하지 않는다</h3>
 * <p>예전엔 RSSI 에서 10·log10(BW/20) 을 뺐지만, 같은 TMB 실측에서 20/40/80MHz 의 RSSI 에 유의한 차이가
 * 없었다(논문 각주 11). 넓은 채널은 잡음 대역이 넓어져 SNR 이 나빠질 뿐 수신 전력 자체는 줄지 않는다.
 * (이 보정이 5GHz 의 실제 과대 예측을 6dB 가려 왔다.)</p>
 */
public final class LinkBudget {
    private LinkBudget() {}

    /** 상용 수신 장비 손실 [dB] — TMB 2018 의 L₀(54.12 dB) − FSPL(1m, 5.18GHz)(46.73 dB). */
    public static final double RECEIVER_LOSS_DB = 7.39;

    /** 송신 출력 + 안테나 이득 [dBm]. 라디오 설정이 없으면 기본값(17 + 5 = 22). */
    public static double eirpDbm(RadioConfig rc) {
        return (rc != null)
                ? rc.txPowerDbm + rc.antennaGain
                : RadioConfig.FIXED_TX_POWER_DBM + RadioConfig.DEFAULT_ANTENNA_GAIN_DBI;
    }

    /** 수신 신호 세기 [dBm]. */
    public static double rssiDbm(RadioConfig rc, double pathLossDb) {
        return eirpDbm(rc) - pathLossDb - RECEIVER_LOSS_DB;
    }
}
