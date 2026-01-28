package app.model;

import java.util.EnumMap;
import java.util.Map;

public class AP {
    // 물리 AP 이름(표시용)
    public String name = "AP-1";

    public double x, y;

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