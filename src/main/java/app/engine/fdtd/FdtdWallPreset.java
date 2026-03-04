package app.engine.fdtd;

/**
 * FDTD 재질 격자 구성 시 벽 재질을 어떤 기준으로 해석할지 결정하는 프리셋.
 */
public enum FdtdWallPreset {
    /**
     * 각 벽의 WallMaterial(문/창문/콘크리트/사용자지정 등)을 그대로 사용.
     */
    FROM_WALL("벽별 재질 사용"),

    /**
     * 모든 일반 벽을 석고보드 계열로 간주.
     * (문/창문은 항상 문/창문 재질로 유지)
     */
    GYPSUM("석고보드"),

    /**
     * 모든 일반 벽을 목재 계열로 간주.
     * (문/창문은 항상 문/창문 재질로 유지)
     */
    WOOD("목재"),

    /**
     * 모든 일반 벽을 콘크리트 계열로 간주.
     * (문/창문은 항상 문/창문 재질로 유지)
     */
    CONCRETE("콘크리트");

    private final String label;

    FdtdWallPreset(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

