package app;

/**
 * Wall(선분) + 재질 기반 감쇠 모델.
 */
public class Wall {
    public double x1, y1, x2, y2;

    private WallMaterial material = WallMaterial.CUSTOM;

    // Per-band attenuation (dB)
    public double attenuationDb24 = material.defaultAttenuationDb();
    public double attenuationDb5  = material.defaultAttenuationDb();

    // Backward compatible (treat as 2.4GHz)
    public double attenuationDb   = attenuationDb24;

    public String kind = material.kind();

    public Wall() {}

    public Wall(double x1, double y1, double x2, double y2, WallMaterial material) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        setMaterial(material);
    }

    public void setMaterial(WallMaterial material) {
        if (material == null) material = WallMaterial.CUSTOM;
        this.material = material;

        // NOTE: For now we initialize both bands from the preset's defaultAttenuationDb().
        // (After WallMaterial is updated to expose 2.4/5 separately, we will map them here.)
        this.attenuationDb24 = material.defaultAttenuation24Db();
        this.attenuationDb5  = material.defaultAttenuation5Db();
        this.attenuationDb   = this.attenuationDb24; // legacy

        this.kind = material.kind();
    }

    public WallMaterial getMaterial() {
        return material;
    }

    /** Backward compatible: sets BOTH bands to the same value (dB). */
    public void setAttenuationDb(double attenuationDb) {
        setAttenuationDb(attenuationDb, attenuationDb);
    }

    /** Sets 2.4GHz / 5GHz attenuation separately (dB). */
    public void setAttenuationDb(double attenuationDb24, double attenuationDb5) {
        this.attenuationDb24 = attenuationDb24;
        this.attenuationDb5 = attenuationDb5;
        this.attenuationDb = this.attenuationDb24;
        this.material = WallMaterial.CUSTOM;
        this.kind = WallMaterial.CUSTOM.kind();
    }

    /** Returns attenuation for the given band (defaults to 2.4GHz if null). */
    public double attenuationDb(Band band) {
        if (band == null) return attenuationDb24;
        return (band == Band.GHZ_5) ? attenuationDb5 : attenuationDb24;
    }

    @Override
    public String toString() {
        return String.format(
                "%s/%s (%.0f,%.0f)-(%.0f,%.0f) %.0f/%.0f dB",
                kind, material.labelKo(), x1, y1, x2, y2, attenuationDb24, attenuationDb5
        );
    }
}