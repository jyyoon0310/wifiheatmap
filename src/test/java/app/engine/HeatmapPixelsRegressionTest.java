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
 * (마지막 갱신: 신호 등급을 업계 기준(MetaGeek −67/−70/−80 dBm)으로 바꾸며 색 정지점 이동)</p>
 */
class HeatmapPixelsRegressionTest {

    @Test void simpleAllBands()  { check(GoldenDump.scenario(), 200, 200, 16, null,
            "2b4dddc5ef4f2eed8831740c074f82c822d7cccaedadfbfb1ca9390cf19216f1", false); }
    @Test void simple24()        { check(GoldenDump.scenario(), 200, 200, 16, Band.GHZ_24,
            "2b4dddc5ef4f2eed8831740c074f82c822d7cccaedadfbfb1ca9390cf19216f1", false); }
    @Test void simple5()         { check(GoldenDump.scenario(), 200, 200, 16, Band.GHZ_5,
            "a084ea1339d5ea8307992e0d2485006510bddf920cc1f2c109c80504a81f9e2c", false); }
    @Test void simple6()         { check(GoldenDump.scenario(), 200, 200, 16, Band.GHZ_6,
            "a39e3f54282a0d0c904ab187989beb14e6100172a4b71fecc1076515724f016a", false); }

    // 복합 평면도는 외벽이 닫혀 있어 건물 마스크가 적용된다
    @Test void complexAllBands() { check(GoldenDump.scenarioComplex(), 420, 300, 12, null,
            "93890587539bf4d9cd7651b1984d9827b3271f0951a885280181f401364887c9", true); }
    @Test void complex24()       { check(GoldenDump.scenarioComplex(), 420, 300, 12, Band.GHZ_24,
            "93890587539bf4d9cd7651b1984d9827b3271f0951a885280181f401364887c9", true); }
    @Test void complex5()        { check(GoldenDump.scenarioComplex(), 420, 300, 12, Band.GHZ_5,
            "2141f205c4d4c5167abbdf5dcb5d06a623404b5011cb293e945081c2f494064f", true); }
    @Test void complex6()        { check(GoldenDump.scenarioComplex(), 420, 300, 12, Band.GHZ_6,
            "0514153aeb1fee52f85998dedb54f70916e7f35bd808f610362bd5f87daba017", true); }

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
