package app.engine;

import javafx.geometry.Point2D;

import java.util.Arrays;
import java.util.List;

/**
 * 2D FDTD 전력장 → 3D 절대 경로 손실 [dB] 변환 (AP 추천 정밀 검증용).
 *
 * <p>FDTD 시뮬레이터의 전력은 소스 세기에 따른 임의 단위이고, 2D(TEz) 파동은 원통형으로 퍼져
 * 1/r 로 감쇠한다(실제 3D 는 1/r²). 그래서 예전처럼 "공유기 바로 옆 전력 = 0 dB" 로 두고 EIRP 를
 * 더하면 기준 거리까지의 손실이 빠지고 거리 감쇠도 절반만 반영돼, 절대 신호 세기가 크게 후했다.</p>
 *
 * <p>여기서는 자유공간 몫은 해석식(3D FSPL)으로, 벽·회절·반사로 생기는 추가 손실은 FDTD 로 구한다.</p>
 * <pre>
 *   PL(x) = FSPL(r, f) + 추가손실(x)
 *   추가손실(x) = 10·log10(P_ref / P(x)) − 10·log10(r / r_ref)
 * </pre>
 * <ul>
 *   <li>P_ref : 공유기 둘레 r_ref(1 m) 원 위 전력의 중앙값 — 그 거리까지는 자유공간으로 본다</li>
 *   <li>P(x)  : x 둘레 반 파장 이내 격자 전력의 평균 (한 칸 값은 간섭 무늬로 수 dB 흔들림)</li>
 *   <li>10·log10(r/r_ref) : 2D 원통파가 자유공간에서 이미 겪는 감쇠 — 이걸 빼야 "추가" 손실만 남는다</li>
 *   <li>r : 가장 가까운 공유기까지 직선 거리. 여러 공유기를 한 번에 시뮬레이션하므로 가장 가까운
 *       공유기를 지배 송신원으로 본다 (근사)</li>
 * </ul>
 * <p>r ≤ r_ref 인 지점은 자유공간 손실만 쓴다.</p>
 */
public final class FdtdPathLoss {

    /** 기준 원 반지름 [m]. 근접장을 벗어나되(2.4GHz 에서 약 8λ) 벽에 닿기 전인 거리. */
    static final double REF_RADIUS_M = 1.0;
    private static final int REF_SAMPLES = 72;
    private static final double C_MPS = 299_792_458.0;

    private final FdtdWaveSimulator sim;
    private final List<Point2D> apsPx;
    private final double scaleMPerPx;
    private final double freqGhz;
    private final int avgRadiusCells;
    private final double[] refPower;

    /**
     * @param apsPx        시뮬레이션에 넣은 공유기 위치 [평면도 px]
     * @param scaleMPerPx  평면도 축척 [m/px]
     * @param freqGhz      판정할 주파수 [GHz] — 자유공간 손실에 쓴다
     */
    public FdtdPathLoss(FdtdWaveSimulator sim, List<Point2D> apsPx, double scaleMPerPx, double freqGhz) {
        this.sim = sim;
        this.apsPx = List.copyOf(apsPx);
        this.scaleMPerPx = scaleMPerPx;
        this.freqGhz = freqGhz;
        double halfLambdaM = C_MPS / (sim.refFreqGhz() * 1.0e9) / 2.0;
        this.avgRadiusCells = Math.max(1, (int) Math.round(halfLambdaM / sim.dxMeters()));
        this.refPower = new double[this.apsPx.size()];
        double refRadiusPx = REF_RADIUS_M / scaleMPerPx;
        for (int i = 0; i < this.apsPx.size(); i++) {
            Point2D ap = this.apsPx.get(i);
            double[] ring = new double[REF_SAMPLES];
            for (int k = 0; k < REF_SAMPLES; k++) {
                double a = 2.0 * Math.PI * k / REF_SAMPLES;
                ring[k] = localMeanPower(ap.getX() + refRadiusPx * Math.cos(a),
                                         ap.getY() + refRadiusPx * Math.sin(a));
            }
            Arrays.sort(ring);
            refPower[i] = ring[REF_SAMPLES / 2];
        }
    }

    /** 평면도 px 위치의 경로 손실 [dB]. 공유기가 없거나 전력이 없으면 200 dB. */
    public double pathLossDb(double px, double py) {
        int nearest = -1;
        double bestD2 = Double.MAX_VALUE;
        for (int i = 0; i < apsPx.size(); i++) {
            double dx = apsPx.get(i).getX() - px, dy = apsPx.get(i).getY() - py;
            double d2 = dx * dx + dy * dy;
            if (d2 < bestD2) { bestD2 = d2; nearest = i; }
        }
        if (nearest < 0) return 200.0;

        double rM = Math.sqrt(bestD2) * scaleMPerPx;
        if (rM <= REF_RADIUS_M) return DpmPathGrid.fspl(Math.max(0.1, rM), freqGhz);

        double p = localMeanPower(px, py);
        double pRef = refPower[nearest];
        if (!(p > 0.0) || !(pRef > 0.0)) return 200.0;

        double excessDb = 10.0 * Math.log10(pRef / p) - 10.0 * Math.log10(rM / REF_RADIUS_M);
        return DpmPathGrid.fspl(rM, freqGhz) + excessDb;
    }

    /** px 위치 둘레 반 파장 이내 격자 전력의 평균. */
    private double localMeanPower(double px, double py) {
        int cx = sim.toGridX(px), cy = sim.toGridY(py);
        int r = avgRadiusCells, r2 = r * r;
        double sum = 0.0;
        int n = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                if (dx * dx + dy * dy > r2) continue;
                sum += sim.getPowerAt(cx + dx, cy + dy);
                n++;
            }
        }
        return (n == 0) ? 0.0 : sum / n;
    }
}
