package app.engine;

import app.model.ApPreset;
import app.model.Band;
import app.model.WifiEnvironment;
import app.tools.GoldenDump;
import javafx.geometry.Point2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 공유기 자리 추천 회귀 테스트 — 복합 평면도에서 그리디(DPM) 추천 위치·커버율이 기준과 같은지.
 *
 * <p>FDTD 정밀 검증은 끈다 (느리고, 검증 단계는 위치를 옮기는 피드백만 한다).
 * 기준값은 리팩터링 1단계 직전 커밋(d1071ce)의 결과로, 같은 입력을 여러 번 돌려도 같게 나오는 것을 확인했다.</p>
 *
 * <p>⚠️ 추천 알고리즘이나 신호 계산(LinkBudget·DPM)을 <b>의도적으로</b> 바꿨다면 기준값을 갱신할 것.</p>
 */
class ApRecommenderRegressionTest {

    @Test void customTarget65_three() {
        check(ApPreset.CUSTOM, -65.0, 3, 100.0, pts(270, 150, 210, 30, 90, 210));
    }

    @Test void customTarget50_one() {
        check(ApPreset.CUSTOM, -50.0, 1, 99.1903, pts(270, 150));
    }

    @Test void customTarget45_two() {
        // 1대로는 89% — 두 번째 공유기가 미커버 구역 쪽으로 가는지
        check(ApPreset.CUSTOM, -45.0, 2, 100.0, pts(270, 150, 300, 30));
    }

    @Test void wifi7Target45_three() {
        // 프리셋(EIRP 가 더 큼)이 평가에 반영되는지 — CUSTOM 과 다른 자리가 나온다
        check(ApPreset.LGU_WIFI7, -45.0, 3, 100.0, pts(270, 150, 360, 60, 150, 90));
    }

    private static void check(ApPreset preset, double targetDbm, int count,
                              double expectedCoverage, List<Point2D> expected) {
        WifiEnvironment env = GoldenDump.scenarioComplex();
        env.getAps().clear();
        ApRecommender.Params params = new ApRecommender.Params(
                count, targetDbm, 30, 20,
                false, 6000, Band.GHZ_5,   // FDTD 검증 끔
                null, 0, 0, 1,             // 마스크 없음 = 캔버스 전체
                true, true, 150.0, preset);
        ApRecommender.Result r = ApRecommender.recommend(env, GoldenDump.CW, GoldenDump.CH, params, null);

        assertEquals(expected, r.positions(), "추천 위치가 기준과 다르다");
        assertEquals(expectedCoverage, r.coveragePercent(), 1e-3, "커버율이 기준과 다르다");
    }

    private static List<Point2D> pts(double... xy) {
        Point2D[] out = new Point2D[xy.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = new Point2D(xy[2 * i], xy[2 * i + 1]);
        return List.of(out);
    }
}
