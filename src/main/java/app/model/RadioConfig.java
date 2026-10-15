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

        this.channel = band.defaultChannel;
        this.channelWidth = band.defaultChannelWidthMhz;
    }

    public double centerFreqGhz() {
        return centerFreqGhz(band, channel);
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
