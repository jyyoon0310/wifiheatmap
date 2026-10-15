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
 * 갱신할 것. 리팩터링(동작 보존)이라면 해시가 그대로여야 한다.</p>
 */
class HeatmapPixelsRegressionTest {

    @Test void simpleAllBands()  { check(GoldenDump.scenario(), 200, 200, 16, null,
            "f47aee86f1648e2461ca45ef1361c5555f175c12cf7242d05f8f7db7321fc298", false); }
    @Test void simple24()        { check(GoldenDump.scenario(), 200, 200, 16, Band.GHZ_24,
            "f47aee86f1648e2461ca45ef1361c5555f175c12cf7242d05f8f7db7321fc298", false); }
    @Test void simple5()         { check(GoldenDump.scenario(), 200, 200, 16, Band.GHZ_5,
            "ef22d5dab6719b07c28da0ae06c915f6ba13fceafee8902df3d69d94914c6e1e", false); }
    @Test void simple6()         { check(GoldenDump.scenario(), 200, 200, 16, Band.GHZ_6,
            "30ea8c5a41038274325236814f0ae5adce4bfe0dfaa1ccfcbbdfa3737f2e1a98", false); }

    // 복합 평면도는 외벽이 닫혀 있어 건물 마스크가 적용된다
    @Test void complexAllBands() { check(GoldenDump.scenarioComplex(), 420, 300, 12, null,
            "dfa567571db4979f0036d2b94b4d75d1db3dd344a63cd0ee25ddf8e8fa1b3899", true); }
    @Test void complex24()       { check(GoldenDump.scenarioComplex(), 420, 300, 12, Band.GHZ_24,
            "dfa567571db4979f0036d2b94b4d75d1db3dd344a63cd0ee25ddf8e8fa1b3899", true); }
    @Test void complex5()        { check(GoldenDump.scenarioComplex(), 420, 300, 12, Band.GHZ_5,
            "faa2fd04401922441d95678a0239a2a655e91065f29cb08a1e339643974bd818", true); }
    @Test void complex6()        { check(GoldenDump.scenarioComplex(), 420, 300, 12, Band.GHZ_6,
            "908d6938e1828a9a93254beecfe0a072d70364b6877f15126a985f91a4ec27b7", true); }

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
