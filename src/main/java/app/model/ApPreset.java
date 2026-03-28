package app.model;

/**
 * 대한민국 통신 3사 공유기 프리셋.
 * 선택 시 AP의 모든 RadioConfig를 해당 공유기 스펙으로 일괄 설정한다.
 *
 * 출처:
 * - SKB: 공식 블로그 (TX=20dBm 확인), 머큐리 GW-ME6110 스펙시트
 * - KT: 칩셋(IPQ6000/MT7981BA) 데이터시트 기반 추정
 * - LGU+: 다보링크 GAPD-7500 공식 스펙시트 (2.4G=5dBi, 5G=6dBi)
 * - 한국 전파법 EIRP 한도: 2.4/5GHz=23dBm, 6GHz=24dBm
 */
public enum ApPreset {
    CUSTOM("사용자 정의", null),

    // ── SK브로드밴드 (B인터넷) ──────────────────────────────────────────
    SKB_WIFI6("SKB 기가와이파이6 (Wi-Fi 6)", "SKT"),
    SKB_WIFI7("SKB 기가와이파이7 (Wi-Fi 7)", "SKT"),

    // ── KT ──────────────────────────────────────────────────────────────
    KT_WIFI6D("KT WiFi 6D (Wi-Fi 6)", "KT"),
    KT_WIFI6D_160("KT WiFi 6D 160MHz (Wi-Fi 6)", "KT"),
    KT_WIFI7D("KT WiFi 7D (Wi-Fi 7)", "KT"),

    // ── LG유플러스 ──────────────────────────────────────────────────────
    LGU_WIFI6("LGU+ 기가와이파이6 (Wi-Fi 6)", "LGU+"),
    LGU_WIFI7("LGU+ 와이파이7 (Wi-Fi 7)", "LGU+");

    public final String label;
    public final String isp;

    ApPreset(String label, String isp) {
        this.label = label;
        this.isp = isp;
    }

    /** 프리셋을 AP에 적용 (radios 설정 일괄 변경). CUSTOM이면 아무것도 안 함. */
    public void applyTo(AP ap) {
        if (this == CUSTOM) return;
        switch (this) {
            case SKB_WIFI6 -> {
                // 머큐리 GW-ME6110 / HFR GW-HF6110, 2x2, 내장 안테나 2~3dBi
                configRadio(ap, Band.GHZ_24, true,  20, 3, 1,  20);
                configRadio(ap, Band.GHZ_5,  true,  20, 3, 36, 80);
                configRadio(ap, Band.GHZ_6,  false, 20, 3, 1,  80);
            }
            case SKB_WIFI7 -> {
                // 2026.01 출시, 2x2, 160MHz, 4K-QAM, MLO
                configRadio(ap, Band.GHZ_24, true,  20, 4, 1,  20);
                configRadio(ap, Band.GHZ_5,  true,  20, 4, 36, 160);
                configRadio(ap, Band.GHZ_6,  false, 20, 4, 1,  80);
            }
            case KT_WIFI6D -> {
                // Qualcomm IPQ6000 기반, 2x2, 80MHz
                configRadio(ap, Band.GHZ_24, true,  21, 3, 1,  20);
                configRadio(ap, Band.GHZ_5,  true,  21, 3, 36, 80);
                configRadio(ap, Band.GHZ_6,  false, 21, 3, 1,  80);
            }
            case KT_WIFI6D_160 -> {
                // MediaTek MT7981BA (HR08-407H), 2x2, 160MHz
                configRadio(ap, Band.GHZ_24, true,  21, 3, 1,  20);
                configRadio(ap, Band.GHZ_5,  true,  21, 3, 36, 160);
                configRadio(ap, Band.GHZ_6,  false, 21, 3, 1,  80);
            }
            case KT_WIFI7D -> {
                // GB1_KB01-411H (가온브로드밴드), 2x2, 160MHz, 4K-QAM
                configRadio(ap, Band.GHZ_24, true,  21, 4, 1,  20);
                configRadio(ap, Band.GHZ_5,  true,  21, 4, 36, 160);
                configRadio(ap, Band.GHZ_6,  false, 21, 4, 1,  80);
            }
            case LGU_WIFI6 -> {
                // 다보링크 GAPD-7500, 2x2, 외장 안테나 (2.4G=5dBi, 5G=6dBi)
                configRadio(ap, Band.GHZ_24, true,  20, 5, 1,  20);
                configRadio(ap, Band.GHZ_5,  true,  20, 6, 36, 80);
                configRadio(ap, Band.GHZ_6,  false, 20, 5, 1,  80);
            }
            case LGU_WIFI7 -> {
                // 2025.03 출시, 국내 통신사 최초 6GHz 지원, 320MHz→160으로 보수적 설정
                configRadio(ap, Band.GHZ_24, true,  23, 4, 1,  20);
                configRadio(ap, Band.GHZ_5,  true,  23, 4, 36, 160);
                configRadio(ap, Band.GHZ_6,  true,  23, 4, 1,  160);
            }
        }
    }

    private static void configRadio(AP ap, Band band, boolean enabled,
                                     double txPower, double gain, int ch, int bw) {
        RadioConfig rc = ap.radios.get(band);
        if (rc == null) return;
        rc.enabled = enabled;
        rc.txPowerDbm = txPower;
        rc.antennaGain = gain;
        rc.channel = ch;
        rc.channelWidth = bw;
    }

    @Override
    public String toString() { return label; }
}
