package app.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 주파수 대역과 대역별 규칙(쓸 수 있는 채널·대역폭, 기본값).
 *
 * <p>채널 목록·기본값은 예전엔 편집 패널(LeftPanel)과 RadioConfig 에 따로 적혀 있었다 —
 * 대역 규칙은 여기 한 곳에서 정한다.</p>
 */
public enum Band {
    GHZ_24("2.4 GHz", 2.4, 1, 20, List.of(20, 40)),
    GHZ_5("5 GHz", 5.0, 36, 80, List.of(20, 40, 80, 160)),
    GHZ_6("6 GHz", 6.0, 1, 80, List.of(20, 40, 80, 160, 320));

    public final String label;
    public final double freqGhz;
    /** 새 공유기의 기본 채널 */
    public final int defaultChannel;
    /** 새 공유기의 기본 대역폭 [MHz] */
    public final int defaultChannelWidthMhz;
    /** 고를 수 있는 대역폭 [MHz] */
    public final List<Integer> channelWidthsMhz;

    Band(String label, double freqGhz, int defaultChannel, int defaultChannelWidthMhz,
         List<Integer> channelWidthsMhz) {
        this.label = label;
        this.freqGhz = freqGhz;
        this.defaultChannel = defaultChannel;
        this.defaultChannelWidthMhz = defaultChannelWidthMhz;
        this.channelWidthsMhz = channelWidthsMhz;
    }

    /** 고를 수 있는 채널 번호 (20MHz 기본 채널) */
    public List<Integer> channels() {
        List<Integer> list = new ArrayList<>();
        switch (this) {
            case GHZ_24 -> { for (int ch = 1; ch <= 14; ch++) list.add(ch); }
            case GHZ_5 -> {
                for (int ch = 36; ch <= 64; ch += 4) list.add(ch);
                for (int ch = 100; ch <= 144; ch += 4) list.add(ch);
                for (int ch = 149; ch <= 177; ch += 4) list.add(ch);
            }
            case GHZ_6 -> { for (int ch = 1; ch <= 233; ch += 4) list.add(ch); }
        }
        return list;
    }

    @Override public String toString() { return label; }
}
