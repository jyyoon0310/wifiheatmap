package app.engine;

import app.model.Band;
import app.model.Wall;
import app.model.WallMaterial;
import javafx.geometry.Point2D;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/**
 * Wi-Fi 신호 계산 유틸:
 * - 경로 손실 모델 (PL = PL0 + 10 n log10(d))
 * - 직선 경로상의 벽 감쇠 합산
 * - 색상 매핑 및 간단 박스 블러
 */
public final class WifiMath {

    private WifiMath() {
        // 유틸 클래스
    }

    // ===== 기하 유틸 =====
    // 부동소수점 비교 오차 허용치
    private static final double EPS = 1e-9;
    // 벽을 매우 비스듬히 통과할 때(벽면과 거의 평행) 손실이 과도하게 커지는 것을 방지하는 하한/상한.
    // - cos(theta_n): 진행 방향과 벽 법선의 코사인(1=수직입사, 0=접선입사)
    // - Beer-Lambert 관점에서 벽 내부 경로길이 ~ 1/cos(theta_n)
    private static final double MIN_COS_THETA_N = 0.45;
    private static final double MAX_OBLIQUE_PATH_RATIO = 1.8;
    // 같은 지점에서 여러 벽이 겹쳐 카운트되는 경우(벽 분할/코너)에 대한 교차점 병합 반경
    private static final double WALL_CROSS_DEDUP_PX = 2.0;
    // AP/Rx가 벽과 거의 맞닿은 경우 교차 감쇠를 완화하는 반경/하한 가중치
    private static final double CROSS_ENDPOINT_RELAX_PX = 5.0;
    private static final double CROSS_ENDPOINT_MIN_WEIGHT = 0.45;
    // 반사점이 벽 끝점에 너무 가까우면 반사로 인정하지 않음(최소치)
    private static final double REFLECTION_ENDPOINT_MARGIN_MIN_PX = 3.0;
    // 경로 차단 판정 허용오차(px): 작을수록 엄격
    private static final double PATH_INTERSECTION_TOL_PX = 1.5;
    // AP 근접 구간에서는 단말 AGC/포화 및 안테나 패턴 요인으로 밴드별 RSSI 차이가 압축되는 경향이 있다.
    // 이를 반영하기 위한 "근접 밴드 보정" 유효 거리 구간(m).
    private static final double NEAR_BAND_COMP_START_M = 1.0;
    private static final double NEAR_BAND_COMP_END_M = 4.0; // 선형 100%→0% 구간 끝
    // 4m 이후 지수 감쇠 시상수(m): 실내 다중경로로 5GHz가 FSPL보다 덜 약해지는 효과 반영
    // 실측 기반: 아파트 스케일(5~12m)에서 5GHz는 2.4GHz 대비 실효 차이 2~3 dB 수준
    private static final double NEAR_BAND_COMP_DECAY_TAU_M = 6.0;
    // 간단 MU-MIMO/TxBF 근사 이득(LOS에서만). 밴드가 높을수록 상대 이득이 조금 더 크다고 가정.
    private static final double BF_GAIN_24_DB = 0.8;
    private static final double BF_GAIN_5_DB = 2.8;
    private static final double BF_GAIN_6_DB = 3.5;
    private static final double BF_RISE_START_M = 0.8;
    private static final double BF_RISE_TAU_M = 0.9;
    private static final double BF_FAR_DECAY_START_M = 12.0;
    private static final double BF_FAR_DECAY_TAU_M = 8.0;
    // 광속(m/s)
    private static final double C_MPS = 299_792_458.0;
    // 벽 경계 Soft Transition: 수신점이 벽 선분에 이만큼(px) 가까우면 감쇠를 부드럽게 적용
    // (교차 판정 통과 후에만 적용 → 벽 반대편 누출 없음)
    private static final double WALL_BOUNDARY_SOFT_PX = 12.0;

    /**
     * Smoothstep 보간 (t ∈ [0,1]): 경계에서 0, 경계 밖에서 1로 부드럽게 전환
     * t=0 → 0 (벽 바로 위), t=1 → 1 (충분히 멀리)
     */
    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static int orient(Point2D a, Point2D b, Point2D c) {
        // cross((b-a),(c-a))
        double v = (b.getX() - a.getX()) * (c.getY() - a.getY())
                - (b.getY() - a.getY()) * (c.getX() - a.getX());
        if (Math.abs(v) < EPS) return 0; // collinear
        return (v > 0) ? 1 : -1; // ccw : cw
    }

    private static boolean onSegment(Point2D a, Point2D b, Point2D p) {
        // p가 ab의 bounding box 안에 있고 collinear일 때
        return p.getX() <= Math.max(a.getX(), b.getX()) + EPS
                && p.getX() + EPS >= Math.min(a.getX(), b.getX())
                && p.getY() <= Math.max(a.getY(), b.getY()) + EPS
                && p.getY() + EPS >= Math.min(a.getY(), b.getY());
    }

    /**
     * 선분 ab와 cd가 교차하는지 여부(끝점 접촉/일직선 겹침 포함)
     */
    public static boolean segmentsIntersect(Point2D a, Point2D b, Point2D c, Point2D d) {
        int o1 = orient(a, b, c);
        int o2 = orient(a, b, d);
        int o3 = orient(c, d, a);
        int o4 = orient(c, d, b);

        // 일반적인 교차
        if (o1 != o2 && o3 != o4) return true;

        // 특수 케이스: collinear + onSegment
        if (o1 == 0 && onSegment(a, b, c)) return true;
        if (o2 == 0 && onSegment(a, b, d)) return true;
        if (o3 == 0 && onSegment(c, d, a)) return true;
        if (o4 == 0 && onSegment(c, d, b)) return true;

        return false;
    }

    /**
     * 두 선분 ab, cd의 교점을 구함.
     * - 교점이 단일 점으로 존재하고(평행/완전 겹침 제외)
     * - 그 점이 두 선분 내부(끝점 포함)에 있으면 Point2D 반환
     * - 아니면 null 반환
     */
    public static Point2D segmentIntersectionPoint(Point2D a, Point2D b, Point2D c, Point2D d) {
        // 선분이 교차하지 않으면 빠르게 종료
        if (!segmentsIntersect(a, b, c, d)) return null;

        // 선분을 직선으로 보고 교점 계산
        double x1 = a.getX(), y1 = a.getY();
        double x2 = b.getX(), y2 = b.getY();
        double x3 = c.getX(), y3 = c.getY();
        double x4 = d.getX(), y4 = d.getY();

        double den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(den) < EPS) {
            // 평행이거나(또는) 완전 겹침: 단일 교점 정의가 애매하므로 null
            return null;
        }

        double px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / den;
        double py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / den;
        Point2D p = new Point2D(px, py);

        // 수치오차 고려: bounding box 체크
        if (onSegment(a, b, p) && onSegment(c, d, p)) return p;
        return null;
    }

    /**
     * 무한 직선(벽의 연장선) ab에 대해 점 p를 반사시킨 점을 반환.
     */
    public static Point2D reflectPointOverLine(Point2D p, Point2D a, Point2D b) {
        // a->b 방향 벡터
        double vx = b.getX() - a.getX();
        double vy = b.getY() - a.getY();
        double len2 = vx * vx + vy * vy;
        if (len2 < EPS) return p; // degenerate

        // p를 직선에 정사영한 점 h
        double t = ((p.getX() - a.getX()) * vx + (p.getY() - a.getY()) * vy) / len2;
        double hx = a.getX() + t * vx;
        double hy = a.getY() + t * vy;

        // 반사점 p' = 2h - p
        return new Point2D(2 * hx - p.getX(), 2 * hy - p.getY());
    }

    /**
     * 선분 ab에 대한 점 p의 최근접점(정사영 후 구간 클램프)
     */
    public static Point2D closestPointOnSegment(Point2D p, Point2D a, Point2D b) {
        double vx = b.getX() - a.getX();
        double vy = b.getY() - a.getY();
        double len2 = vx * vx + vy * vy;
        if (len2 < EPS) return a;
        double t = ((p.getX() - a.getX()) * vx + (p.getY() - a.getY()) * vy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return new Point2D(a.getX() + t * vx, a.getY() + t * vy);
    }

    // ===== 벽 관련 =====

    /**
     * AP(ax,ay)와 점(bx,by)를 잇는 직선 경로 상에서
     * 교차하는 벽들의 감쇠(dB)를 모두 합산.
     */
    public static double wallLossAlong(double ax, double ay,
                                       double bx, double by,
                                       java.util.List<Wall> walls) {
        return wallLossAlong(ax, ay, bx, by, walls, null, null);
    }

    /**
     * wallLossAlong의 변형: 특정 벽(참조 동일성 기준)을 제외하고 감쇠 합산
     */
    public static double wallLossAlong(double ax, double ay,
                                       double bx, double by,
                                       java.util.List<Wall> walls,
                                       Wall ignoreWall) {
        return wallLossAlong(ax, ay, bx, by, walls, ignoreWall, null);
    }

    /**
     * Band-aware wall loss.
     * @param band null이면 2.4GHz로 취급
     */
    public static double wallLossAlong(double ax, double ay,
                                       double bx, double by,
                                       java.util.List<Wall> walls,
                                       Band band) {
        return wallLossAlong(ax, ay, bx, by, walls, null, band);
    }

    /**
     * Band-aware wall loss + 특정 벽 제외(ignoreWall).
     * @param band null이면 2.4GHz로 취급
     */
    public static double wallLossAlong(double ax, double ay,
                                       double bx, double by,
                                       java.util.List<Wall> walls,
                                       Wall ignoreWall,
                                       Band band) {
        if (walls == null || walls.isEmpty()) return 0.0;

        Point2D a = new Point2D(ax, ay);
        Point2D b = new Point2D(bx, by);
        java.util.ArrayList<Point2D> crossPoints = new java.util.ArrayList<>();
        java.util.ArrayList<Double> crossLossDb = new java.util.ArrayList<>();

        for (Wall w : walls) {
            if (w == null) continue;
            if (ignoreWall != null && w == ignoreWall) continue;
            Point2D c = new Point2D(w.x1, w.y1);
            Point2D d = new Point2D(w.x2, w.y2);
            if (!segmentsIntersect(a, b, c, d)) continue;

            // 평행/겹침 등으로 단일 교차점이 정의되지 않으면 "관통"으로 보지 않는다.
            Point2D cross = segmentIntersectionPoint(a, b, c, d);
            if (cross == null) continue;

            // 벽을 실제로 "관통"할 때만 감쇠 적용.
            // 기본 감쇠값(attenuationDb)은 수직입사 기준(normal incidence)으로 해석하고,
            // 비스듬한 입사는 Beer-Lambert의 유효 경로길이 증가(≈ 1/cos(theta_n))로 보정한다.
            double obliqueScale = wallObliqueScale(a, b, c, d);
            double endpointWeight = endpointRelaxWeight(a, b, cross);

            // 벽 경계 Soft Transition: 수신점(b)이 벽 선분에 가까울수록 감쇠를 서서히 적용.
            // 교차 판정(segmentsIntersect)을 통과한 뒤에만 실행되므로 벽 반대편 누출 없음.
            double wx = w.x2 - w.x1, wy = w.y2 - w.y1;
            double wlen = Math.hypot(wx, wy);
            double boundaryWeight = 1.0;
            if (wlen > EPS) {
                // 수신점 b의 벽 선분까지 수직 거리(px)
                double perpDist = Math.abs((bx - w.x1) * wy - (by - w.y1) * wx) / wlen;
                boundaryWeight = smoothstep(Math.min(1.0, perpDist / WALL_BOUNDARY_SOFT_PX));
            }

            double lossDb = w.attenuationDb(band) * obliqueScale * endpointWeight * boundaryWeight;

            int idx = findCrossingCluster(crossPoints, cross, WALL_CROSS_DEDUP_PX);
            if (idx >= 0) {
                // 같은 교차점(코너/분할벽)에서는 중복 합산 대신 더 큰 손실 하나만 유지
                if (lossDb > crossLossDb.get(idx)) crossLossDb.set(idx, lossDb);
            } else {
                crossPoints.add(cross);
                crossLossDb.add(lossDb);
            }
        }

        double sum = 0.0;
        for (int i = 0; i < crossLossDb.size(); i++) {
            sum += crossLossDb.get(i);
        }
        return sum;
    }

    /**
     * 반사점 계산(이미지 소스 방식): apMirror->rx 직선과 벽 선분의 교점
     */
    public static Point2D reflectionPoint(Point2D ap, Point2D rx, Wall wall) {
        if (ap == null || rx == null || wall == null) return null;
        Point2D w1 = new Point2D(wall.x1, wall.y1);
        Point2D w2 = new Point2D(wall.x2, wall.y2);
        Point2D apMirror = reflectPointOverLine(ap, w1, w2);
        return segmentIntersectionPoint(apMirror, rx, w1, w2);
    }

    /**
     * 벽 법선과 입사 벡터의 cos(theta) (theta: 입사각, 법선 기준)
     */
    public static double incidenceCosine(Point2D incidentFrom, Point2D onWall, Wall wall) {
        if (incidentFrom == null || onWall == null || wall == null) return 1.0;
        double wx = wall.x2 - wall.x1;
        double wy = wall.y2 - wall.y1;
        double wlen = Math.hypot(wx, wy);
        if (wlen < EPS) return 1.0;
        double nx = -wy / wlen;
        double ny = wx / wlen;

        double vx = incidentFrom.getX() - onWall.getX();
        double vy = incidentFrom.getY() - onWall.getY();
        double vlen = Math.hypot(vx, vy);
        if (vlen < EPS) return 1.0;
        vx /= vlen;
        vy /= vlen;

        double cos = Math.abs(nx * vx + ny * vy);
        if (!Double.isFinite(cos)) return 1.0;
        return Math.max(0.0, Math.min(1.0, cos));
    }

    /**
     * Fresnel 반사 손실(dB). WallMaterial에 ITU-R P.2040 파라미터가 없으면 fallback.
     */
    public static double fresnelReflectionLossDb(WallMaterial mat, double freqGhz, double cosThetaI) {
        if (mat == null || !mat.hasPermittivityModel()) {
            return (mat == null) ? 8.0 : mat.reflectionLossDb();
        }
        if (!Double.isFinite(freqGhz) || freqGhz <= 0.0) {
            return mat.reflectionLossDb();
        }

        double epsR = mat.epsilonReal(freqGhz);
        double sigma = mat.conductivity(freqGhz);
        if (!Double.isFinite(epsR) || !Double.isFinite(sigma)) {
            return mat.reflectionLossDb();
        }

        // ITU-R P.2040: eps'' = 17.98 * sigma / f(GHz)
        double epsImag = 17.98 * sigma / freqGhz;
        Complex eps = new Complex(epsR, -epsImag);

        double cosI = Math.max(0.0, Math.min(1.0, cosThetaI));
        double sin2 = Math.max(0.0, 1.0 - cosI * cosI);

        Complex sin2C = new Complex(sin2, 0.0);
        Complex sqrtTerm = Complex.sqrt(eps.sub(sin2C));
        Complex cosC = new Complex(cosI, 0.0);

        Complex rs = cosC.sub(sqrtTerm).div(cosC.add(sqrtTerm));
        Complex rp = eps.mul(cosC).sub(sqrtTerm).div(eps.mul(cosC).add(sqrtTerm));

        double Rs = rs.abs2();
        double Rp = rp.abs2();
        double R = 0.5 * (Rs + Rp);
        if (!Double.isFinite(R)) return mat.reflectionLossDb();

        R = Math.max(1e-6, Math.min(1.0, R));
        return -10.0 * Math.log10(R);
    }

    /**
     * ITU-R P.526 knife-edge 회절 손실(dB)
     */
    public static double knifeEdgeLossDb(double hM, double d1M, double d2M, double freqGhz) {
        if (!Double.isFinite(hM) || !Double.isFinite(d1M) || !Double.isFinite(d2M) || !Double.isFinite(freqGhz)) {
            return 0.0;
        }
        if (d1M <= 0.0 || d2M <= 0.0 || freqGhz <= 0.0) return 0.0;

        double lambda = (C_MPS / 1.0e9) / freqGhz;
        double v = hM * Math.sqrt(2.0 * (d1M + d2M) / (lambda * d1M * d2M));
        if (v <= -0.78) return 0.0;

        double t = v - 0.1;
        double jv = 6.9 + 20.0 * Math.log10(Math.sqrt(t * t + 1.0) + t);
        return Math.max(0.0, jv);
    }

    /**
     * UTD wedge diffraction loss (Luebbers heuristic, simplified).
     *
     * 벽의 끝점(코너)를 쐐기(wedge)로 모델링하여 회절 손실을 계산.
     * Knife-Edge 모델보다 정확한 코너 회절을 제공.
     *
     * 참고: Hindawi - Heuristic UTD Coefficients for Three-Dimensional Diffraction
     *       (International Journal of Antennas and Propagation, 2018)
     *
     * @param hM          코너의 LOS 수직거리 (m) - 음수면 shadow boundary 안쪽
     * @param d1M         소스→코너 거리 (m)
     * @param d2M         코너→관측점 거리 (m)
     * @param freqGhz     주파수 (GHz)
     * @param wedgeAngleN 외각 = n*π (실내 직각 코너: n=1.5, 평면벽 끝: n=2.0)
     * @return 회절 손실 (dB, 양수 = 감쇠)
     */
    public static double utdWedgeDiffractionLossDb(double hM, double d1M, double d2M,
                                                     double freqGhz, double wedgeAngleN) {
        if (!Double.isFinite(hM) || !Double.isFinite(d1M) || !Double.isFinite(d2M)
                || !Double.isFinite(freqGhz) || !Double.isFinite(wedgeAngleN)) {
            return 0.0;
        }
        if (d1M <= 0.0 || d2M <= 0.0 || freqGhz <= 0.0 || wedgeAngleN <= 0.0) return 0.0;

        double lambda = (C_MPS / 1.0e9) / freqGhz;
        double k = 2.0 * Math.PI / lambda;  // 파수

        // n 파라미터 (외각 = nπ)
        double n = Math.max(1.0, Math.min(3.0, wedgeAngleN));

        // Distance parameter L (Luebbers)
        double L = (d1M * d2M) / (d1M + d2M);

        // 입사/회절 각도를 Knife-Edge 높이에서 근사
        // Fresnel 파라미터 v를 통해 유효 각도 추정
        double v = hM * Math.sqrt(2.0 * (d1M + d2M) / (lambda * d1M * d2M));

        // UTD 보정: n 파라미터와 Fresnel transition function을 이용한 회절 계수
        // Luebbers 간략 모델: Knife-Edge 기반에 wedge angle 보정 적용

        // 기본 Knife-Edge 손실
        double keDb;
        if (v <= -0.78) {
            keDb = 0.0;
        } else {
            double t = v - 0.1;
            keDb = 6.9 + 20.0 * Math.log10(Math.sqrt(t * t + 1.0) + t);
            keDb = Math.max(0.0, keDb);
        }

        // UTD wedge 보정 계수:
        // n=2.0 (평면벽 끝, 180°): KE와 동일 → 보정 없음
        // n=1.5 (직각 코너, 270°): 회절이 약간 더 강함 (손실 감소)
        // n=1.0 (날카로운 쐐기, 180°-: 회절 약함 (손실 증가)
        //
        // 보정 공식: UTD_loss = KE_loss * wedgeCorrection
        // wedgeCorrection ≈ (2.0 / n) × Fresnel_transition_factor
        double wedgeCorrection;
        if (n >= 1.99 && n <= 2.01) {
            // 반무한 평면 (half-plane): UTD = KE, 보정 없음
            wedgeCorrection = 1.0;
        } else {
            // Luebbers 근사: 쐐기 각도에 따른 회절 계수 보정
            // n < 2: 더 많은 회절 (실내 코너 뒤쪽으로 신호 전달)
            // Fresnel transition: F(x) ≈ 1 for large x, ≈ √(πx)·e^(jπ/4) for small x
            double kLa = k * L;  // 이 값이 클수록 Fresnel 영역에서 먼 곳
            double fresnelFactor;
            if (kLa > 10.0) {
                fresnelFactor = 1.0;  // 고주파 극한
            } else {
                // Fresnel transition function 근사: F(x) ≈ 1 - exp(-√(π·x))
                fresnelFactor = 1.0 - Math.exp(-Math.sqrt(Math.PI * Math.max(0.0, kLa)));
            }

            // 실내 직각 코너(n=1.5)에서는 회절이 KE보다 약 2-3dB 더 강함
            // n이 작을수록(더 뾰족) 회절 손실이 더 큼
            double angleRatio = 2.0 / n;
            wedgeCorrection = 1.0 / (angleRatio * fresnelFactor + (1.0 - fresnelFactor));
            // clamp: 0.5~1.5 사이
            wedgeCorrection = Math.max(0.5, Math.min(1.5, wedgeCorrection));
        }

        double utdLossDb = keDb * wedgeCorrection;

        // 최종 clamp: 0~40 dB
        return Math.max(0.0, Math.min(40.0, utdLossDb));
    }

    private static double segmentDistance(Point2D a, Point2D b, Point2D c, Point2D d) {
        if (segmentsIntersect(a, b, c, d)) return 0.0;
        double d1 = pointToSegmentDistance(a, c, d);
        double d2 = pointToSegmentDistance(b, c, d);
        double d3 = pointToSegmentDistance(c, a, b);
        double d4 = pointToSegmentDistance(d, a, b);
        return Math.min(Math.min(d1, d2), Math.min(d3, d4));
    }

    private static double pointToSegmentDistance(Point2D p, Point2D a, Point2D b) {
        Point2D c = closestPointOnSegment(p, a, b);
        return p.distance(c);
    }

    private static double wallObliqueScale(Point2D a, Point2D b, Point2D c, Point2D d) {
        double rx = b.getX() - a.getX();
        double ry = b.getY() - a.getY();
        double rLen = Math.hypot(rx, ry);
        if (rLen < EPS) return 1.0;

        double wx = d.getX() - c.getX();
        double wy = d.getY() - c.getY();
        double wLen = Math.hypot(wx, wy);
        if (wLen < EPS) return 1.0;

        // 벽 법선 n = (-wy, wx) / |w|
        double nx = -wy / wLen;
        double ny = wx / wLen;

        // cos(theta_n): 진행방향과 벽 법선 사이 각의 코사인
        double cosThetaN = Math.abs((rx * nx + ry * ny) / rLen);
        if (!Double.isFinite(cosThetaN)) return 1.0;
        cosThetaN = Math.max(MIN_COS_THETA_N, Math.min(1.0, cosThetaN));

        // 유효 벽 내부 경로길이 비율(수직입사 대비)
        return Math.min(MAX_OBLIQUE_PATH_RATIO, 1.0 / cosThetaN);
    }

    private static int findCrossingCluster(java.util.List<Point2D> points, Point2D p, double tolPx) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).distance(p) <= tolPx) return i;
        }
        return -1;
    }

    private static double endpointRelaxWeight(Point2D a, Point2D b, Point2D cross) {
        double dEnd = Math.min(a.distance(cross), b.distance(cross));
        if (!Double.isFinite(dEnd) || dEnd >= CROSS_ENDPOINT_RELAX_PX) return 1.0;
        double t = Math.max(0.0, Math.min(1.0, dEnd / CROSS_ENDPOINT_RELAX_PX));
        return CROSS_ENDPOINT_MIN_WEIGHT + (1.0 - CROSS_ENDPOINT_MIN_WEIGHT) * t;
    }

    private static double sideOfLine(Point2D a, Point2D b, Point2D p) {
        return (b.getX() - a.getX()) * (p.getY() - a.getY())
                - (b.getY() - a.getY()) * (p.getX() - a.getX());
    }

    public static boolean isSegmentBlocked(Point2D a,
                                           Point2D b,
                                           java.util.List<Wall> walls,
                                           Wall ignoreWall,
                                           Point2D ignorePoint,
                                           double tolPx) {
        if (a == null || b == null || walls == null) return false;
        for (Wall w : walls) {
            if (w == null) continue;
            WallMaterial mat = w.getMaterial();
            if (mat == WallMaterial.DOOR || mat == WallMaterial.WINDOW) continue; // 문/창문은 감쇠만 적용하고 통과 허용
            boolean isIgnoredWall = (ignoreWall != null && w == ignoreWall);
            Point2D w1 = new Point2D(w.x1, w.y1);
            Point2D w2 = new Point2D(w.x2, w.y2);
            double dist = segmentDistance(a, b, w1, w2);
            if (dist > tolPx) continue;

            if (isIgnoredWall && ignorePoint != null) {
                double dWall = pointToSegmentDistance(ignorePoint, w1, w2);
                double dSeg = pointToSegmentDistance(ignorePoint, a, b);
                if (dWall <= tolPx && dSeg <= tolPx) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    public static double pathIntersectionTolPx() {
        return PATH_INTERSECTION_TOL_PX;
    }

    /**
     * 단일 전파 경로(LOS/반사/회절 등)를 표현
     */
    public static final class Path {
        public final double lengthMeters;
        public final double wallLossDb;
        public final double extraLossDb; // 반사/회절 등 추가 손실
        public final Point2D via; // 반사점/코너점 등(없으면 null)
        public final Wall viaWall; // 반사에 사용된 벽(없으면 null)

        public Path(double lengthMeters, double wallLossDb, double extraLossDb, Point2D via, Wall viaWall) {
            this.lengthMeters = lengthMeters;
            this.wallLossDb = wallLossDb;
            this.extraLossDb = extraLossDb;
            this.via = via;
            this.viaWall = viaWall;
        }
    }

    /**
     * 1차 반사 경로(이미지 소스 방식) 후보를 생성
     *
     * @param ap AP 위치(px)
     * @param rx 수신 위치(px)
     * @param wall 반사에 사용할 벽(선분)
     * @param scaleMPerPx 픽셀→미터 스케일
     * @param reflectionLossDb 반사 추가 손실(dB)
     * @return 유효한 반사 경로면 Path, 아니면 null
     */
    public static Path buildSingleBounceReflection(Point2D ap,
                                                   Point2D rx,
                                                   Wall wall,
                                                   java.util.List<Wall> walls,
                                                   double scaleMPerPx,
                                                   double reflectionLossDb) {
        return buildSingleBounceReflection(ap, rx, wall, walls, scaleMPerPx, reflectionLossDb, null);
    }

    public static Path buildSingleBounceReflection(Point2D ap,
                                                   Point2D rx,
                                                   Wall wall,
                                                   java.util.List<Wall> walls,
                                                   double scaleMPerPx,
                                                   double reflectionLossDb,
                                                   Band band) {
        Point2D w1 = new Point2D(wall.x1, wall.y1);
        Point2D w2 = new Point2D(wall.x2, wall.y2);

        // AP/RX가 벽의 서로 다른 쪽에 있으면(벽이 막는 경우) 반사 제외
        double s1 = sideOfLine(w1, w2, ap);
        double s2 = sideOfLine(w1, w2, rx);
        if (s1 * s2 < -EPS) return null;

        // AP를 벽의 연장 직선에 대해 반사시킨 가상 AP'
        Point2D apMirror = reflectPointOverLine(ap, w1, w2);

        // apMirror -> rx 직선과 벽 선분(w1-w2)의 교점이 반사점
        Point2D p = segmentIntersectionPoint(apMirror, rx, w1, w2);
        if (p == null) return null;
        double endpointMarginPx = reflectionEndpointMarginPx(wall, scaleMPerPx);
        if (Math.min(p.distance(w1), p.distance(w2)) < endpointMarginPx) return null;
        if (isSegmentBlocked(ap, p, walls, wall, p, PATH_INTERSECTION_TOL_PX)) return null;
        if (isSegmentBlocked(p, rx, walls, wall, p, PATH_INTERSECTION_TOL_PX)) return null;

        // 실제 경로 길이(AP->p + p->rx)
        double d1m = ap.distance(p) * scaleMPerPx;
        double d2m = p.distance(rx) * scaleMPerPx;
        double lenM = d1m + d2m;

        // 반사에 사용된 벽은 '관통'으로 세지 않도록 제외
        double wl1 = wallLossAlong(ap.getX(), ap.getY(), p.getX(), p.getY(), walls, wall, band);
        double wl2 = wallLossAlong(p.getX(), p.getY(), rx.getX(), rx.getY(), walls, wall, band);
        double wallLossDb = wl1 + wl2;

        // 너무 비현실적인 경로(극단적으로 긴 우회)는 버림
        double losM = ap.distance(rx) * scaleMPerPx;
        if (lenM > losM * 2.5) return null;

        return new Path(lenM, wallLossDb, reflectionLossDb, p, wall);
    }

    /**
     * 1차 회절 경로 (코너 기반 근사)
     *
     * @param ap AP 위치(px)
     * @param rx 수신 위치(px)
     * @param corner 회절 코너(px)
     * @param walls 벽 리스트
     * @param scaleMPerPx px → m 스케일
     * @param diffLossDb 회절 추가 손실(dB)
     * @return Path(경로 길이/벽 감쇠/회절 손실 포함)
     */
    public static Path buildSingleCornerDiffraction(Point2D ap,
                                                    Point2D rx,
                                                    Point2D corner,
                                                    java.util.List<Wall> walls,
                                                    double scaleMPerPx,
                                                    double diffLossDb) {
        return buildSingleCornerDiffraction(ap, rx, corner, walls, scaleMPerPx, diffLossDb, null);
    }

    public static Path buildSingleCornerDiffraction(Point2D ap,
                                                    Point2D rx,
                                                    Point2D corner,
                                                    java.util.List<Wall> walls,
                                                    double scaleMPerPx,
                                                    double diffLossDb,
                                                    Band band) {
        // 경로 길이(m)
        double d1m = ap.distance(corner) * scaleMPerPx;
        double d2m = corner.distance(rx) * scaleMPerPx;
        double lenM = d1m + d2m;

        // 벽 관통 감쇠(dB): ap->corner + corner->rx
        double wl1 = wallLossAlong(ap.getX(), ap.getY(), corner.getX(), corner.getY(), walls, (Wall) null, band);
        double wl2 = wallLossAlong(corner.getX(), corner.getY(), rx.getX(), rx.getY(), walls, (Wall) null, band);
        double wallLossDb = wl1 + wl2;

        return new Path(lenM, wallLossDb, diffLossDb, corner, null);
    }

    private static double reflectionEndpointMarginPx(Wall wall, double scaleMPerPx) {
        double wallLenPx = Math.hypot(wall.x2 - wall.x1, wall.y2 - wall.y1);
        double byLength = wallLenPx * 0.20; // 벽 길이의 20%
        double byMeters = 0.0;
        if (Double.isFinite(scaleMPerPx) && scaleMPerPx > 1e-9) {
            byMeters = 0.40 / scaleMPerPx; // 0.4m
        }
        return Math.max(REFLECTION_ENDPOINT_MARGIN_MIN_PX, Math.max(byLength, byMeters));
    }

    // ===== 색상 매핑 및 픽셀 유틸 =====

    /**
     * RSSI → 히트맵 색 (반투명 0.55). 색 정의는 {@link app.model.RssiLevel} 한 곳에 있다 —
     * 결과 카드·하단 범례와 같은 등급 색이 나오도록 dBm 기준점에 고정된 그라데이션.
     * (예전엔 범례 범위 -96~-10 dBm 에 고르게 펴서 -55 dBm 이 초록으로 칠해졌다.)
     */
    public static Color rssiToColor(double rssi) {
        Color c = app.model.RssiLevel.gradientColor(rssi);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 0.55);
    }

    // ===== 내부 복소수 유틸 =====
    private static final class Complex {
        final double re;
        final double im;

        Complex(double re, double im) {
            this.re = re;
            this.im = im;
        }

        Complex add(Complex o) { return new Complex(re + o.re, im + o.im); }
        Complex sub(Complex o) { return new Complex(re - o.re, im - o.im); }
        Complex mul(Complex o) { return new Complex(re * o.re - im * o.im, re * o.im + im * o.re); }
        Complex div(Complex o) {
            double den = o.re * o.re + o.im * o.im;
            if (den < EPS) return new Complex(0.0, 0.0);
            return new Complex((re * o.re + im * o.im) / den, (im * o.re - re * o.im) / den);
        }
        double abs2() { return re * re + im * im; }

        static Complex sqrt(Complex z) {
            double r = Math.hypot(z.re, z.im);
            double u = Math.sqrt(Math.max(0.0, (r + z.re) / 2.0));
            double v = Math.sqrt(Math.max(0.0, (r - z.re) / 2.0));
            if (z.im < 0) v = -v;
            return new Complex(u, v);
        }
    }

    // ===== 간단 박스 블러 =====

    /**
     * 간단한 box blur (수평 + 수직 1차)
     * radius 픽셀 만큼 양쪽으로 윈도우
     */
    public static WritableImage boxBlur(WritableImage src, int radius) {
        if (radius <= 0) return src;

        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        WritableImage tmp = new WritableImage(w, h);
        WritableImage dst = new WritableImage(w, h);
        PixelReader pr = src.getPixelReader();
        PixelWriter pwTmp = tmp.getPixelWriter();
        PixelWriter pwDst = dst.getPixelWriter();

        // 수평 방향
        for (int y = 0; y < h; y++) {
            double a = 0, r = 0, g = 0, b = 0;
            int win = radius * 2 + 1;

            for (int x = -radius; x <= radius; x++) {
                int xx = Math.min(w - 1, Math.max(0, x));
                int argb = pr.getArgb(xx, y);
                a += (argb >>> 24) & 0xFF;
                r += (argb >>> 16) & 0xFF;
                g += (argb >>> 8) & 0xFF;
                b += (argb) & 0xFF;
            }

            for (int x = 0; x < w; x++) {
                int outA = (int) Math.round(a / win);
                int outR = (int) Math.round(r / win);
                int outG = (int) Math.round(g / win);
                int outB = (int) Math.round(b / win);
                pwTmp.setArgb(x, y, (outA << 24) | (outR << 16) | (outG << 8) | outB);

                int xOut = Math.max(0, x - radius);
                int xIn  = Math.min(w - 1, x + radius + 1);
                int argbOut = pr.getArgb(xOut, y);
                int argbIn  = pr.getArgb(xIn, y);

                a += ((argbIn >>> 24) & 0xFF) - ((argbOut >>> 24) & 0xFF);
                r += ((argbIn >>> 16) & 0xFF) - ((argbOut >>> 16) & 0xFF);
                g += ((argbIn >>> 8) & 0xFF) - ((argbOut >>> 8) & 0xFF);
                b += (argbIn & 0xFF) - (argbOut & 0xFF);
            }
        }

        // 수직 방향
        PixelReader pr2 = tmp.getPixelReader();
        for (int x = 0; x < w; x++) {
            double a = 0, r = 0, g = 0, b = 0;
            int win = radius * 2 + 1;

            for (int y = -radius; y <= radius; y++) {
                int yy = Math.min(h - 1, Math.max(0, y));
                int argb = pr2.getArgb(x, yy);
                a += (argb >>> 24) & 0xFF;
                r += (argb >>> 16) & 0xFF;
                g += (argb >>> 8) & 0xFF;
                b += (argb) & 0xFF;
            }

            for (int y = 0; y < h; y++) {
                int outA = (int) Math.round(a / win);
                int outR = (int) Math.round(r / win);
                int outG = (int) Math.round(g / win);
                int outB = (int) Math.round(b / win);
                pwDst.setArgb(x, y, (outA << 24) | (outR << 16) | (outG << 8) | outB);

                int yOut = Math.max(0, y - radius);
                int yIn  = Math.min(h - 1, y + radius + 1);
                int argbOut = pr2.getArgb(x, yOut);
                int argbIn  = pr2.getArgb(x, yIn);

                a += ((argbIn >>> 24) & 0xFF) - ((argbOut >>> 24) & 0xFF);
                r += ((argbIn >>> 16) & 0xFF) - ((argbOut >>> 16) & 0xFF);
                g += ((argbIn >>> 8) & 0xFF) - ((argbOut >>> 8) & 0xFF);
                b += (argbIn & 0xFF) - (argbOut & 0xFF);
            }
        }

        return dst;
    }

    // ===== 경로손실 모델 =====

    /**
     * 경로손실(dB) 계산
     *
     * PL(d) = 32.44 + 20 log10(f_MHz) + 10 n log10(d_m)
     *
     * - fGhz : 주파수(GHz) (예: 2.4, 5.0, 6.0)
     * - n    : 경로손실 지수 (1.6 ~ 4 정도)
     */
    public static double pathLossDb(double dMeters, double fGhz, double n) {
        // d0=1m 기준 로그-거리 모델을 사용하므로, d<1m 구간은 1m로 캡핑한다.
        // (근거리에서 비현실적으로 RSSI가 과도하게 높아지는 현상 방지)
        double d = Math.max(dMeters, 1.0);

        double fMHz = fGhz * 1000.0;

        // 1m 기준 FSPL (d0 = 1m)
        // FSPL(1m) = 32.44 + 20log10(fMHz) - 60
        double pl0 = 32.44 + 20 * Math.log10(fMHz) - 60.0;

        // 로그-거리 경로손실 모델: PL(d) = PL0 + 10 n log10(d / d0), d0 = 1m
        return pl0 + 10.0 * n * Math.log10(d / 1.0);
    }

    /**
     * 근접 밴드 보정(dB).
     *
     * - 동일 EIRP 가정의 로그거리/FSPL 모델은 근거리에서 5/6GHz가 2.4GHz 대비 과도하게 약하게 예측되는 경우가 있다.
     * - 실측 환경에서 AP 바로 앞(약 1~2m 내)에서는 단말 AGC/수신 체인 특성으로 밴드 간 RSSI가 압축되어 비슷하게 보이기도 한다.
     * - 이를 반영해 d<=1m에서는 2.4 기준 주파수 차이를 100% 보정하고, 4m에서 0으로 선형 감쇠한다.
     */
    public static double nearFieldBandCompensationDb(double dMeters, double fGhz) {
        if (!Double.isFinite(dMeters) || !Double.isFinite(fGhz)) return 0.0;
        double freq = Math.max(2.4, fGhz);
        double freqDeltaDb = 20.0 * Math.log10(freq / 2.4);
        if (freqDeltaDb <= 0.0) return 0.0;

        // d <= 1m: 100% 보정 (FSPL 차이 전부 상쇄)
        if (dMeters <= NEAR_BAND_COMP_START_M) return freqDeltaDb;

        // 1m ~ 4m: 선형 감쇠 (기존 동일)
        if (dMeters < NEAR_BAND_COMP_END_M) {
            double t = (NEAR_BAND_COMP_END_M - dMeters) / (NEAR_BAND_COMP_END_M - NEAR_BAND_COMP_START_M);
            return freqDeltaDb * t;
        }

        // 4m 이후: 지수 감쇠 — 실내 다중경로로 5GHz FSPL 격차가 점진적으로 압축되는 효과
        // 실측 데이터: 아파트 5~10m 구간에서 5GHz ≈ 2.4GHz + 2~3 dB (FSPL 이론 6.4 dB보다 훨씬 작음)
        double excess = dMeters - NEAR_BAND_COMP_END_M;
        return freqDeltaDb * Math.exp(-excess / NEAR_BAND_COMP_DECAY_TAU_M);
    }

    /**
     * LOS 구간 빔포밍 근사 이득(dB).
     * - 실제 CSI 기반 빔포밍은 아니며, mouse-RSSI/heatmap에서 체감 오차를 줄이기 위한 단순 파라미터 모델.
     * - NLOS(벽 관통 포함)에서는 이득을 0으로 둔다.
     */
    public static double beamformingGainDb(Band band, double dMeters, boolean los) {
        if (!los || !Double.isFinite(dMeters) || band == null) return 0.0;

        double maxGain = switch (band) {
            case GHZ_24 -> BF_GAIN_24_DB;
            case GHZ_5 -> BF_GAIN_5_DB;
            case GHZ_6 -> BF_GAIN_6_DB;
        };
        if (maxGain <= 0.0) return 0.0;

        double x = Math.max(0.0, dMeters - BF_RISE_START_M);
        double rise = 1.0 - Math.exp(-x / BF_RISE_TAU_M);

        double far = 1.0;
        if (dMeters > BF_FAR_DECAY_START_M) {
            far = Math.exp(-(dMeters - BF_FAR_DECAY_START_M) / BF_FAR_DECAY_TAU_M);
        }

        return maxGain * rise * far;
    }
}
