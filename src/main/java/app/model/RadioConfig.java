package app.model;

public class RadioConfig {
    public static final double FIXED_TX_POWER_DBM = 17.0;
    public static final double DEFAULT_ANTENNA_GAIN_DBI = 5.0;
    public final Band band;

    public boolean enabled = true;

    // ✅ 밴드별 SSID (요구사항)
    public String ssid;

    public double txPowerDbm = FIXED_TX_POWER_DBM;
    public double antennaGain = DEFAULT_ANTENNA_GAIN_DBI;
    public int channel = 1;
    public int channelWidth = 20;

    public RadioConfig(Band band, String defaultSsid) {
        this.band = band;
        this.ssid = defaultSsid;

        // 밴드별 초기값
        if (band == Band.GHZ_24) { channel = 1;  channelWidth = 20; txPowerDbm = FIXED_TX_POWER_DBM; }
        if (band == Band.GHZ_5)  { channel = 36; channelWidth = 80; txPowerDbm = FIXED_TX_POWER_DBM; }
        if (band == Band.GHZ_6)  { channel = 1;  channelWidth = 80; txPowerDbm = FIXED_TX_POWER_DBM; }
    }

    public double centerFreqGhz() {
        return centerFreqGhz(band, channel);
    }

    /**
     * 채널 중심주파수 + 대역폭 절반(상단 edge)을 대표 주파수로 사용해
     * 대역폭 증가 시 추가 감쇠가 반영되도록 한다.
     */
    public double effectiveFreqGhzForLoss() {
        double center = centerFreqGhz();
        double halfBwGhz = Math.max(20, channelWidth) / 2000.0;
        return center + halfBwGhz;
    }

    /**
     * 대역폭 증가에 따른 수신 난이도(노이즈 대역 증가)를 간단히 dB 패널티로 반영.
     * 20MHz를 기준으로 10*log10(BW/20).
     */
    public double bandwidthPenaltyDb() {
        double bw = Math.max(20.0, channelWidth);
        return 10.0 * Math.log10(bw / 20.0);
    }

    public static double centerFreqGhz(Band band, int channel) {
        if (band == null) return 2.4;

        return switch (band) {
            case GHZ_24 -> {
                if (channel == 14) yield 2.484;
                yield 2.412 + 0.005 * (channel - 1);
            }
            case GHZ_5 -> (5000.0 + 5.0 * channel) / 1000.0;
            case GHZ_6 -> (5950.0 + 5.0 * channel) / 1000.0;
        };
    }
}
