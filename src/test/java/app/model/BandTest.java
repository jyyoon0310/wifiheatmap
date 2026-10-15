package app.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 대역 규칙 — 편집 패널에 있던 채널·대역폭 목록과 기본값이 그대로인지. */
class BandTest {

    @Test
    void channelLists() {
        List<Integer> c24 = Band.GHZ_24.channels();
        assertEquals(14, c24.size());
        assertEquals(1, c24.get(0));
        assertEquals(14, c24.get(13));

        List<Integer> c5 = Band.GHZ_5.channels();
        assertEquals(8 + 12 + 8, c5.size());   // 36~64, 100~144, 149~177
        assertEquals(36, c5.get(0));
        assertTrue(c5.contains(100) && c5.contains(149));
        assertEquals(177, c5.get(c5.size() - 1));

        List<Integer> c6 = Band.GHZ_6.channels();
        assertEquals(59, c6.size());            // 1~233, 4 간격
        assertEquals(233, c6.get(c6.size() - 1));
    }

    @Test
    void defaultsMatchNewRadio() {
        for (Band b : Band.values()) {
            RadioConfig rc = new RadioConfig(b, "x");
            assertEquals(b.defaultChannel, rc.channel, b.name());
            assertEquals(b.defaultChannelWidthMhz, rc.channelWidth, b.name());
            assertTrue(b.channels().contains(rc.channel), b.name());
            assertTrue(b.channelWidthsMhz.contains(rc.channelWidth), b.name());
            assertEquals(RadioConfig.FIXED_TX_POWER_DBM, rc.txPowerDbm);
        }
        assertEquals(1, Band.GHZ_24.defaultChannel);
        assertEquals(36, Band.GHZ_5.defaultChannel);
        assertEquals(20, Band.GHZ_24.defaultChannelWidthMhz);
        assertEquals(80, Band.GHZ_5.defaultChannelWidthMhz);
        assertEquals(List.of(20, 40, 80, 160, 320), Band.GHZ_6.channelWidthsMhz);
    }
}
