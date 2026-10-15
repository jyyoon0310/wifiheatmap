package app.engine;

import javafx.geometry.Point2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 정밀 검증 피드백 — 약한 지점 쪽으로 공유기를 옮기는 규칙. */
class RefineApPositionsTest {

    @Test
    void eachRouterMovesOnlyTowardItsOwnWeakPoints() {
        Point2D left = new Point2D(100, 200), right = new Point2D(500, 200);
        // 약한 지점은 오른쪽 공유기 근처에만 있다
        List<Point2D> measure = List.of(new Point2D(560, 200), new Point2D(570, 210), new Point2D(565, 190));
        List<Integer> weak = List.of(0, 1, 2);

        List<Point2D> out = ApRecommender.refineApPositions(List.of(left, right), measure, weak,
                null, 0, 0, 1, List.of(), 20);

        assertEquals(left, out.get(0), "약한 지점을 맡지 않은 공유기는 그대로");
        assertTrue(out.get(1).getX() > right.getX(), "오른쪽 공유기는 약한 지점 쪽(오른쪽)으로");
        assertTrue(out.get(1).distance(right) <= 40.0 + 1e-9, "한 번에 gridStep×2 이하로만 이동");
    }

    @Test
    void routersDoNotCollapseTogether() {
        Point2D a = new Point2D(100, 100), b = new Point2D(400, 100);
        // 약한 지점이 양쪽 바깥에 하나씩 — 예전 규칙은 둘 다 전체 중심(가운데)으로 끌어당겼다
        List<Point2D> measure = List.of(new Point2D(40, 100), new Point2D(460, 100));
        List<Point2D> out = ApRecommender.refineApPositions(List.of(a, b), measure, List.of(0, 1),
                null, 0, 0, 1, List.of(), 20);

        assertTrue(out.get(0).getX() < a.getX(), "왼쪽 공유기는 왼쪽으로");
        assertTrue(out.get(1).getX() > b.getX(), "오른쪽 공유기는 오른쪽으로");
    }
}
