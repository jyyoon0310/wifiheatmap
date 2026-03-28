package app.engine;

import app.model.Band;
import app.model.Wall;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * DPM (Dominant Path Model) 전파 경로 격자.
 *
 * 논문 근거:
 *  - Wölfle G. et al. (2005) "Dominant Path Prediction Model for Indoor Scenarios",
 *    German Microwave Conference (GeMIC), Ulm, 2005.
 *  - Plets D. et al. (2012) "Coverage prediction and optimization algorithms for indoor
 *    environments", EURASIP J. Wireless Comm. Netw., 2012:123.
 *    DOI: 10.1186/1687-1499-2012-123
 *
 * ─── 경로 손실 수식 ───────────────────────────────────────────────────────────
 *
 *   PL [dB] = FSPL(l, f) + Σⱼ tⱼ + Σᵢ f(φᵢ) − Ω
 *
 *   FSPL(l, f) = 20·log₁₀(4π · l · f / c)
 *     l : 지배 경로(Dominant Path)의 총 길이 [m]  ← Euclidean 직선이 아님
 *     f : 반송 주파수 [Hz]
 *     c : 광속 299 792 458 m/s
 *   tⱼ : j번째 벽 통과 손실 [dB]  (재질·밴드별, WallMaterial 정의값 사용)
 *   f(φᵢ) = INTERACTION_A × φᵢ [°]: i번째 방향 전환 손실 (Plets 2012 선형 모델)
 *     φᵢ : 전환 각도 [°],  A = 0.0556 dB/° (드라이월 지배 건물 보정값)
 *     ≈ 5 dB / 90°
 *   Ω : 도파로 이득 [dB] — 복도·통로에서 신호 집중 이득
 *     Ω = corridorScore × 10 × DELTA_N × log₁₀(l / l_prev)
 *     corridorScore ∈ [0,1]: 복도 폭 기반 점수 (1 = 완벽한 복도)
 *     DELTA_N = 0.6: 자유공간(n=2.0) 대비 복도 경로 손실 지수 감소량
 *     수식이 log₁₀ 기반이므로 FSPL 항과 합산해도 항상 양수 → Dijkstra 단조성 보장
 *
 * ─── 경로 선택 비용 (Dijkstra 우선순위) ──────────────────────────────────────
 *   selCost = FSPL(l) + Σtⱼ + LI·Σf(φᵢ) − LW·Ω
 *   LI = 1.5: 방향 전환을 물리적 손실보다 더 강하게 회피
 *   LW = 2.0: 복도 경로를 물리적 이득보다 더 강하게 선호
 *   (Altair WinProp ProMan "Determination of Dominant Paths" 기본값)
 *
 *   출력은 LI·LW 무관한 물리 손실 PL 사용.
 *
 * ─── 알고리즘 ──────────────────────────────────────────────────────────────
 *   1. 도면을 GRID_CELL_M(0.30 m) 격자로 분할
 *      → 표준 문폭 0.9 m 탐지에 충분 (Nyquist: ≤ 0.45 m 필요)
 *   2. 16-방향 연결 격자 그래프 (직선4 + 대각4 + 나이트무브8, 22.5° 간격)
 *   3. Dijkstra 상태: (gx, gy, dirIn) — 방향 전환 손실 계산을 위해 입력 방향 포함
 *      dirIn ∈ {0..15, 16=소스}: N_DIRS = 17
 *   4. 엣지 비용 = FSPL 증분 + 벽 손실 + LI·전환 손실 − LW·도파로 이득
 *   5. 각 픽셀 → 최근접 격자 셀, 9개 방향 상태 중 물리 PL 최솟값 반환
 */
public final class DpmPathGrid {

    /** 격자 셀 크기 (m). 문 탐지용 ≤ 0.45 m; 0.30 m 사용. */
    public static final double GRID_CELL_M = 0.30;

    /** 도달 불가 셀의 플래그값 */
    private static final double UNREACHABLE = Double.MAX_VALUE / 2.0;

    /** 광속 (m/s) */
    private static final double C_MPS = 299_792_458.0;

    /** 소스 최소 거리 (m): log(0) 방지 */
    private static final double MIN_SRC_DIST_M = 0.10;

    // ── 방향 전환 손실 (Plets 2012, EURASIP JWCN 2012:123) ──────────────────
    /** Dijkstra 상태의 "입력 방향 없음" 마커 (소스 셀) */
    private static final int    DIR_SOURCE    = 16;
    /** 상태 방향 수 (0-15: 16방향, 16: 소스) */
    private static final int    N_DIRS        = 17;
    /** 방향 전환 손실 계수 [dB/°] — 드라이월 지배 건물 보정값 (Plets 2012) */
    private static final double INTERACTION_A = 0.0556;
    /** 손실을 적용할 최소 전환 각도 [°] — 격자 노이즈 제거 */
    private static final double MIN_BEND_DEG  = 10.0;

    // ── 도파로 효과 (WinProp ProMan DPM 근사) ───────────────────────────────
    /** 복도 판정 최대 폭 [m] — 양쪽 벽까지 거리 합 기준 */
    private static final double CORRIDOR_MAX_W_M   = 4.0;
    /**
     * 완벽한 복도(W→0)에서의 경로 손실 지수 감소량.
     * 자유공간 n=2.0, 실내 복도 최소 n=1.4 → DELTA_N = 0.6
     * 1.5 m 복도 10 m: 이득 ≈ 4 dB (측정값 3–7 dB)
     */
    private static final double DELTA_N_CORRIDOR   = 0.6;
    /** 수직 방향 탐색 최대 스텝 수 (CORRIDOR_MAX_W_M / GRID_CELL_M ≈ 14) */
    private static final int    MAX_CORRIDOR_STEPS = 14;

    // ── WinProp 경로 선택 가중치 (Altair "Determination of Dominant Paths") ─
    /** 인터랙션 손실 가중치 (경로 선택 시 구부러짐 회피 강화) */
    private static final double LI = 1.5;
    /** 도파로 이득 가중치 (경로 선택 시 복도 경로 선호 강화) */
    private static final double LW = 2.0;

    // ── 우회 경로 패널티 ──────────────────────────────────────────────────
    /**
     * 허용 최대 우회율: path_len / direct_dist.
     * 실내 복도·방 우회(≤ 2.0)는 허용, 건물 외부 우회(> 2.0)는 패널티.
     *
     * 배경: 외벽이 미도시되면 Dijkstra가 건물 외부를 경유하는 경로를
     * 선택할 수 있음 — 콘크리트 2개(28 dB) vs 외부 우회(FSPL+10 dB).
     * 우회율이 MAX_DETOUR_RATIO를 초과할 때 선택 비용에 패널티를 추가해
     * 실내 직선 경로를 우선시한다.
     */
    private static final double MAX_DETOUR_RATIO     = 2.0;
    /**
     * 우회 패널티 [dB / unit excess ratio].
     * 우회율 3.0 → 초과 1.0 → 20 dB 패널티.
     * 콘크리트 2개(28 dB)와 비교 시 외부 경로가 항상 더 비싸도록 보장.
     */
    private static final double DETOUR_PENALTY_DB    = 20.0;

    // 16-방향: 직선 4 + 대각선 4 + 나이트 무브 8 (22.5° 간격)
    private static final double S2 = Math.sqrt(2.0);
    private static final double S5 = Math.sqrt(5.0);
    private static final int[]    DDX      = {1,-1, 0, 0, 1, 1,-1,-1,  2, 1,-1,-2,-2,-1, 1, 2};
    private static final int[]    DDY      = {0, 0, 1,-1, 1,-1, 1,-1,  1, 2, 2, 1,-1,-2,-2,-1};
    private static final double[] EDGE_LEN = {1, 1, 1, 1, S2, S2, S2, S2, S5, S5, S5, S5, S5, S5, S5, S5};

    /** 각 방향의 실제 각도 [°] — bendDeg() 계산용 */
    private static final double[] DIR_ANGLE = new double[16];
    static {
        for (int d = 0; d < 16; d++) {
            DIR_ANGLE[d] = Math.toDegrees(Math.atan2(DDY[d], DDX[d]));
            if (DIR_ANGLE[d] < 0) DIR_ANGLE[d] += 360.0;
        }
    }

    // ── 격자 파라미터 ──────────────────────────────────────────────────────
    private final int    gw;          // 격자 너비 (셀 수)
    private final int    gh;          // 격자 높이 (셀 수)
    private final double cellPx;      // 셀 크기 (픽셀)  = GRID_CELL_M / scaleMPerPx
    private final double scaleMPerPx; // m/px 스케일
    private final List<Wall> walls;
    private final Band   band;

    // ── 도파로 사전계산 (생성자에서 1회) ──────────────────────────────────
    /**
     * omegaPrecomp[dir * gw * gh + gy * gw + gx], dir ∈ [0,15]
     * 값: 복도 점수 ∈ [0, 1].  1 = 완벽한 복도(W→0), 0 = 복도 없음
     * dir 0-7: 직접 계산 (수직 레이), dir 8-15: 인접 기본 방향 평균
     */
    private final double[] omegaPrecomp;

    // ── 소스 셀 위치 (우회율 계산용) ──────────────────────────────────────
    private int srcCellX, srcCellY;

    // ── Dijkstra 결과 (idx = dir * gw * gh + gy * gw + gx) ────────────────
    private double[] bestSelCost;    // 경로 선택 비용 (LI·LW 가중, 우선순위 큐 기준)
    private double[] bestCost;       // 물리 경로 손실 PL [dB] (출력값)
    private double[] bestLenM;       // 누적 경로 길이 [m]
    private double[] bestWallDb;     // 누적 벽 통과 손실 [dB]
    private double[] bestInteractDb; // 누적 방향 전환 손실 [dB]
    private double[] bestWaveDb;     // 누적 도파로 이득 [dB]

    // ── 생성자 ────────────────────────────────────────────────────────────

    /**
     * @param canvasWidthPx  캔버스 너비 (px)
     * @param canvasHeightPx 캔버스 높이 (px)
     * @param scaleMPerPx    스케일 (m/px)
     * @param walls          벽 목록 (픽셀 좌표)
     * @param band           사용 밴드 (null → 2.4 GHz 취급)
     */
    public DpmPathGrid(int canvasWidthPx, int canvasHeightPx,
                       double scaleMPerPx, List<Wall> walls, Band band) {
        this.scaleMPerPx = scaleMPerPx;
        this.walls = walls;
        this.band  = band;
        this.cellPx = Math.max(1.0, GRID_CELL_M / scaleMPerPx);
        this.gw     = (int) Math.ceil(canvasWidthPx  / cellPx) + 1;
        this.gh     = (int) Math.ceil(canvasHeightPx / cellPx) + 1;

        // 도파로 복도 점수 사전계산 (벽 목록 기반, 1회)
        this.omegaPrecomp = new double[16 * gw * gh];
        precomputeWaveguiding();

        int n = N_DIRS * gw * gh;
        this.bestSelCost    = new double[n];
        this.bestCost       = new double[n];
        this.bestLenM       = new double[n];
        this.bestWallDb     = new double[n];
        this.bestInteractDb = new double[n];
        this.bestWaveDb     = new double[n];
    }

    // ── 공개 API ──────────────────────────────────────────────────────────

    /**
     * Dijkstra 실행: AP 위치에서 모든 격자 셀까지의 최소 경로 손실 계산.
     *
     * @param sourceXPx AP 위치 X [픽셀]
     * @param sourceYPx AP 위치 Y [픽셀]
     * @param freqGhz   반송 주파수 [GHz]
     */
    public void runDijkstra(double sourceXPx, double sourceYPx, double freqGhz) {
        int n = N_DIRS * gw * gh;
        Arrays.fill(bestSelCost,    UNREACHABLE);
        Arrays.fill(bestCost,       UNREACHABLE);
        Arrays.fill(bestLenM,       0.0);
        Arrays.fill(bestWallDb,     0.0);
        Arrays.fill(bestInteractDb, 0.0);
        Arrays.fill(bestWaveDb,     0.0);

        int sx = clampX((int)(sourceXPx / cellPx));
        int sy = clampY((int)(sourceYPx / cellPx));
        srcCellX = sx;
        srcCellY = sy;
        int srcState = si(sx, sy, DIR_SOURCE);

        double srcPl = fspl(MIN_SRC_DIST_M, freqGhz);
        bestSelCost[srcState] = srcPl;
        bestCost[srcState]    = srcPl;
        bestLenM[srcState]    = MIN_SRC_DIST_M;

        // PriorityQueue: [selectionCost, stateId]
        PriorityQueue<double[]> pq = new PriorityQueue<>(
                Comparator.comparingDouble(e -> e[0]));
        pq.offer(new double[]{srcPl, srcState});

        while (!pq.isEmpty()) {
            double[] top     = pq.poll();
            double   selCost = top[0];
            int      state   = (int) top[1];

            // stale entry 제거
            if (selCost > bestSelCost[state] + 1e-9) continue;

            int    dirIn   = state / (gw * gh);
            int    cellIdx = state % (gw * gh);
            int    gx      = cellIdx % gw;
            int    gy      = cellIdx / gw;
            double cx      = (gx + 0.5) * cellPx;
            double cy      = (gy + 0.5) * cellPx;

            for (int dirOut = 0; dirOut < 16; dirOut++) {
                int nx = gx + DDX[dirOut];
                int ny = gy + DDY[dirOut];
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;

                double ncx = (nx + 0.5) * cellPx;
                double ncy = (ny + 0.5) * cellPx;

                // ① 벽 통과 손실
                double edgeWallDb = wallLossAlongEdge(cx, cy, ncx, ncy);
                double newWallDb  = bestWallDb[state] + edgeWallDb;

                // ② 경로 길이 누적
                double edgeLenM = EDGE_LEN[dirOut] * cellPx * scaleMPerPx;
                double newLenM  = bestLenM[state] + edgeLenM;

                // ③ 방향 전환 손실 (Plets 2012): L_B = A × φ [°]
                double bend         = bendDeg(dirIn, dirOut);
                double edgeIntDb    = (bend >= MIN_BEND_DEG) ? (INTERACTION_A * bend) : 0.0;
                double newInteractDb = bestInteractDb[state] + edgeIntDb;

                // ④ 도파로 이득: log-scale로 누적 (Dijkstra 단조성 보장)
                //    edgeWaveDb = score × 10 × DELTA_N × log₁₀(newLen / prevLen)
                double prevLen    = Math.max(MIN_SRC_DIST_M, bestLenM[state]);
                double cScore     = omegaPrecomp[dirOut * gw * gh + ny * gw + nx];
                double edgeWaveDb = cScore * 10.0 * DELTA_N_CORRIDOR
                        * Math.log10(newLenM / prevLen);
                double newWaveDb  = bestWaveDb[state] + edgeWaveDb;

                // ⑤ 우회율 패널티 — 건물 외부 우회 경로 억제 (선택 비용에만 적용)
                //    직선 거리 대비 경로 길이가 MAX_DETOUR_RATIO 초과 시 패널티 추가.
                //    물리 PL에는 적용 안 함 (경로 선택 bias만 조정).
                double directDistM = Math.hypot((nx - srcCellX) * cellPx,
                                                (ny - srcCellY) * cellPx) * scaleMPerPx;
                double detourRatio = newLenM / Math.max(MIN_SRC_DIST_M, directDistM);
                double detourPenalty = Math.max(0.0,
                        (detourRatio - MAX_DETOUR_RATIO) * DETOUR_PENALTY_DB);

                // ⑥ 물리 경로 손실 (출력) & 선택 비용 (LI·LW 가중, 경로 탐색용)
                double newPhysPl  = fspl(newLenM, freqGhz)
                        + newWallDb + newInteractDb - newWaveDb;
                double newSelCost = fspl(newLenM, freqGhz)
                        + newWallDb + LI * newInteractDb - LW * newWaveDb
                        + detourPenalty;

                int nextState = si(nx, ny, dirOut);
                if (newSelCost < bestSelCost[nextState]) {
                    bestSelCost[nextState]    = newSelCost;
                    bestCost[nextState]       = newPhysPl;
                    bestLenM[nextState]       = newLenM;
                    bestWallDb[nextState]     = newWallDb;
                    bestInteractDb[nextState] = newInteractDb;
                    bestWaveDb[nextState]     = newWaveDb;
                    pq.offer(new double[]{newSelCost, nextState});
                }
            }
        }
    }

    /**
     * 픽셀 (px, py) 에서의 총 경로 손실 [dB].
     * 4개 이웃 격자 셀에 쌍선형 보간(bilinear interpolation)을 적용하여
     * 8방향 Dijkstra의 팔각형 등고선 아티팩트(별 모양)를 제거한다.
     * 도달 불가 지점은 200 dB 반환.
     */
    public double getPathLossDb(double px, double py) {
        double gxd = px / cellPx;
        double gyd = py / cellPx;

        int gx0 = clampX((int) Math.floor(gxd));
        int gy0 = clampY((int) Math.floor(gyd));
        int gx1 = clampX(gx0 + 1);
        int gy1 = clampY(gy0 + 1);

        double tx = gxd - Math.floor(gxd); // 0.0 ~ 1.0
        double ty = gyd - Math.floor(gyd);

        double pl00 = cellMinCost(gx0, gy0);
        double pl10 = cellMinCost(gx1, gy0);
        double pl01 = cellMinCost(gx0, gy1);
        double pl11 = cellMinCost(gx1, gy1);

        // 도달 불가 셀은 보간에서 제외 (가중치 0)
        double thresh = 1e8;
        double w00 = (pl00 < thresh) ? 1.0 : 0.0;
        double w10 = (pl10 < thresh) ? 1.0 : 0.0;
        double w01 = (pl01 < thresh) ? 1.0 : 0.0;
        double w11 = (pl11 < thresh) ? 1.0 : 0.0;

        double wSum = (1-tx)*(1-ty)*w00 + tx*(1-ty)*w10
                    + (1-tx)*ty*w01     + tx*ty*w11;
        if (wSum < 1e-9) return 200.0; // 4개 이웃 모두 도달 불가

        double vSum = (1-tx)*(1-ty)*w00*pl00 + tx*(1-ty)*w10*pl10
                    + (1-tx)*ty*w01*pl01     + tx*ty*w11*pl11;
        return vSum / wSum;
    }

    /** 셀 (gx, gy) 에서 모든 방향 상태 중 최소 물리 경로 손실 반환 */
    private double cellMinCost(int gx, int gy) {
        double best = UNREACHABLE;
        for (int d = 0; d < N_DIRS; d++) {
            double v = bestCost[si(gx, gy, d)];
            if (v < best) best = v;
        }
        return best;
    }

    // ── 도파로 사전계산 ────────────────────────────────────────────────────

    /**
     * 각 (셀, 방향) 쌍에 대한 복도 점수 ∈ [0, 1] 사전 계산.
     *
     * 이동 방향에 수직인 양쪽 방향으로 레이를 쏘아 벽까지 거리 dL, dR을 측정.
     * 양쪽 합 W = dL + dR < CORRIDOR_MAX_W_M 이면 복도로 판정.
     *
     *   score = max(0, 1 − W / CORRIDOR_MAX_W_M)
     *
     * 수직 방향 정의: DDX/DDY 기준 90° 반시계(+2) / 시계(+6) 방향.
     */
    private void precomputeWaveguiding() {
        // 기존 8방향 (d=0..7): 수직 레이로 복도 점수 직접 계산
        for (int d = 0; d < 8; d++) {
            int perpL = (d + 2) % 8; // 90° 왼쪽 수직
            int perpR = (d + 6) % 8; // 90° 오른쪽 수직
            for (int gy = 0; gy < gh; gy++) {
                for (int gx = 0; gx < gw; gx++) {
                    double dL = rayDistToWallM(gx, gy, perpL);
                    double dR = rayDistToWallM(gx, gy, perpR);
                    double W  = dL + dR;
                    double score = (dL > 0.01 && dR > 0.01 && W < CORRIDOR_MAX_W_M)
                            ? Math.max(0.0, 1.0 - W / CORRIDOR_MAX_W_M)
                            : 0.0;
                    omegaPrecomp[d * gw * gh + gy * gw + gx] = score;
                }
            }
        }
        // 나이트 무브 (d=8..15): 인접 기본 방향 2개의 평균
        // 각도 순서: 0°(0), 26.6°(8), 45°(4), 63.4°(9), 90°(2), 116.6°(10), ...
        int[][] bracket = {{0,4},{4,2},{6,2},{1,6},{1,7},{7,3},{5,3},{0,5}};
        int gwgh = gw * gh;
        for (int k = 0; k < 8; k++) {
            int dA = bracket[k][0], dB = bracket[k][1];
            int d = k + 8;
            int baseA = dA * gwgh, baseB = dB * gwgh, baseD = d * gwgh;
            for (int i = 0; i < gwgh; i++) {
                omegaPrecomp[baseD + i] = 0.5 * (omegaPrecomp[baseA + i] + omegaPrecomp[baseB + i]);
            }
        }
    }

    /**
     * 격자 셀 (gx, gy)에서 방향 dir로 가장 가까운 벽까지의 거리 [m].
     * 최대 MAX_CORRIDOR_STEPS 스텝까지 탐색. 경계에 도달하면 경계까지의 거리 반환.
     */
    private double rayDistToWallM(int gx, int gy, int dir) {
        double cx = (gx + 0.5) * cellPx;
        double cy = (gy + 0.5) * cellPx;
        for (int s = 1; s <= MAX_CORRIDOR_STEPS; s++) {
            int nx = gx + DDX[dir] * s;
            int ny = gy + DDY[dir] * s;
            if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) {
                return (s - 0.5) * EDGE_LEN[dir] * cellPx * scaleMPerPx;
            }
            double ncx = (nx + 0.5) * cellPx;
            double ncy = (ny + 0.5) * cellPx;
            if (wallLossAlongEdge(cx, cy, ncx, ncy) > 0) {
                return (s - 0.5) * EDGE_LEN[dir] * cellPx * scaleMPerPx;
            }
            cx = ncx;
            cy = ncy;
        }
        return MAX_CORRIDOR_STEPS * EDGE_LEN[dir] * cellPx * scaleMPerPx;
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────

    /** 상태 인덱스: (gx, gy, dir) → 1D 배열 인덱스. dir ∈ [0, N_DIRS) */
    private int si(int gx, int gy, int dir) {
        return dir * gw * gh + gy * gw + gx;
    }

    /**
     * 두 방향 간 전환 각도 [°].
     * 16방향 (22.5° 간격): atan2 기반 실각도 차이.
     */
    private static double bendDeg(int dirIn, int dirOut) {
        if (dirIn == DIR_SOURCE) return 0.0;
        double diff = Math.abs(DIR_ANGLE[dirOut] - DIR_ANGLE[dirIn]);
        if (diff > 180.0) diff = 360.0 - diff;
        return diff;
    }

    /**
     * Free-Space Path Loss.
     *   FSPL [dB] = 20·log₁₀(4π · l [m] · f [Hz] / c)
     *             = 20·log₁₀(l) + 20·log₁₀(f_GHz) + 32.45  (f in GHz 근사)
     */
    private static double fspl(double lengthM, double freqGhz) {
        double l = Math.max(0.01, lengthM);
        return 20.0 * Math.log10(4.0 * Math.PI * l * freqGhz * 1.0e9 / C_MPS);
    }

    /**
     * 격자 엣지 (ax,ay)→(bx,by) [픽셀 좌표] 를 교차하는 벽들의 감쇠 합산.
     * 벽 좌표도 픽셀 기준이므로 좌표 변환 불필요.
     */
    private double wallLossAlongEdge(double ax, double ay, double bx, double by) {
        double loss = 0.0;
        for (Wall w : walls) {
            if (w == null) continue;
            if (segmentsIntersectStrict(ax, ay, bx, by, w.x1, w.y1, w.x2, w.y2)) {
                loss += w.attenuationDb(band);
            }
        }
        return loss;
    }

    /**
     * 진정한 선분 교차 판정 (끝점 접촉 제외).
     * 격자 엣지가 벽 끝점(문 모서리)에 접촉해도 통과로 처리하여
     * 문 옆 경로를 막지 않음.
     */
    private static boolean segmentsIntersectStrict(
            double ax, double ay, double bx, double by,
            double cx, double cy, double dx, double dy) {
        final double EPS = 1e-9;
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);
        boolean ab_straddles_cd = (d1 >  EPS && d2 < -EPS) || (d1 < -EPS && d2 >  EPS);
        boolean cd_straddles_ab = (d3 >  EPS && d4 < -EPS) || (d3 < -EPS && d4 >  EPS);
        return ab_straddles_cd && cd_straddles_ab;
    }

    /** 삼각형 부호 있는 넓이 (방향성 cross product): (a→b) × (a→p) */
    private static double cross(double ox, double oy,
                                double ax, double ay,
                                double bx, double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    private int clampX(int v) { return Math.max(0, Math.min(gw - 1, v)); }
    private int clampY(int v) { return Math.max(0, Math.min(gh - 1, v)); }
}
