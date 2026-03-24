package app.engine;

import app.model.Band;
import app.model.Wall;
import app.model.WallMaterial;
import javafx.geometry.Point2D;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    // 벽 감쇠를 부드럽게 만드는 전이 거리(px)
    private static final double WALL_SOFT_PX = 8.0;
    // 반사점이 벽 끝점에 너무 가까우면 반사로 인정하지 않음(최소치)
    private static final double REFLECTION_ENDPOINT_MARGIN_MIN_PX = 3.0;
    // 경로 차단 판정 허용오차(px): 작을수록 엄격
    private static final double PATH_INTERSECTION_TOL_PX = 1.5;
    // 광속(m/s)
    private static final double C_MPS = 299_792_458.0;

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
     * 교차 판정(segmentsIntersect) 기반: 경로가 벽 선분을 실제로 관통할 때만 감쇠 합산.
     * soft-distance blending 제거 → 벽 밖으로 신호 누출 방지.
     *
     * @param band null이면 2.4GHz로 취급
     */
    public static double wallLossAlong(double ax, double ay,
                                       double bx, double by,
                                       java.util.List<Wall> walls,
                                       Wall ignoreWall,
                                       Band band) {
        double sum = 0.0;
        Point2D a = new Point2D(ax, ay);
        Point2D b = new Point2D(bx, by);
        for (Wall w : walls) {
            if (w == null) continue;
            if (ignoreWall != null && w == ignoreWall) continue;
            Point2D c = new Point2D(w.x1, w.y1);
            Point2D d = new Point2D(w.x2, w.y2);
            if (segmentsIntersect(a, b, c, d)) {
                sum += w.attenuationDb(band);
            }
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

    private static double smoothstep(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
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
            Point2D w1 = new Point2D(w.x1, w.y1);
            Point2D w2 = new Point2D(w.x2, w.y2);
            // 교차 판정 기반: 경로가 벽 선분을 실제로 관통할 때만 "막힘"으로 판정
            if (!segmentsIntersect(a, b, w1, w2)) continue;

            // ignoreWall: 반사/회절 기준 벽은 끝점 접촉(교차점이 ignorePoint와 일치)이면 제외
            if (ignoreWall != null && w == ignoreWall && ignorePoint != null) {
                Point2D ip = segmentIntersectionPoint(a, b, w1, w2);
                if (ip != null && ip.distance(ignorePoint) <= tolPx) {
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
            if (w == null) continue;
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
        return buildSingleCornerDiffraction(ap, rx, corner, walls, scaleMPerPx, diffLossDb, null, null);
    }

    public static Path buildSingleCornerDiffraction(Point2D ap,
                                                    Point2D rx,
                                                    Point2D corner,
                                                    java.util.List<Wall> walls,
                                                    double scaleMPerPx,
                                                    double diffLossDb,
                                                    Band band) {
        return buildSingleCornerDiffraction(ap, rx, corner, walls, scaleMPerPx, diffLossDb, band, null);
    }

    public static Path buildSingleCornerDiffraction(Point2D ap,
                                                    Point2D rx,
                                                    Point2D corner,
                                                    java.util.List<Wall> walls,
                                                    double scaleMPerPx,
                                                    double diffLossDb,
                                                    Band band,
                                                    Wall ownerWall) {
        // 경로 길이(m)
        double d1m = ap.distance(corner) * scaleMPerPx;
        double d2m = corner.distance(rx) * scaleMPerPx;
        double lenM = d1m + d2m;

        // 벽 관통 감쇠(dB): ap->corner + corner->rx
        // ownerWall 제외: corner가 ownerWall의 끝점이므로 교차로 잘못 판정되는 것을 막음
        double wl1 = wallLossAlong(ap.getX(), ap.getY(), corner.getX(), corner.getY(), walls, ownerWall, band);
        double wl2 = wallLossAlong(corner.getX(), corner.getY(), rx.getX(), rx.getY(), walls, ownerWall, band);
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
     * RSSI(dBm) → 8단계 컬러맵.
     * deep navy(약) → blue → cyan → green → yellow → orange → red → dark red(강)
     */
    public static Color rssiToColor(double rssi, double vmin, double vmax) {
        double t = (rssi - vmin) / (vmax - vmin);
        t = Math.max(0.0, Math.min(1.0, t));

        // [t, R, G, B]
        double[][] stops = {
                {0.000,  10,   8,  80},   // deep navy
                {0.140,  32,  52, 170},   // blue
                {0.280,  43, 160, 255},   // cyan-blue
                {0.420,  64, 232, 200},   // cyan-green
                {0.560,  80, 230,  80},   // green
                {0.700, 230, 255,  56},   // yellow
                {0.850, 255, 168,  20},   // orange
                {1.000, 230,  40,  20}    // red
        };

        int i = 0;
        while (i < stops.length - 2 && t > stops[i + 1][0]) {
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
        // d는 최소 0.1m 정도로 바닥 깔기 (너 nearFieldFloorM도 별도로 쓰고 있으니 이건 보호용)
        double d = Math.max(dMeters, 0.1);

        double fMHz = fGhz * 1000.0;

        // 1m 기준 FSPL (d0 = 1m)
        // FSPL(1m) = 32.44 + 20log10(fMHz) - 60
        double pl0 = 32.44 + 20 * Math.log10(fMHz) - 60.0;

        // 로그-거리 경로손실 모델: PL(d) = PL0 + 10 n log10(d / d0), d0 = 1m
        return pl0 + 10.0 * n * Math.log10(d / 1.0);
    }

    // ===== 반사/회절 후보 공통 상수 & 헬퍼 =====

    /** 반사 후보 벽 최대 개수 */
    public static final int    REFLECTION_WALL_MAX    = 12;
    /** 반사 후보로 포함할 최대 거리(m): AP 또는 RX까지의 최소 거리 기준 */
    public static final double REFLECTION_RADIUS_M    = 15.0;
    /** 반사 경로 길이가 LOS의 이 배를 초과하면 제외 */
    public static final double REFLECTION_LOS_RATIO   = 2.5;
    /** 회절 후보 코너 최대 개수 */
    public static final int    DIFFRACTION_CORNER_MAX = 16;
    /** 회절 후보로 포함할 최대 거리(m) */
    public static final double DIFFRACTION_RADIUS_M   = 12.0;
    /** 회절 경로 길이가 LOS의 이 배를 초과하면 제외 */
    public static final double DIFFRACTION_LOS_RATIO  = 2.8;
    /** 2차 경로(반사/회절) RSSI가 LOS보다 이 값(dB) 이상 약하면 제외 */
    public static final double SECONDARY_CUTOFF_DB    = 10.0;

    /** 반사 후보 벽 (거리순 정렬된 결과) */
    public static final class WallCand {
        public final Wall wall;
        public final double score; // 작을수록 더 가까움
        public WallCand(Wall wall, double score) { this.wall = wall; this.score = score; }
    }

    /** 회절 후보 코너 (거리순 정렬된 결과) */
    public static final class CornerCand {
        public final Wall wall;     // 코너가 속한 벽
        public final Point2D corner;
        public final double score;  // 작을수록 더 가까움
        public CornerCand(Wall wall, Point2D corner, double score) {
            this.wall = wall; this.corner = corner; this.score = score;
        }
    }

    /**
     * AP와 RX 위치를 기준으로 반사 후보 벽 목록을 반환한다.
     * - AP 또는 RX까지의 최소 거리가 REFLECTION_RADIUS_M 이내인 벽만 포함
     * - 거리(score) 오름차순 정렬, 최대 REFLECTION_WALL_MAX개
     */
    public static List<WallCand> reflectionCandidates(Point2D apPt,
                                                      Point2D rxPt,
                                                      List<Wall> walls,
                                                      double scaleMPerPx) {
        List<WallCand> cands = new ArrayList<>();
        for (Wall w : walls) {
            if (w == null) continue;
            Point2D w1 = new Point2D(w.x1, w.y1);
            Point2D w2 = new Point2D(w.x2, w.y2);
            Point2D cpAp = closestPointOnSegment(apPt, w1, w2);
            Point2D cpRx = closestPointOnSegment(rxPt, w1, w2);
            double dApM = apPt.distance(cpAp) * scaleMPerPx;
            double dRxM = rxPt.distance(cpRx) * scaleMPerPx;
            double minM = Math.min(dApM, dRxM);
            if (minM <= REFLECTION_RADIUS_M) {
                cands.add(new WallCand(w, minM));
            }
        }
        cands.sort(Comparator.comparingDouble(c -> c.score));
        if (cands.size() > REFLECTION_WALL_MAX) {
            cands = cands.subList(0, REFLECTION_WALL_MAX);
        }
        return cands;
    }

    /**
     * AP와 RX 위치를 기준으로 회절 후보 코너 목록을 반환한다.
     * - 각 벽의 두 끝점을 후보로 추가, DIFFRACTION_RADIUS_M 이내만 포함
     * - 중복 좌표 제거, 3개 이상 벽을 양쪽 모두 관통하는 후보 제외
     * - 거리(score) 오름차순 정렬, 최대 DIFFRACTION_CORNER_MAX개
     */
    public static List<CornerCand> diffractionCandidates(Point2D apPt,
                                                         Point2D rxPt,
                                                         List<Wall> walls,
                                                         double scaleMPerPx) {
        // 1단계: 거리 필터 + 정렬
        List<CornerCand> ranked = new ArrayList<>();
        for (Wall w : walls) {
            if (w == null) continue;
            Point2D c1 = new Point2D(w.x1, w.y1);
            Point2D c2 = new Point2D(w.x2, w.y2);
            double s1 = Math.min(apPt.distance(c1) * scaleMPerPx, rxPt.distance(c1) * scaleMPerPx);
            double s2 = Math.min(apPt.distance(c2) * scaleMPerPx, rxPt.distance(c2) * scaleMPerPx);
            if (s1 <= DIFFRACTION_RADIUS_M) ranked.add(new CornerCand(w, c1, s1));
            if (s2 <= DIFFRACTION_RADIUS_M) ranked.add(new CornerCand(w, c2, s2));
        }
        ranked.sort(Comparator.comparingDouble(c -> c.score));

        // 2단계: 중복 제거 + 과도 차단 필터
        Set<Long> seen = new HashSet<>();
        List<CornerCand> result = new ArrayList<>();
        for (CornerCand cc : ranked) {
            int qx = (int) Math.round(cc.corner.getX());
            int qy = (int) Math.round(cc.corner.getY());
            long key = (((long) qx) << 32) ^ (qy & 0xffffffffL);
            if (!seen.add(key)) continue;

            int cross1 = wallCrossCount(apPt.getX(), apPt.getY(),
                    cc.corner.getX(), cc.corner.getY(), walls, cc.wall);
            int cross2 = wallCrossCount(cc.corner.getX(), cc.corner.getY(),
                    rxPt.getX(), rxPt.getY(), walls, cc.wall);
            if (cross1 >= 3 && cross2 >= 3) continue;

            result.add(cc);
            if (result.size() >= DIFFRACTION_CORNER_MAX) break;
        }
        return result;
    }

    // ─── 후보 캐싱 최적화용 헬퍼 ──────────────────────────────────────────────

    /**
     * 단일 기준점 기준으로 REFLECTION_RADIUS_M 이내 반사 후보 벽 목록 반환 (거리 오름차순).
     * AP-local 또는 RX-local 각각 한 번씩 계산한 뒤 mergeWallCands로 병합하면
     * reflectionCandidates(apPt, rxPt, ...) 와 동일한 결과를 얻는다.
     */
    public static List<WallCand> wallsNearPoint(Point2D pt, List<Wall> walls, double scaleMPerPx) {
        List<WallCand> cands = new ArrayList<>();
        for (Wall w : walls) {
            if (w == null) continue;
            Point2D w1 = new Point2D(w.x1, w.y1);
            Point2D w2 = new Point2D(w.x2, w.y2);
            Point2D cp = closestPointOnSegment(pt, w1, w2);
            double dM = pt.distance(cp) * scaleMPerPx;
            if (dM <= REFLECTION_RADIUS_M) cands.add(new WallCand(w, dM));
        }
        cands.sort(Comparator.comparingDouble(c -> c.score));
        return cands;
    }

    /**
     * AP-local / RX-local 반사 후보 목록을 병합한다.
     * 같은 Wall 인스턴스가 양쪽에 있으면 min(score)를 사용하고,
     * 결과를 score 오름차순 정렬 후 REFLECTION_WALL_MAX개로 제한한다.
     */
    public static List<WallCand> mergeWallCands(List<WallCand> apCands, List<WallCand> rxCands) {
        IdentityHashMap<Wall, Double> scoreMap = new IdentityHashMap<>();
        for (WallCand c : apCands) scoreMap.put(c.wall, c.score);
        for (WallCand c : rxCands) scoreMap.merge(c.wall, c.score, Math::min);
        List<WallCand> merged = new ArrayList<>(scoreMap.size());
        scoreMap.forEach((w, s) -> merged.add(new WallCand(w, s)));
        merged.sort(Comparator.comparingDouble(c -> c.score));
        return merged.size() > REFLECTION_WALL_MAX ? new ArrayList<>(merged.subList(0, REFLECTION_WALL_MAX)) : merged;
    }

    /**
     * 단일 기준점 기준으로 DIFFRACTION_RADIUS_M 이내 회절 코너 목록 반환 (stage-1 전용).
     * dedup/cross-filter 없음 — mergeCornerCands가 stage-2를 처리한다.
     */
    public static List<CornerCand> cornersNearPoint(Point2D pt, List<Wall> walls, double scaleMPerPx) {
        List<CornerCand> cands = new ArrayList<>();
        for (Wall w : walls) {
            if (w == null) continue;
            Point2D c1 = new Point2D(w.x1, w.y1);
            Point2D c2 = new Point2D(w.x2, w.y2);
            double s1 = pt.distance(c1) * scaleMPerPx;
            double s2 = pt.distance(c2) * scaleMPerPx;
            if (s1 <= DIFFRACTION_RADIUS_M) cands.add(new CornerCand(w, c1, s1));
            if (s2 <= DIFFRACTION_RADIUS_M) cands.add(new CornerCand(w, c2, s2));
        }
        return cands;
    }

    /**
     * AP-local / RX-local 회절 코너 목록을 병합하고 stage-2(dedup, cross-filter)를 적용한다.
     * diffractionCandidates(apPt, rxPt, ...) 와 동일한 결과를 반환하지만,
     * 벽 스캔을 AP-local / RX-local로 분리 계산한 뒤 이 메서드로 합칠 수 있다.
     */
    public static List<CornerCand> mergeCornerCands(List<CornerCand> apCands,
                                                     List<CornerCand> rxCands,
                                                     List<Wall> walls,
                                                     Point2D apPt,
                                                     Point2D rxPt) {
        // 좌표 기반 중복 제거 + min score
        LinkedHashMap<Long, CornerCand> best = new LinkedHashMap<>();
        for (CornerCand cc : apCands) {
            long key = cornerKey(cc.corner);
            best.merge(key, cc, (a, b) -> a.score <= b.score ? a : b);
        }
        for (CornerCand cc : rxCands) {
            long key = cornerKey(cc.corner);
            best.merge(key, cc, (a, b) -> a.score <= b.score ? a : b);
        }

        List<CornerCand> ranked = new ArrayList<>(best.values());
        ranked.sort(Comparator.comparingDouble(c -> c.score));

        // cross-filter (과도한 벽 차단 코너 제외)
        List<CornerCand> result = new ArrayList<>();
        for (CornerCand cc : ranked) {
            int cross1 = wallCrossCount(apPt.getX(), apPt.getY(),
                    cc.corner.getX(), cc.corner.getY(), walls, cc.wall);
            int cross2 = wallCrossCount(cc.corner.getX(), cc.corner.getY(),
                    rxPt.getX(), rxPt.getY(), walls, cc.wall);
            if (cross1 >= 3 && cross2 >= 3) continue;
            result.add(cc);
            if (result.size() >= DIFFRACTION_CORNER_MAX) break;
        }
        return result;
    }

    private static long cornerKey(Point2D corner) {
        int qx = (int) Math.round(corner.getX());
        int qy = (int) Math.round(corner.getY());
        return (((long) qx) << 32) ^ (qy & 0xffffffffL);
    }
}
