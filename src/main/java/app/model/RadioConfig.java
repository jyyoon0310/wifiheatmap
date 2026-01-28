package app.model;

public class RadioConfig {
    public final Band band;

    public boolean enabled = true;

    // ✅ 밴드별 SSID (요구사항)
    public String ssid;

    public double txPowerDbm = 18;
    public double antennaGain = 2;

    public String mode = "ax";
    public int channel = 1;
    public int channelWidth = 20;
    public String security = "WPA2";

    public RadioConfig(Band band, String defaultSsid) {
        this.band = band;
        this.ssid = defaultSsid;

        // 밴드별 초기값
        if (band == Band.GHZ_24) { mode = "n";  channel = 1;  channelWidth = 20; security = "WPA2"; txPowerDbm = 18; }
        if (band == Band.GHZ_5)  { mode = "ax"; channel = 36; channelWidth = 80; security = "WPA2"; txPowerDbm = 18; }
        if (band == Band.GHZ_6)  { mode = "ax"; channel = 1;  channelWidth = 80; security = "WPA3"; txPowerDbm = 18; }
    }
}