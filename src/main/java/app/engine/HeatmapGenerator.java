package app.engine;

import app.model.AP;
import app.model.Band;
import app.model.RadioConfig;
import app.model.Wall;
import app.model.WifiEnvironment;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * DPM(지배경로 모델) 신호 지도 생성기.
 *
 * <p>공유기·밴드마다 {@link DpmPathGrid} 로 경로 손실 지도를 한 번 계산한 뒤, 픽셀마다
 * 가장 센 신호를 색으로 칠한다. 백그라운드 스레드에서 돌도록 JavaFX 이미지가 아닌
 * {@code int[]} ARGB 버퍼를 돌려준다 — 이미지 변환·흐림 처리는 호출자가 FX 스레드에서 한다.</p>
 *
 * <p>(예전 Legacy 모델의 히트맵 경로는 화면에서 쓰지 않아 지웠다. Legacy 계산 자체는
 * {@link WifiEnvironment#sampleRssiAt} 에 비교 기준선으로 남아 있다.)</p>
 */
public class HeatmapGenerator {

    /** 백그라운드 스레드에서 계산한 결과. */
    public static final class HeatmapResult {
        public final int[] argbPixels;
        public final int width;
        public final int height;
        public final int smoothRadiusPx;
        /** 공유기별·밴드별 경로 손실 지도 — 마우스 위치 신호값을 같은 모델로 읽는 데 쓴다. */
        public final java.util.Map<AP, java.util.Map<Band, DpmPathGrid>> dpmGrids;
        /** 건물 안쪽 마스크 (없으면 null) — 흐림 처리 뒤 경계를 벽선에 맞춰 다시 자르는 데 쓴다. */
        public final BuildingMask buildingMask;
        /** DPM 탐색 통계 (고급 모드 표시용). */
        public final long dpmExpandedNodes;
        public final long dpmPrunedNodes;
        public final long dpmElapsedMs;

        public HeatmapResult(int[] argbPixels, int width, int height, int smoothRadiusPx,
                             java.util.Map<AP, java.util.Map<Band, DpmPathGrid>> dpmGrids,
                             long dpmExpandedNodes, long dpmPrunedNodes, long dpmElapsedMs) {
            this(argbPixels, width, height, smoothRadiusPx, dpmGrids,
                    dpmExpandedNodes, dpmPrunedNodes, dpmElapsedMs, null);
        }

        public HeatmapResult(int[] argbPixels, int width, int height, int smoothRadiusPx,
                             java.util.Map<AP, java.util.Map<Band, DpmPathGrid>> dpmGrids,
                             long dpmExpandedNodes, long dpmPrunedNodes, long dpmElapsedMs,
                             BuildingMask buildingMask) {
            this.argbPixels = argbPixels;
            this.width = width;
            this.height = height;
            this.smoothRadiusPx = smoothRadiusPx;
            this.dpmGrids = dpmGrids;
            this.dpmExpandedNodes = dpmExpandedNodes;
            this.dpmPrunedNodes   = dpmPrunedNodes;
            this.dpmElapsedMs     = dpmElapsedMs;
            this.buildingMask = buildingMask;
        }
    }

    private final WifiEnvironment env;
    /** 표시할 밴드. null 이면 모든 밴드 중 가장 센 신호. */
    private final Band band;

    /**
     * @param band 표시할 밴드 — null 이면 모든 밴드 중 가장 센 신호
     */
    public HeatmapGenerator(WifiEnvironment env, Band band) {
        this.env = env;
        this.band = band;
    }

    private boolean showsBand(Band b) {
        return band == null || band == b;
    }

    /**
     * 신호 지도를 만든다.
     *
     * <p>공유기·밴드마다 DpmPathGrid.runDijkstra() 로 지배 경로 손실을 미리 계산한 뒤
     * 픽셀별로 조회한다. gridStepPx 칸마다 3×3 표본의 평균(mW)으로 색을 정한다.</p>
     */
    public HeatmapResult generate(int width, int height, int gridStepPx, int smoothRadiusPx,
                                  Consumer<Double> progressCallback) {
        List<AP> aps = new ArrayList<>(env.getAps());
        List<Wall> walls = new ArrayList<>(env.getWalls());
        double scaleMPerPx = env.getScaleMPerPx();

        List<AP> enabled = new ArrayList<>();
        for (AP ap : aps) {
            if (ap != null && ap.enabled) enabled.add(ap);
        }

        int[] argb = new int[width * height]; // 투명(0) 초기값

        if (enabled.isEmpty() || !Double.isFinite(scaleMPerPx) || scaleMPerPx <= 0) {
            if (progressCallback != null) progressCallback.accept(1.0);
            return new HeatmapResult(argb, width, height, smoothRadiusPx, null, 0, 0, 0);
        }

        // ── AP별·밴드별 Dijkstra 사전 계산 (진행률 0→35%) ──────────────
        //   bandFilter가 단일 밴드면 그 밴드만 계산해서 속도 향상.
        java.util.Map<AP, java.util.Map<Band, DpmPathGrid>> grids = new java.util.IdentityHashMap<>();
        int totalAps = enabled.size();
        int doneAps = 0;
        long totalExpanded = 0;
        long totalPruned   = 0;
        long totalElapsedMs = 0;
        for (AP ap : enabled) {
            java.util.Map<Band, DpmPathGrid> bandGrids = new java.util.EnumMap<>(Band.class);
            for (Band b : Band.values()) {
                if (!showsBand(b)) continue;
                RadioConfig rc = ap.radios.get(b);
                if (rc == null || !rc.enabled) continue;
                DpmPathGrid grid = new DpmPathGrid(width, height, scaleMPerPx, walls, b);
                grid.runDijkstra(ap.x, ap.y, rc.centerFreqGhz());
                totalExpanded  += grid.getExpandedNodeCount();
                totalPruned    += grid.getPrunedNodeCount();
                totalElapsedMs += grid.getElapsedMs();
                bandGrids.put(b, grid);
            }
            if (!bandGrids.isEmpty()) grids.put(ap, bandGrids);
            doneAps++;
            if (progressCallback != null) progressCallback.accept(0.35 * doneAps / totalAps);
        }

        // ── 픽셀 렌더링 (진행률 35→95%) — int[] 직접 기록 ─────────────
        int sub = 3;
        int totalRows = (height + gridStepPx - 1) / gridStepPx;
        int doneRows  = 0;

        for (int yy = 0; yy < height; yy += gridStepPx) {
            for (int xx = 0; xx < width; xx += gridStepPx) {
                double mwSum  = 0.0;
                int    samples = 0;

                for (int sy = 0; sy < sub; sy++) {
                    for (int sx2 = 0; sx2 < sub; sx2++) {
                        int spx = Math.min(width  - 1, xx + (sx2 * gridStepPx + gridStepPx / 2) / sub);
                        int spy = Math.min(height - 1, yy + (sy  * gridStepPx + gridStepPx / 2) / sub);
                        double strongest = -1e9;

                        for (AP ap : enabled) {
                            java.util.Map<Band, DpmPathGrid> bandGrids = grids.get(ap);
                            if (bandGrids == null) continue;
                            double bestRssi = -1e9;
                            for (Band b : Band.values()) {
                                if (!showsBand(b)) continue;
                                RadioConfig rc = ap.radios.get(b);
                                if (rc == null || !rc.enabled) continue;
                                DpmPathGrid grid = bandGrids.get(b);
                                if (grid == null) continue;
                                double plDb = grid.getPathLossDb(spx, spy);
                                double rssi = rc.txPowerDbm + rc.antennaGain
                                        - plDb - rc.bandwidthPenaltyDb();
                                if (rssi > bestRssi) bestRssi = rssi;
                            }
                            if (bestRssi > strongest) strongest = bestRssi;
                        }

                        if (strongest > -1e9) {
                            mwSum += Math.pow(10.0, strongest / 10.0);
                            samples++;
                        }
                    }
                }

                if (samples > 0) {
                    double avgDbm = 10.0 * Math.log10(mwSum / samples);
                    // Color → int ARGB (백그라운드 스레드 안전: Color 객체만 사용)
                    Color c = WifiMath.rssiToColor(avgDbm);
                    int pixel = colorToArgb(c);

                    // gridStepPx × gridStepPx 블록 채우기 (int[] 직접 기록)
                    int yMax = Math.min(height, yy + gridStepPx);
                    int xMax = Math.min(width,  xx + gridStepPx);
                    for (int fy = yy; fy < yMax; fy++) {
                        int rowBase = fy * width;
                        for (int fx = xx; fx < xMax; fx++) {
                            argb[rowBase + fx] = pixel;
                        }
                    }
                }
            }
            doneRows++;
            if (progressCallback != null) {
                progressCallback.accept(0.35 + 0.60 * doneRows / totalRows);
            }
        }

        if (progressCallback != null) progressCallback.accept(1.0);

        // gradle daemon 이 stdout 을 삼켜도 보이도록 stderr 로 출력
        if (totalExpanded > 0) {
            System.err.printf("[DPM A*] expanded=%,d  pruned=%,d  elapsed=%,d ms%n",
                    totalExpanded, totalPruned, totalElapsedMs);
            System.err.flush();
        }

        // 건물 안쪽만 칠한다 — 바깥 여백까지 칠하면 지도가 난잡해 보이고 "어디까지가 집인지"가
        // 흐려진다. 외벽이 닫혀 있지 않아 안쪽을 판정할 수 없으면 그대로 전체를 표시한다.
        // (경계는 뒤이은 boxBlur 가 알파까지 번지게 해 부드럽게 처리된다.)
        BuildingMask building = BuildingMask.compute(walls, width, height);
        if (building != null) building.clip(argb, width, height);

        // blur는 MainController의 setOnSucceeded(FX Thread)에서 처리
        return new HeatmapResult(argb, width, height, smoothRadiusPx, grids,
                totalExpanded, totalPruned, totalElapsedMs, building);
    }

    /** Color → ARGB int 변환 */
    private static int colorToArgb(Color c) {
        int a = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getOpacity())) * 255.0);
        int r = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getRed())) * 255.0);
        int g = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getGreen())) * 255.0);
        int b = (int) Math.round(Math.max(0.0, Math.min(1.0, c.getBlue())) * 255.0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
