package app.model;

import java.util.EnumMap;
import java.util.Map;

public class AP {
    // 물리 AP 이름(표시용)
    public String name = "AP-1";

    public double x, y;
    public double heightM = 1.0;

    // 물리 AP 전체 on/off
    public boolean enabled = true;

    // 밴드별 라디오
    public final Map<Band, RadioConfig> radios = new EnumMap<>(Band.class);

    public AP() {
        // 밴드별 SSID 기본값(원하면 규칙 바꿔도 됨)
        radios.put(Band.GHZ_24, new RadioConfig(Band.GHZ_24, name + "_24G"));
        radios.put(Band.GHZ_5,  new RadioConfig(Band.GHZ_5,  name + "_5G"));
        radios.put(Band.GHZ_6,  new RadioConfig(Band.GHZ_6,  name + "_6G"));
    }

    /**
     * 밴드별 SSID 를 현재 이름 기준 기본값(이름_24G/_5G/_6G)으로 맞춘다.
     * 생성자는 기본 이름("AP-1")으로 SSID 를 만들므로, 생성 후 이름을 바꿨다면 호출해야
     * 여러 AP 가 같은 SSID 를 갖지 않는다.
     */
    public void resetSsidsFromName() {
        RadioConfig r24 = radios.get(Band.GHZ_24);
        RadioConfig r5  = radios.get(Band.GHZ_5);
        RadioConfig r6  = radios.get(Band.GHZ_6);
        if (r24 != null) r24.ssid = name + "_24G";
        if (r5  != null) r5.ssid  = name + "_5G";
        if (r6  != null) r6.ssid  = name + "_6G";
    }

    /**
     * 화면에서 새로 놓는 공유기의 기본 구성 — 2.4/5 GHz 듀얼밴드 (6 GHz 끔).
     * 집에서 쓰는 공유기는 대부분 듀얼밴드이고, 6 GHz 는 Wi-Fi 6E/7 프리셋을 고르면 켜진다.
     * (생성자 기본값은 엔진 테스트 시나리오가 세 밴드를 모두 쓰므로 그대로 둔다.)
     */
    public void useDualBandDefaults() {
        RadioConfig r6 = radios.get(Band.GHZ_6);
        if (r6 != null) r6.enabled = false;
    }

    @Override
    public String toString() {
        // 리스트에서 보이기: 켜진 밴드만 요약
        StringBuilder sb = new StringBuilder(name + " ");
        for (Band b : Band.values()) {
            RadioConfig r = radios.get(b);
            if (r != null && enabled && r.enabled) sb.append("[").append(b.label).append("] ");
        }
        return sb.toString().trim();
    }
}
