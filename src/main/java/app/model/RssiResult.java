package app.model;

public class RssiResult {
    public final String apName;
    public final String ssid;
    public final Band band;
    public final double rssiDbm;

    public RssiResult(String apName, String ssid, Band band, double rssiDbm) {
        this.apName = apName;
        this.ssid = ssid;
        this.band = band;
        this.rssiDbm = rssiDbm;
    }

    public RssiResult(String ssid, Band band, double rssiDbm) {
        this(null, ssid, band, rssiDbm);
    }
}
