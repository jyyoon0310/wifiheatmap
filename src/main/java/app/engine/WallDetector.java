package app.engine;

import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.Vec4iVector;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.*;

/**
 * 평면도 이미지에서 벽(선분)을 자동 인식합니다.
 * OpenCV HoughLinesP 기반 + 근접 선분 병합.
 */
public class WallDetector {

    // ── 파라미터 ─────────────────────────────────────────────────────────────

    public record Params(
            double cannyLow,       // Canny 하한 임계값 (기본: 40)
            double cannyHigh,      // Canny 상한 임계값 (기본: 120)
            int    houghThreshold, // HoughLinesP 누적 임계값 (기본: 30)
            int    minLengthPx,    // 최소 선분 길이 px (기본: 30)
            int    maxGapPx,       // 선분 최대 갭 px (기본: 8)
            int    mergeDistPx,    // 병합 판단 수직 거리 px (기본: 10)
            double angleTolDeg     // 각도 버킷 크기 deg (기본: 5)
    ) {
        public static Params defaults() {
            return new Params(40, 120, 30, 30, 8, 10, 5.0);
        }
    }

    // ── 결과 타입 ─────────────────────────────────────────────────────────────

    public record Segment(int x1, int y1, int x2, int y2) {
        public double lengthPx() {
            int dx = x2 - x1, dy = y2 - y1;
            return Math.sqrt((double)dx*dx + (double)dy*dy);
        }
    }

    // ── 메인 감지 ─────────────────────────────────────────────────────────────

    public static List<Segment> detect(BufferedImage img, Params p) {
        // 1. BufferedImage → BGR Mat
        BufferedImage bgr = ensureType(img, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        Mat src = new Mat(bgr.getHeight(), bgr.getWidth(), CV_8UC3);
        src.data().put(pixels);

        // 2. 그레이스케일
        Mat gray = new Mat();
        cvtColor(src, gray, COLOR_BGR2GRAY);

        // 3. 가우시안 블러 (노이즈 감소)
        Mat blurred = new Mat();
        GaussianBlur(gray, blurred, new Size(5, 5), 0);

        // 4. Canny 엣지
        Mat edges = new Mat();
        Canny(blurred, edges, p.cannyLow(), p.cannyHigh());

        // 5. 모폴로지 팽창 — 끊긴 엣지 연결
        Mat kernel = getStructuringElement(MORPH_RECT, new Size(3, 1));
        Mat dilated = new Mat();
        dilate(edges, dilated, kernel);

        // 6. HoughLinesP
        Vec4iVector lines = new Vec4iVector();
        HoughLinesP(dilated, lines, 1.0, Math.PI / 180.0,
                p.houghThreshold(), p.minLengthPx(), p.maxGapPx());

        // 7. Vec4iVector → Segment 목록
        List<Segment> raw = new ArrayList<>();
        for (long i = 0; i < lines.size(); i++) {
            Scalar4i s = lines.get(i);
            raw.add(new Segment(s.get(0), s.get(1), s.get(2), s.get(3)));
        }

        // 8. 근접 선분 병합
        return mergeSegments(raw, p.angleTolDeg(), p.mergeDistPx());
    }

    // ── 선분 병합 ─────────────────────────────────────────────────────────────

    /**
     * 거의 평행하고 가까운 선분들을 하나로 병합합니다.
     *
     * 알고리즘:
     * 1. 각도 버킷으로 그룹화 (예: 0-5°, 5-10°, …)
     * 2. 같은 버킷 내에서 중심점의 수직 오프셋이 mergeDistPx 이내인 것 병합
     * 3. 병합된 그룹은 기준 방향으로 투영하여 최소~최대 구간으로 단일 선분화
     */
    private static List<Segment> mergeSegments(List<Segment> segs, double bucketDeg, int distPx) {
        if (segs.isEmpty()) return segs;

        // 각도 버킷으로 그룹화
        Map<Integer, List<Segment>> byAngle = new LinkedHashMap<>();
        for (Segment s : segs) {
            double a = normalizedAngleDeg(s);
            int bucket = (int)(a / bucketDeg);
            byAngle.computeIfAbsent(bucket, k -> new ArrayList<>()).add(s);
        }

        List<Segment> result = new ArrayList<>();
        for (List<Segment> group : byAngle.values()) {
            // 버킷 내 평균 각도로 기준 방향 결정
            double refAngleRad = Math.toRadians(
                    group.stream().mapToDouble(WallDetector::normalizedAngleDeg).average().orElse(0));
            double dx = Math.cos(refAngleRad);
            double dy = Math.sin(refAngleRad);
            // 수직 방향 (법선)
            double nx = -dy, ny = dx;

            // 수직 오프셋으로 정렬
            group.sort(Comparator.comparingDouble(s -> perpOffset(s, nx, ny)));

            // 인접 선분 그리디 병합
            List<Segment> cluster = new ArrayList<>();
            cluster.add(group.get(0));
            double lastPerp = perpOffset(group.get(0), nx, ny);

            for (int i = 1; i < group.size(); i++) {
                Segment s = group.get(i);
                double perf = perpOffset(s, nx, ny);
                if (Math.abs(perf - lastPerp) <= distPx) {
                    cluster.add(s);
                    lastPerp = perf; // running update (last in cluster)
                } else {
                    result.add(collapseCluster(cluster, refAngleRad));
                    cluster = new ArrayList<>();
                    cluster.add(s);
                    lastPerp = perf;
                }
            }
            if (!cluster.isEmpty()) result.add(collapseCluster(cluster, refAngleRad));
        }
        return result;
    }

    /** 각도 [0, 180) 정규화 */
    private static double normalizedAngleDeg(Segment s) {
        double a = Math.toDegrees(Math.atan2(s.y2() - s.y1(), s.x2() - s.x1()));
        while (a < 0)   a += 180;
        while (a >= 180) a -= 180;
        return a;
    }

    /** 선분 중점의 법선 방향 오프셋 */
    private static double perpOffset(Segment s, double nx, double ny) {
        double mx = (s.x1() + s.x2()) * 0.5;
        double my = (s.y1() + s.y2()) * 0.5;
        return mx * nx + my * ny;
    }

    /** 클러스터를 기준 방향으로 투영해 단일 선분으로 병합 */
    private static Segment collapseCluster(List<Segment> cluster, double refRad) {
        if (cluster.size() == 1) return cluster.get(0);
        double dx = Math.cos(refRad), dy = Math.sin(refRad);
        // 중심점 평균
        double cx = cluster.stream().mapToDouble(s -> (s.x1() + s.x2()) * 0.5).average().orElse(0);
        double cy = cluster.stream().mapToDouble(s -> (s.y1() + s.y2()) * 0.5).average().orElse(0);
        // 기준 방향으로 투영 → 최소/최대 t 찾기
        double minT = Double.MAX_VALUE, maxT = -Double.MAX_VALUE;
        for (Segment s : cluster) {
            double t1 = (s.x1() - cx) * dx + (s.y1() - cy) * dy;
            double t2 = (s.x2() - cx) * dx + (s.y2() - cy) * dy;
            minT = Math.min(minT, Math.min(t1, t2));
            maxT = Math.max(maxT, Math.max(t1, t2));
        }
        return new Segment(
                (int)Math.round(cx + minT * dx), (int)Math.round(cy + minT * dy),
                (int)Math.round(cx + maxT * dx), (int)Math.round(cy + maxT * dy));
    }

    private static BufferedImage ensureType(BufferedImage src, int type) {
        if (src.getType() == type) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), type);
        java.awt.Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }
}
