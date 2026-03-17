package app.model;

/**
 * Material preset with per-band attenuation (2.4GHz / 5GHz) and a display color.
 *
 * - kind(): 문자열 태그(리스트 표시/분류용)
 * - labelKo(): UI 표시 라벨(영문으로 둬도 OK)
 * - defaultAttenuation24Db(), defaultAttenuation5Db(): 밴드별 기본 감쇠(dB)
 * - defaultAttenuationDb(): 레거시 호환용(2.4GHz로 취급)
 * - labelWithAttn(): "Door (3/8dB)" 같은 드롭다운 표시용
 * - colorHex(): 벽 선분 렌더링 색상
 */
public enum WallMaterial {

    // ===== Screenshot presets (2.4 / 5 GHz) =====
    BOOKSHELF("책장", "bookshelf", 2, 2, "#8D6E63", 4, 1.99, 0.0, 0.0047, 1.0718), // Wood
    CUBICLE("칸막이", "cubicle", 2, 2, "#607D8B", 4, 1.48, 0.0, 0.0011, 1.0750), // Ceiling board
    DRY_WALL("석고보드", "drywall", 3, 3, "#B0BEC5", 6, 2.73, 0.0, 0.0085, 0.9395), // Plasterboard
    BRICK_WALL("벽돌벽", "brick", 5, 15, "#C62828", 10, 3.91, 0.0, 0.0238, 0.16),
    // 일반 유리창(저방사 코팅 없는 clear glass) 기준 기본치: 2.4GHz 2dB, 5GHz 4dB
    WINDOW("창문", "glass", 2, 4, "#26C6DA", 6, 6.31, 0.0, 0.0036, 1.3394),
    // 실내 목재문 기준 기본치(여러 실측 가이드 범위의 중앙값에 가깝게 설정): 약 3~4dB(2.4), 4~6dB(5)
    DOOR("문", "door", 3, 5, "#FB8C00", 6, 1.99, 0.0, 0.0047, 1.0718), // Wood
    ELEVATOR_SHAFT("엘리베이터 샤프트", "elevator", 10, 10, "#6A1B9A", 12, 1.0, 0.0, 1.0e7, 0.0), // Metal

    // ===== Backward compatibility / optional presets =====
    CONCRETE_WALL("콘크리트벽", "wall", 14, 14, "#424242", 10, 5.24, 0.0, 0.0462, 0.7822),

    // 사용자 지정(스피너로 직접 입력하는 경우)
    CUSTOM("사용자지정", "wall", 0, 0, "#212121", 8, Double.NaN, 0.0, 0.0, 0.0);

    private final String label;
    private final String kind;
    private final double attn24Db;
    private final double attn5Db;
    private final String colorHex;
    private final double reflLossDb;
    private final double epsA;
    private final double epsB;
    private final double sigC;
    private final double sigD;

    WallMaterial(String label, String kind,
                 double attn24Db, double attn5Db,
                 String colorHex, double reflLossDb,
                 double epsA, double epsB,
                 double sigC, double sigD) {
        this.label = label;
        this.kind = kind;
        this.attn24Db = attn24Db;
        this.attn5Db = attn5Db;
        this.colorHex = colorHex;
        this.reflLossDb = reflLossDb;
        this.epsA = epsA;
        this.epsB = epsB;
        this.sigC = sigC;
        this.sigD = sigD;
    }

    public String kind() {
        return kind;
    }

    // 기존 코드 호환: labelKo()라는 이름을 쓰고 있을 수 있음
    public String labelKo() {
        return label;
    }

    public double defaultAttenuation24Db() {
        return attn24Db;
    }

    public double defaultAttenuation5Db() {
        return attn5Db;
    }

    /** 레거시 호환: 2.4GHz 기본값으로 취급 */
    public double defaultAttenuationDb() {
        return attn24Db;
    }

    public String colorHex() {
        return colorHex;
    }

    /** 드롭다운 표시용: 예) 문(3dB/8dB)  -> (2.4GHz/5GHz) */
    public String labelWithAttn() {
        return String.format("%s(%.0fdB/%.0fdB)", label, attn24Db, attn5Db); // d
    }

    /** 1차 반사 시 재질별 추가 손실(dB) */
    public double reflectionLossDb() {
        return reflLossDb;
    }

    public boolean hasPermittivityModel() {
        return Double.isFinite(epsA);
    }

    /** ITU-R P.2040 Table 3: eps' = a * f^b (f in GHz) */
    public double epsilonReal(double freqGhz) {
        if (!hasPermittivityModel() || !Double.isFinite(freqGhz)) return Double.NaN;
        return epsA * Math.pow(freqGhz, epsB);
    }

    /** ITU-R P.2040 Table 3: sigma = c * f^d (f in GHz, S/m) */
    public double conductivity(double freqGhz) {
        if (!hasPermittivityModel() || !Double.isFinite(freqGhz)) return Double.NaN;
        return sigC * Math.pow(freqGhz, sigD);
    }

    public double attenuationDb(Band band) {
        if (band == null) return attn24Db;
        if (band == Band.GHZ_5 || band == Band.GHZ_6) return attn5Db;
        return attn24Db;
    }
}
