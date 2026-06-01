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
    // interactionDb90: DPM 방향전환 손실 [dB/90°] — 재질이 단단할수록 회절 손실 큼
    //   Plets(2012) drywall 기준 5.0 dB/90°; 콘크리트·금속은 상향, 유리·목재는 하향
    BOOKSHELF("책장", "bookshelf", 2, 3, "#8D6E63", 4, 3.0, 1.99, 0.0, 0.0047, 1.0718), // Wood — interactionDb90=3.0
    CUBICLE("칸막이", "cubicle", 2, 3, "#607D8B", 4, 3.0, 1.48, 0.0, 0.0011, 1.0750), // Ceiling board — interactionDb90=3.0
    DRY_WALL("석고보드", "drywall", 3, 5, "#B0BEC5", 6, 5.0, 2.73, 0.0, 0.0085, 0.9395), // Plasterboard — Plets(2012) 기준값
    BRICK_WALL("벽돌벽", "brick", 6, 13, "#C62828", 10, 8.0, 3.91, 0.0, 0.0238, 0.16), // 단단, 강한 회절
    WINDOW("창문", "glass", 2, 4, "#26C6DA", 6, 3.0, 6.31, 0.0, 0.0036, 1.3394), // 투명, 낮은 회절
    DOOR("문", "door", 3, 5, "#FB8C00", 6, 4.0, 1.99, 0.0, 0.0047, 1.0718), // 석고보드 유사
    ELEVATOR_SHAFT("엘리베이터 샤프트", "elevator", 13, 15, "#6A1B9A", 1, 12.0, 1.0, 0.0, 1.0e7, 0.0), // 금속, 최대 회절

    // ===== Backward compatibility / optional presets =====
    CONCRETE_WALL("콘크리트벽", "wall", 22, 26, "#424242", 8, 17.5, 5.24, 0.0, 0.0462, 0.7822), // 한국 아파트 외벽(20~25cm 철근 콘크리트) 실측 기준: 2.4GHz 22dB, 5GHz 26dB. cornerLossDb90=17.5 per Plets 2012

    // 사용자 지정(스피너로 직접 입력하는 경우)
    CUSTOM("사용자지정", "wall", 0, 0, "#212121", 8, 5.0, Double.NaN, 0.0, 0.0, 0.0);

    private final String label;
    private final String kind;
    private final double attn24Db;
    private final double attn5Db;
    private final String colorHex;
    private final double reflLossDb;
    /** DPM 방향전환 손실 [dB/90°]. 재질이 단단할수록 크다 (열린공간 기본값=2.0). */
    private final double interactionDb90;
    private final double epsA;
    private final double epsB;
    private final double sigC;
    private final double sigD;

    WallMaterial(String label, String kind,
                 double attn24Db, double attn5Db,
                 String colorHex, double reflLossDb,
                 double interactionDb90,
                 double epsA, double epsB,
                 double sigC, double sigD) {
        this.label = label;
        this.kind = kind;
        this.attn24Db = attn24Db;
        this.attn5Db = attn5Db;
        this.colorHex = colorHex;
        this.reflLossDb = reflLossDb;
        this.interactionDb90 = interactionDb90;
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

    /**
     * DPM 경로가 이 재질 옆에서 90° 꺾일 때의 방향전환 손실 [dB/90°].
     * Plets(2012) 드라이월 기준 5.0 dB/90°를 기준으로 재질별 조정.
     * 열린 공간(벽 없음)의 기본값은 DpmPathGrid.DEFAULT_INTERACTION_DB90 = 2.0 dB/90°.
     */
    public double cornerLossDb90() {
        return interactionDb90;
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
