package app.engine.fdtd;

/**
 * FDTD RMS 전계(Ez)를 상대 dB로 정규화할 때 사용할 기준(reference) 선택.
 */
public enum FdtdReferenceMode {
    /**
     * AP 소스 주변(작은 반경)의 RMS 평균을 기준으로 사용.
     * AP 근처에서 상대적으로 0 dB가 되도록 보정된다.
     */
    AP_NEAR_RING("AP 근처 평균"),

    /**
     * 계산 영역 전체 최대 RMS를 기준으로 사용.
     * 전체 맵에서 가장 강한 지점이 0 dB가 된다.
     */
    GLOBAL_MAX("전역 최대"),

    /**
     * 사용자가 지정한 reference 값을 그대로 사용.
     */
    CUSTOM("사용자 지정");

    private final String label;

    FdtdReferenceMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

