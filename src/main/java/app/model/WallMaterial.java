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
    BOOKSHELF("책장", "bookshelf", 2, 2, "#8D6E63", 4),
    CUBICLE("칸막이", "cubicle", 2, 2, "#607D8B", 4),
    DRY_WALL("석고보드", "drywall", 3, 3, "#B0BEC5", 6),
    BRICK_WALL("벽돌벽", "brick", 5, 15, "#C62828", 10),
    WINDOW("창문", "glass", 3, 9, "#26C6DA", 6),
    DOOR("문", "door", 3, 8, "#FB8C00", 6),
    ELEVATOR_SHAFT("엘리베이터 샤프트", "elevator", 10, 10, "#6A1B9A", 12),

    // ===== Backward compatibility / optional presets =====
    CONCRETE_WALL("콘크리트벽", "wall", 14, 14, "#424242", 10),

    // 사용자 지정(스피너로 직접 입력하는 경우)
    CUSTOM("사용자지정", "wall", 0, 0, "#212121", 8);

    private final String label;
    private final String kind;
    private final double attn24Db;
    private final double attn5Db;
    private final String colorHex;
    private final double reflLossDb;

    WallMaterial(String label, String kind, double attn24Db, double attn5Db, String colorHex, double reflLossDb) {
        this.label = label;
        this.kind = kind;
        this.attn24Db = attn24Db;
        this.attn5Db = attn5Db;
        this.colorHex = colorHex;
        this.reflLossDb = reflLossDb;
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

    public double attenuationDb(Band band) {
        if (band == null) return attn24Db;
        return (band == Band.GHZ_5) ? attn5Db : attn24Db;
    }
}