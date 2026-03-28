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

    // ===== 재질 프리셋 (ITU-R P.2040 + NIST IR 6055 + COST 231 실측 기반) =====
    // 반사 손실: Fresnel 계수 기반 (낮을수록 반사 강함)
    BOOKSHELF("책장", "bookshelf", 2, 3, "#8D6E63", 4, 1.99, 0.0, 0.0047, 1.0718), // Wood — 5GHz: 2→3 (문헌 3-4)
    CUBICLE("칸막이", "cubicle", 2, 3, "#607D8B", 4, 1.48, 0.0, 0.0011, 1.0750), // Ceiling board — 5GHz: 2→3
    DRY_WALL("석고보드", "drywall", 3, 5, "#B0BEC5", 6, 2.73, 0.0, 0.0085, 0.9395), // Plasterboard — 5GHz: 3→5 (문헌 4-6)
    BRICK_WALL("벽돌벽", "brick", 6, 13, "#C62828", 10, 3.91, 0.0, 0.0238, 0.16), // 2.4: 5→6, 5GHz: 15→13 (NIST 6-12 / 12-18 중앙)
    WINDOW("창문", "glass", 2, 4, "#26C6DA", 6, 6.31, 0.0, 0.0036, 1.3394), // OK (문헌 1-3 / 3-5)
    DOOR("문", "door", 3, 5, "#FB8C00", 6, 1.99, 0.0, 0.0047, 1.0718), // OK (문헌 3-6 / 4-8)
    ELEVATOR_SHAFT("엘리베이터 샤프트", "elevator", 13, 15, "#6A1B9A", 1, 1.0, 0.0, 1.0e7, 0.0), // 반사 12→1 (금속 거의 완전 반사), 감쇠 10→13/15

    // ===== Backward compatibility / optional presets =====
    CONCRETE_WALL("콘크리트벽", "wall", 14, 18, "#424242", 8, 5.24, 0.0, 0.0462, 0.7822), // 5GHz: 18 (NIST 102mm=22, 아파트 내벽 120-150mm 기준 보정)

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
