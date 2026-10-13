package app.engine;

import app.model.Wall;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 건물 안쪽 영역 마스크 — 히트맵을 건물 안에만 칠하기 위해 쓴다.
 *
 * <p>벽을 격자에 굵게 그린 뒤, 캔버스 가장자리에서 출발해 벽에 막힐 때까지 채운 영역을
 * "바깥"으로 본다. 나머지(벽 포함)가 안쪽이다. AP 추천의 방 선택 Flood Fill 과 같은 원리.</p>
 *
 * <p>외벽에 틈이 있으면 바깥이 안으로 새어 들어와 안쪽이 엘리베이터 샤프트 같은 작은 칸만
 * 남는다. 그런 경우 잘못 자르면 히트맵이 거의 사라지므로, 안쪽 면적이 벽들의 경계 상자의
 * {@link #MIN_INTERIOR_OF_BBOX} 미만이면 마스크를 만들지 않는다(= 자르지 않고 전체 표시).</p>
 */
public final class BuildingMask {

    /** 마스크 격자 한 칸 [px]. AP 추천 Flood Fill 과 같은 값. */
    public static final int CELL_PX = 4;
    /** 벽 두께 [칸]. 벽 끝이 조금 떨어져 있어도 막히도록 굵게 그린다. */
    private static final int WALL_THICK = 2;
    /** 안쪽 면적 / 벽 경계 상자 면적 의 최소값. 이보다 작으면 외벽이 열린 것으로 본다. */
    static final double MIN_INTERIOR_OF_BBOX = 0.30;

    private final boolean[] inside;
    private final int mw, mh;

    private BuildingMask(boolean[] inside, int mw, int mh) {
        this.inside = inside;
        this.mw = mw;
        this.mh = mh;
    }

    /**
     * @return 안쪽 마스크. 벽이 없거나 외벽이 닫혀 있지 않아 안쪽을 판정할 수 없으면 null.
     */
    public static BuildingMask compute(List<Wall> walls, int widthPx, int heightPx) {
        if (walls == null || walls.isEmpty() || widthPx <= 0 || heightPx <= 0) return null;
        int mw = (widthPx + CELL_PX - 1) / CELL_PX;
        int mh = (heightPx + CELL_PX - 1) / CELL_PX;

        boolean[] wall = new boolean[mw * mh];
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Wall w : walls) {
            if (w == null) continue;
            ApRecommender.rasterWall(wall, mw, mh,
                    (int) (w.x1 / CELL_PX), (int) (w.y1 / CELL_PX),
                    (int) (w.x2 / CELL_PX), (int) (w.y2 / CELL_PX), WALL_THICK);
            minX = Math.min(minX, Math.min(w.x1, w.x2)); maxX = Math.max(maxX, Math.max(w.x1, w.x2));
            minY = Math.min(minY, Math.min(w.y1, w.y2)); maxY = Math.max(maxY, Math.max(w.y1, w.y2));
        }

        // 가장자리의 벽 아닌 칸에서 동시에 BFS → 바깥
        boolean[] outside = new boolean[mw * mh];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int x = 0; x < mw; x++) { seed(q, outside, wall, x, 0, mw); seed(q, outside, wall, x, mh - 1, mw); }
        for (int y = 0; y < mh; y++) { seed(q, outside, wall, 0, y, mw); seed(q, outside, wall, mw - 1, y, mw); }
        while (!q.isEmpty()) {
            int i = q.poll();
            int x = i % mw, y = i / mw;
            if (x > 0)      seed(q, outside, wall, x - 1, y, mw);
            if (x < mw - 1) seed(q, outside, wall, x + 1, y, mw);
            if (y > 0)      seed(q, outside, wall, x, y - 1, mw);
            if (y < mh - 1) seed(q, outside, wall, x, y + 1, mw);
        }

        // 벽을 ±WALL_THICK 칸 굵게 그렸으므로 그대로 두면 안쪽이 벽선보다 바깥으로 그만큼 넓다.
        // 바깥에 닿은 벽 칸을 WALL_THICK 번 벗겨내 경계를 실제 벽선에 맞춘다 (틈 막기 효과는 유지).
        for (int pass = 0; pass < WALL_THICK; pass++) {
            boolean[] peel = new boolean[mw * mh];
            for (int y = 0; y < mh; y++) {
                for (int x = 0; x < mw; x++) {
                    int i = y * mw + x;
                    if (!wall[i] || outside[i]) continue;
                    if ((x > 0 && outside[i - 1]) || (x < mw - 1 && outside[i + 1])
                            || (y > 0 && outside[i - mw]) || (y < mh - 1 && outside[i + mw])) {
                        peel[i] = true;
                    }
                }
            }
            for (int i = 0; i < peel.length; i++) if (peel[i]) outside[i] = true;
        }

        boolean[] inside = new boolean[mw * mh];
        int insideCells = 0;
        for (int i = 0; i < inside.length; i++) {
            inside[i] = !outside[i];
            if (inside[i]) insideCells++;
        }

        double bboxCells = Math.max(1.0, ((maxX - minX) / CELL_PX) * ((maxY - minY) / CELL_PX));
        if (insideCells < MIN_INTERIOR_OF_BBOX * bboxCells) return null;  // 외벽이 열려 있음
        return new BuildingMask(inside, mw, mh);
    }

    private static void seed(ArrayDeque<Integer> q, boolean[] outside, boolean[] wall, int x, int y, int mw) {
        int i = y * mw + x;
        if (outside[i] || wall[i]) return;
        outside[i] = true;
        q.add(i);
    }

    /** 캔버스 px 좌표가 건물 안쪽(벽 포함)인지. */
    public boolean contains(int px, int py) {
        int x = px / CELL_PX, y = py / CELL_PX;
        if (x < 0 || y < 0 || x >= mw || y >= mh) return false;
        return inside[y * mw + x];
    }

    /** 바깥 픽셀을 투명하게 만든다 (ARGB 버퍼, 제자리 수정). */
    public void clip(int[] argb, int widthPx, int heightPx) {
        for (int y = 0; y < heightPx; y++) {
            int row = y * widthPx;
            for (int x = 0; x < widthPx; x++) {
                if (!contains(x, y)) argb[row + x] = 0;
            }
        }
    }
}
