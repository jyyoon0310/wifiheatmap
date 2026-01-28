package app.model;

public class RssiResult {
    public final String ssid;
    public final Band band;
    public final double rssiDbm;

    public RssiResult(String ssid, Band band, double rssiDbm) {
        this.ssid = ssid;
        this.band = band;
        this.rssiDbm = rssiDbm;
    }
}