package app.engine;

import app.tools.GoldenDump;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 엔진 수치 회귀 테스트 — 리팩터링이 계산 결과를 바꾸지 않았음을 보증한다.
 *
 * <p>기준값은 {@link GoldenDump} 가 덤프한 {@code test/resources/golden/*.txt} 이며,
 * 각 값은 {@code Double.toHexString} 으로 기록되어 비트 단위로 복원된다.</p>
 *
 * <p><b>사용법</b> — 성능 최적화처럼 "동작은 그대로, 속도만" 인 변경에서는
 * 골든을 재생성하지 말고 이 테스트를 그대로 통과시켜야 한다.
 * 모델을 의도적으로 바꿨을 때만 {@code ./gradlew goldenDump} 로 갱신하고,
 * 그 변경 내역을 커밋 메시지에 남긴다.</p>
 *
 * <p>계산 로직은 {@link GoldenDump} 의 public 메서드를 그대로 호출한다 —
 * 테스트가 로직을 복제하면 덤퍼와 따로 놀 수 있기 때문이다.
 * (안드로이드 {@code GoldenParityTest} 는 별도 코드베이스라 불가피하게 복제한다.)</p>
 */
@DisplayName("엔진 골든 회귀")
class GoldenRegressionTest {

    /**
     * 허용오차 0 — 동일 JVM·동일 코드이므로 비트 일치를 요구한다.
     * 부동소수 재결합(연산 순서 변경)조차 잡아내야 리팩터링 검증으로 의미가 있다.
     */
    private static final double TOL_DB = 0.0;

    // ── 기본 시나리오 (안드로이드 :engine 과 공유하는 정의) ──────────────────

    @Test
    @DisplayName("DPM RSSI 격자 — 기본 시나리오")
    void dpmBaseUnchanged() throws Exception {
        assertGridMatches("DPM(기본)",
                readGolden("/golden/golden_dpm.txt"),
                GoldenDump.dpmGrid(GoldenDump.scenario()));
    }

    @Test
    @DisplayName("Legacy RSSI 격자 — 기본 시나리오")
    void legacyBaseUnchanged() throws Exception {
        assertGridMatches("Legacy(기본)",
                readGolden("/golden/golden_legacy.txt"),
                GoldenDump.legacyGrid(GoldenDump.scenario()));
    }

    // ── complex 시나리오 (복도·문틈·재질 6종·사선벽) ─────────────────────────

    @Test
    @DisplayName("DPM RSSI 격자 — complex 시나리오")
    void dpmComplexUnchanged() throws Exception {
        assertGridMatches("DPM(complex)",
                readGolden("/golden/golden_dpm_complex.txt"),
                GoldenDump.dpmGrid(GoldenDump.scenarioComplex(),
                        GoldenDump.CW, GoldenDump.CH, GoldenDump.CSTEP));
    }

    @Test
    @DisplayName("Legacy RSSI 격자 — complex 시나리오")
    void legacyComplexUnchanged() throws Exception {
        assertGridMatches("Legacy(complex)",
                readGolden("/golden/golden_legacy_complex.txt"),
                GoldenDump.legacyGrid(GoldenDump.scenarioComplex(),
                        GoldenDump.CW, GoldenDump.CH, GoldenDump.CSTEP));
    }

    /**
     * DpmPathGrid 경로손실 원본 — AP·밴드 max 를 거치지 않으므로 가장 민감하다.
     * 엣지 비용 테이블화 등 DpmPathGrid 내부 최적화는 이 테스트로 검증한다.
     */
    @Test
    @DisplayName("DPM 경로손실 원본 격자 — AP·밴드별 전량")
    void dpmPathLossUnchanged() throws Exception {
        assertGridMatches("DPM 경로손실",
                readGolden("/golden/golden_dpm_pathloss.txt"),
                GoldenDump.dpmPathLossGrid(GoldenDump.scenarioComplex(),
                        GoldenDump.CW, GoldenDump.CH, GoldenDump.CSTEP));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private static double[] readGolden(String resource) throws Exception {
        InputStream in = GoldenRegressionTest.class.getResourceAsStream(resource);
        assertNotNull(in, "골든 리소스 누락: " + resource + " — ./gradlew goldenDump 로 생성");
        List<Double> vals = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) vals.add(Double.parseDouble(line)); // 0x1.xp 표기 그대로 파싱
            }
        }
        double[] a = new double[vals.size()];
        for (int i = 0; i < a.length; i++) a[i] = vals.get(i);
        return a;
    }

    private static void assertGridMatches(String label, double[] golden, double[] actual) {
        assertEquals(golden.length, actual.length, label + ": 격자점 개수 불일치");

        double maxDiff = 0.0;
        int worst = -1;
        int mismatches = 0;
        for (int i = 0; i < golden.length; i++) {
            double d = Math.abs(golden[i] - actual[i]);
            if (d > TOL_DB) mismatches++;
            if (d > maxDiff) { maxDiff = d; worst = i; }
        }

        if (mismatches > 0) {
            throw new AssertionError(String.format(
                    "%s: %d/%d 점이 기준과 다름 (최대 %.6g dB, 점 #%d: 기준 %.9f → 실제 %.9f).%n" +
                    "동작 보존 리팩터링이라면 버그다. 모델을 의도적으로 바꿨다면 " +
                    "./gradlew goldenDump 로 골든을 갱신하고 변경 내역을 커밋에 남길 것.",
                    label, mismatches, golden.length, maxDiff, worst,
                    golden[worst], actual[worst]));
        }
    }
}
