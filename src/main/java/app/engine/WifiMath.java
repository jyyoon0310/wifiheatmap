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
    private static final double NEAR_BAND_COMP_END_M = 4.0;
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

    /**
     * 두 벡터(u, v)의 각도(0~180도)
     */
    public static double angleDeg(Point2D u, Point2D v) {
        double du = u.magnitude();
        double dv = v.magnitude();
        if (du < EPS || dv < EPS) return 0.0;
        double cos = (u.getX() * v.getX() + u.getY() * v.getY()) / (du * dv);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.toDegrees(Math.acos(cos));
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
     * 모든 활성 Band에 대한 wallLoss를 한 번의 교차점 계산으로 일괄 반환.
     * 동일 경로(ax,ay)→(bx,by)에서 Band별로 attenuationDb만 다르므로,
     * 교차 벽 탐색 및 각도/경계 보정을 1회만 수행하고 Band별 감쇠를 별도 합산한다.
     *
     * @param bands  계산할 Band 목록 (null 항목 허용, 그 경우 해당 인덱스는 0.0 반환)
     * @return bands 인덱스와 1:1 대응하는 wallLoss(dB) 배열
     */
    public static double[] wallLossAlongAllBands(double ax, double ay,
                                                  double bx, double by,
                                                  java.util.List<Wall> walls,
                                                  Band[] bands) {
        double[] result = new double[bands.length];
        if (walls == null || walls.isEmpty()) return result;

        Point2D a = new Point2D(ax, ay);
        Point2D b = new Point2D(bx, by);

        // 교차 벽별로 (교차점, 공통 스케일, Band별 기본 감쇠) 수집
        java.util.ArrayList<Point2D> crossPoints = new java.util.ArrayList<>();
        // 교차점별로 Band수 크기의 손실 배열 저장
        java.util.ArrayList<double[]> crossLossPerBand = new java.util.ArrayList<>();

        double wx_full = bx - ax, wy_full = by - ay; // 수신점 방향 벡터 (boundaryWeight용)

        for (Wall w : walls) {
            if (w == null) continue;
            Point2D c = new Point2D(w.x1, w.y1);
            Point2D d = new Point2D(w.x2, w.y2);
            if (!segmentsIntersect(a, b, c, d)) continue;

            Point2D cross = segmentIntersectionPoint(a, b, c, d);
            if (cross == null) continue;

            double obliqueScale = wallObliqueScale(a, b, c, d);
            double endpointWeight = endpointRelaxWeight(a, b, cross);

            double wx = w.x2 - w.x1, wy = w.y2 - w.y1;
            double wlen = Math.hypot(wx, wy);
            double boundaryWeight = 1.0;
            if (wlen > EPS) {
                double perpDist = Math.abs((bx - w.x1) * wy - (by - w.y1) * wx) / wlen;
                boundaryWeight = smoothstep(Math.min(1.0, perpDist / WALL_BOUNDARY_SOFT_PX));
            }

            double commonScale = obliqueScale * endpointWeight * boundaryWeight;

            // Band별 손실 계산
            double[] lossPerBand = new double[bands.length];
            for (int bi = 0; bi < bands.length; bi++) {
                lossPerBand[bi] = w.attenuationDb(bands[bi]) * commonScale;
            }

            int idx = findCrossingCluster(crossPoints, cross, WALL_CROSS_DEDUP_PX);
            if (idx >= 0) {
                // 같은 교차점: Band별로 더 큰 손실 유지
                double[] existing = crossLossPerBand.get(idx);
                for (int bi = 0; bi < bands.length; bi++) {
                    if (lossPerBand[bi] > existing[bi]) existing[bi] = lossPerBand[bi];
                }
            } else {
                crossPoints.add(cross);
                crossLossPerBand.add(lossPerBand);
            }
        }

        // 교차점별 합산
        for (double[] lossPerBand : crossLossPerBand) {
            for (int bi = 0; bi < bands.length; bi++) {
                result[bi] += lossPerBand[bi];
            }
        }
        return result;
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
     * 선분(a,b) 경로가 벽을 몇 개 관통하는지(디버깅/회절 후보 필터링에 유용)
     */
    public static int wallCrossCount(double ax, double ay,
                                     double bx, double by,
                                     java.util.List<Wall> walls,
                                     Wall ignoreWall) {
        int cnt = 0;
        Point2D a = new Point2D(ax, ay);
        Point2D b = new Point2D(bx, by);
        for (Wall w : walls) {
            if (ignoreWall != null && w == ignoreWall) continue;
            if (segmentsIntersect(a, b,
                    new Point2D(w.x1, w.y1),
                    new Point2D(w.x2, w.y2))) {
                cnt++;
            }
        }
        return cnt;
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

    /**
     * 2차 회절(corner1 → corner2): AP → corner1 → corner2 → RX 경로
     *
     * @param ap          AP 위치
     * @param rx          수신점 위치
     * @param corner1     1차 회절 코너
     * @param corner2     2차 회절 코너
     * @param walls       전체 벽 목록
     * @param scaleMPerPx 픽셀 → 미터 변환 스케일
     * @param diffLossDb  두 코너의 knife-edge 손실 합(dB)
     * @param band        주파수 밴드
     * @return Path 객체 (경로 길이, 벽 손실, 회절 추가 손실 포함), 유효하지 않으면 null
     */
    public static Path buildDoubleCornerDiffraction(Point2D ap,
                                                    Point2D rx,
                                                    Point2D corner1,
                                                    Point2D corner2,
                                                    java.util.List<Wall> walls,
                                                    double scaleMPerPx,
                                                    double diffLossDb,
                                                    Band band) {
        double lenM = (ap.distance(corner1) + corner1.distance(corner2) + corner2.distance(rx)) * scaleMPerPx;

        // 3개 세그먼트(ap→c1, c1→c2, c2→rx)의 벽 관통 감쇠 합산
        double wl1 = wallLossAlong(ap.getX(), ap.getY(), corner1.getX(), corner1.getY(), walls, (Wall) null, band);
        double wl2 = wallLossAlong(corner1.getX(), corner1.getY(), corner2.getX(), corner2.getY(), walls, (Wall) null, band);
        double wl3 = wallLossAlong(corner2.getX(), corner2.getY(), rx.getX(), rx.getY(), walls, (Wall) null, band);
        double wallLossDb = wl1 + wl2 + wl3;

        return new Path(lenM, wallLossDb, diffLossDb, corner1, null);
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
     * RSSI(dBm) → 강(빨강) ~ 중(노랑) ~ 약(초록) 색상 매핑
     */
    public static Color rssiToColor(double rssi, double vmin, double vmax) {
        double t = (rssi - vmin) / (vmax - vmin);
        t = Math.max(0.0, Math.min(1.0, t));

        // [t, R, G, B] — 5단계 색상 정지점 (NetSpot 스타일 세밀한 그라디언트)
        double[][] stops = {
                {0.00,   0, 100,   0},   // Dark Green (매우 약한 신호)
                {0.25,   0, 200,   0},   // Green
                {0.50, 255, 235,   0},   // Yellow
                {0.75, 255, 140,   0},   // Orange
                {1.00, 230,  40,  20}    // Red (매우 강한 신호)
        };

        int i = 0;
        while (i < stops.length - 1 && t > stops[i + 1][0]) {
            i++;
        }

        double t0 = stops[i][0];
        double t1 = stops[i + 1][0];
        double u = (t1 == t0) ? 0.0 : (t - t0) / (t1 - t0);

        double r = stops[i][1] + u * (stops[i + 1][1] - stops[i][1]);
        double g = stops[i][2] + u * (stops[i + 1][2] - stops[i][2]);
        double b = stops[i][3] + u * (stops[i + 1][3] - stops[i][3]);

        return new Color(r / 255.0, g / 255.0, b / 255.0, 0.55);
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

    /**
     * (x,y)에서 wStep×hStep 블록을 색 c로 채움
     */
    public static void fillBlock(PixelWriter pw,
                                 int x, int y,
                                 int wStep, int hStep,
                                 int w, int h,
                                 Color c) {
        for (int yy = y; yy < Math.min(y + hStep, h); yy++) {
            for (int xx = x; xx < Math.min(x + wStep, w); xx++) {
                pw.setColor(xx, yy, c);
            }
        }
    }

    // ===== Edge-Preserving Bilateral Filter (Tomasi & Manduchi 1998) =====

    /**
     * dBm 2D 그리드에 대한 Bilateral Filter.
     *
     * 공간적으로 가까운 픽셀끼리 스무딩하되,
     * dBm 차이가 큰 경계(= 벽에 의한 신호 불연속)는 자동으로 보존.
     *
     * W(i,j→p,q) = exp(-(Δd²)/(2σ_s²)) × exp(-(ΔdBm²)/(2σ_r²))
     *
     * @param dbmGrid      width×height dBm 값 (row-major, dbmGrid[y*width+x])
     * @param width        이미지 폭 (px)
     * @param height       이미지 높이 (px)
     * @param spatialSigma 공간 시그마 (px) — 블러 반경 (권장: gridStepPx)
     * @param rangeSigma   범위 시그마 (dBm) — 값 차이 허용치 (권장: 6~8)
     * @return 새로운 스무딩된 dBm 배열
     */
    public static double[] bilateralFilterDbm(double[] dbmGrid, int width, int height,
                                               double spatialSigma, double rangeSigma) {
        if (dbmGrid == null || width <= 0 || height <= 0) return dbmGrid;
        if (spatialSigma <= 0.0 || rangeSigma <= 0.0) return dbmGrid.clone();

        int radius = (int) Math.ceil(2.0 * spatialSigma);
        double spatialDenom = 2.0 * spatialSigma * spatialSigma;
        double rangeDenom = 2.0 * rangeSigma * rangeSigma;

        // 공간 가우시안 커널 사전 계산 (대칭이므로 1/4만 계산)
        double[] spatialKernel = new double[(radius + 1) * (radius + 1)];
        for (int dy = 0; dy <= radius; dy++) {
            for (int dx = 0; dx <= radius; dx++) {
                spatialKernel[dy * (radius + 1) + dx] = Math.exp(-(dx * dx + dy * dy) / spatialDenom);
            }
        }

        double[] result = new double[width * height];

        // 행별 병렬 처리
        java.util.stream.IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {
                double centerDbm = dbmGrid[y * width + x];
                double weightSum = 0.0;
                double valueSum = 0.0;

                int yMin = Math.max(0, y - radius);
                int yMax = Math.min(height - 1, y + radius);
                int xMin = Math.max(0, x - radius);
                int xMax = Math.min(width - 1, x + radius);

                for (int qy = yMin; qy <= yMax; qy++) {
                    int dy = Math.abs(qy - y);
                    for (int qx = xMin; qx <= xMax; qx++) {
                        int dx = Math.abs(qx - x);
                        double neighborDbm = dbmGrid[qy * width + qx];

                        // 공간 가중치 (사전 계산된 커널)
                        double ws = spatialKernel[dy * (radius + 1) + dx];

                        // 범위 가중치 (dBm 차이 → 벽 경계에서 자동 억제)
                        double dDbm = neighborDbm - centerDbm;
                        double wr = Math.exp(-(dDbm * dDbm) / rangeDenom);

                        double w = ws * wr;
                        weightSum += w;
                        valueSum += w * neighborDbm;
                    }
                }

                result[y * width + x] = (weightSum > 0.0) ? valueSum / weightSum : centerDbm;
            }
        });

        return result;
    }

    // ===== Separable Gaussian Blur (dBm 그리드용) =====

    /**
     * 1D separable Gaussian blur on dBm grid.
     * bilateral 필터 후 residual ray(바퀴살) 잔재 제거용 경량 후처리.
     *
     * 수평 → 수직 순서로 두 번 1D 컨볼루션 수행 (O(n·r), separability 활용).
     * 경계: clamp-to-edge (border 픽셀을 그대로 연장)
     *
     * @param src    원본 dBm 배열 (row-major, src[y*width+x])
     * @param width  이미지 폭 (px)
     * @param height 이미지 높이 (px)
     * @param sigma  가우시안 σ (px), 권장: 1.0~2.0
     * @return 새로운 스무딩된 dBm 배열
     */
    public static double[] gaussianBlurDbm(double[] src, int width, int height, double sigma) {
        if (src == null || width <= 0 || height <= 0 || sigma <= 0.0) return src;

        int radius = (int) Math.ceil(3.0 * sigma); // 3σ rule: 99.7% 포함
        int kernelSize = radius * 2 + 1;

        // 1D 가우시안 커널 사전 계산
        double[] kernel = new double[kernelSize];
        double twoSigmaSq = 2.0 * sigma * sigma;
        double kernelSum = 0.0;
        for (int i = 0; i < kernelSize; i++) {
            int x = i - radius;
            kernel[i] = Math.exp(-(x * x) / twoSigmaSq);
            kernelSum += kernel[i];
        }
        for (int i = 0; i < kernelSize; i++) {
            kernel[i] /= kernelSum; // 정규화
        }

        double[] tmp = new double[width * height]; // 수평 패스 결과
        double[] dst = new double[width * height]; // 수직 패스 결과 (최종)

        // 수평 패스 (각 행에 1D 컨볼루션)
        java.util.stream.IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {
                double sum = 0.0;
                for (int k = 0; k < kernelSize; k++) {
                    int sx = Math.max(0, Math.min(width - 1, x + k - radius)); // clamp
                    sum += kernel[k] * src[y * width + sx];
                }
                tmp[y * width + x] = sum;
            }
        });

        // 수직 패스 (각 열에 1D 컨볼루션)
        java.util.stream.IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {
                double sum = 0.0;
                for (int k = 0; k < kernelSize; k++) {
                    int sy = Math.max(0, Math.min(height - 1, y + k - radius)); // clamp
                    sum += kernel[k] * tmp[sy * width + x];
                }
                dst[y * width + x] = sum;
            }
        });

        return dst;
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

    // ===== Bicubic (Catmull-Rom) 보간 =====

    /**
     * 1D Catmull-Rom spline 보간.
     * p0, p1, p2, p3: 4개 제어점 값
     * t: p1~p2 구간 내 보간 위치 [0, 1]
     *
     * p(t) = 0.5 * ((2·p1) + (-p0+p2)·t + (2·p0 - 5·p1 + 4·p2 - p3)·t² + (-p0 + 3·p1 - 3·p2 + p3)·t³)
     */
    public static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1)
                + (-p0 + p2) * t
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    /**
     * 2D Bicubic Catmull-Rom 보간 (separable).
     * grid4x4[row][col]: 4×4 이웃 격자점 값 (row=0..3, col=0..3)
     *   - grid4x4[1][1] ~ grid4x4[2][2]가 보간 대상 셀의 4 꼭짓점
     *   - grid4x4[0][*], grid4x4[3][*] 등은 외곽 패딩(clamp 처리된)
     * tx, ty: 셀 내 보간 위치 [0, 1]
     *
     * 1) Y 방향으로 4줄 각각 Catmull-Rom → 4개 중간값
     * 2) X 방향으로 1줄 Catmull-Rom → 최종값
     */
    public static double bicubicCatmullRom(double[][] grid4x4, double tx, double ty) {
        // 각 행을 X 방향으로 보간
        double c0 = catmullRom(grid4x4[0][0], grid4x4[0][1], grid4x4[0][2], grid4x4[0][3], tx);
        double c1 = catmullRom(grid4x4[1][0], grid4x4[1][1], grid4x4[1][2], grid4x4[1][3], tx);
        double c2 = catmullRom(grid4x4[2][0], grid4x4[2][1], grid4x4[2][2], grid4x4[2][3], tx);
        double c3 = catmullRom(grid4x4[3][0], grid4x4[3][1], grid4x4[3][2], grid4x4[3][3], tx);
        // Y 방향으로 보간
        return catmullRom(c0, c1, c2, c3, ty);
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

        if (dMeters <= NEAR_BAND_COMP_START_M) return freqDeltaDb;
        if (dMeters >= NEAR_BAND_COMP_END_M) return 0.0;

        double t = (NEAR_BAND_COMP_END_M - dMeters) / (NEAR_BAND_COMP_END_M - NEAR_BAND_COMP_START_M);
        t = Math.max(0.0, Math.min(1.0, t));
        return freqDeltaDb * t;
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
