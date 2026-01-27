package app;

public enum Band {
    GHZ_24("2.4 GHz", 2.4),
    GHZ_5("5 GHz", 5.0),
    GHZ_6("6 GHz", 6.0);

    public final String label;
    public final double freqGhz;

    Band(String label, double freqGhz) {
        this.label = label;
        this.freqGhz = freqGhz;
    }

    @Override public String toString() { return label; }
}