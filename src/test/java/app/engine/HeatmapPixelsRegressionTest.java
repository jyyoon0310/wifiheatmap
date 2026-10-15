package app.engine;

import app.model.Band;
import app.model.WifiEnvironment;
import app.tools.GoldenDump;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 신호 지도 픽셀 회귀 테스트 — {@link HeatmapGenerator} 출력(ARGB 버퍼)의 SHA-256 이 기준값과 같은지.
 *
 * <p>{@link GoldenRegressionTest} 는 경로 손실 격자만 보므로, 그 위의 색칠·표본 평균·건물 마스크
 * 단계가 바뀌어도 잡지 못한다. 이 테스트가 그 단계를 지킨다.</p>
 *
 * <p>⚠️ 색 기준({@code RssiLevel})이나 신호 계산을 <b>의도적으로</b> 바꿨다면 기준 해시를 다시 뽑아
 * 갱신할 것. 리팩터링(동작 보존)이라면 해시가 그대로여야 한다.
 * (마지막 갱신: DPM 문헌 보정 — 대역폭 보정 제거·상태별 지수·수신 장비 손실, docs/dpm-calibration.md)</p>
 */
class HeatmapPixelsRegressionTest {

    @Test void simpleAllBands()  { check(GoldenDump.scenario(), 200, 200, 16, null,
            "6e538ae416d6452b2c6c4ae2ffaba548fc759fe8d3d2fb9ec70b5aabbf943f11", false); }
    @Test void simple24()        { check(GoldenDump.scenario(), 200, 200, 16, Band.GHZ_24,
            "6e538ae416d6452b2c6c4ae2ffaba548fc759fe8d3d2fb9ec70b5aabbf943f11", false); }
    @Test void simple5()         { check(GoldenDump.scenario(), 200, 200, 16, Band.GHZ_5,
            "5863bd689818ed188ab2b9b0e0a761df45ef7b4598cfb38a6b8cd873f572380b", false); }
    @Test void simple6()         { check(GoldenDump.scenario(), 200, 200, 16, Band.GHZ_6,
            "73b255e39af833b492a6e766c04fc483eb96be625959e8a05168a9460af43e19", false); }

    // 복합 평면도는 외벽이 닫혀 있어 건물 마스크가 적용된다
    @Test void complexAllBands() { check(GoldenDump.scenarioComplex(), 420, 300, 12, null,
            "8cc2178606dd61f71cc63775f76453143bf2f2f97581a9594b964a9125577022", true); }
    @Test void complex24()       { check(GoldenDump.scenarioComplex(), 420, 300, 12, Band.GHZ_24,
            "8cc2178606dd61f71cc63775f76453143bf2f2f97581a9594b964a9125577022", true); }
    @Test void complex5()        { check(GoldenDump.scenarioComplex(), 420, 300, 12, Band.GHZ_5,
            "4e2e8da07559b6430e5b768ebdd5a2e7ce6b6a59a30766544654b1626bfe0a53", true); }
    @Test void complex6()        { check(GoldenDump.scenarioComplex(), 420, 300, 12, Band.GHZ_6,
            "836189d2f5d0d4482f23cbfb98b9c1bd3fab2dd855487ca73d4b5746325a6ac3", true); }

    private static void check(WifiEnvironment env, int w, int h, int step, Band band,
                              String expectedSha256, boolean expectMask) {
        HeatmapGenerator.HeatmapResult r = new HeatmapGenerator(env, band).generate(w, h, step, 0, null);
        assertEquals(expectedSha256, sha256(r.argbPixels), "신호 지도 픽셀이 기준과 다르다");
        if (expectMask) assertNotNull(r.buildingMask); else assertNull(r.buildingMask);
    }

    private static String sha256(int[] argb) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int p : argb) {
                md.update((byte) (p >>> 24));
                md.update((byte) (p >>> 16));
                md.update((byte) (p >>> 8));
                md.update((byte) p);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
