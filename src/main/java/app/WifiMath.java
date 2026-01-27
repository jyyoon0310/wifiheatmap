package app;

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
        double sum = 0.0;
        Point2D a = new Point2D(ax, ay);
        Point2D b = new Point2D(bx, by);
        for (Wall w : walls) {
            if (w == null) continue;
            if (ignoreWall != null && w == ignoreWall) continue;
            if (segmentsIntersect(a, b,
                    new Point2D(w.x1, w.y1),
                    new Point2D(w.x2, w.y2))) {
                sum += w.attenuationDb(band);
            }
        }
        return sum;
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

        // AP를 벽의 연장 직선에 대해 반사시킨 가상 AP'
        Point2D apMirror = reflectPointOverLine(ap, w1, w2);

        // apMirror -> rx 직선과 벽 선분(w1-w2)의 교점이 반사점
        Point2D p = segmentIntersectionPoint(apMirror, rx, w1, w2);
        if (p == null) return null;

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

    // ===== 색상 매핑 및 픽셀 유틸 =====

    /**
     * RSSI(dBm) → 강(빨강) ~ 중(노랑) ~ 약(초록) 색상 매핑
     */
    public static Color rssiToColor(double rssi, double vmin, double vmax) {
        double t = (rssi - vmin) / (vmax - vmin);
        t = Math.max(0.0, Math.min(1.0, t));

        // [t, R, G, B]
        double[][] stops = {
                {0.00, 0,   160, 0},    // Green
                {0.50, 255, 235, 0},    // Yellow
                {1.00, 230, 40,  20}    // Red
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
}
