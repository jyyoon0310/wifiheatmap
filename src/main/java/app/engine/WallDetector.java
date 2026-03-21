package app.engine;

import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.Vec4iVector;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY_INV;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.*;

/**
 * 평면도 이미지에서 굵은 검은 벽선만 자동 인식합니다.
 *
 * 파이프라인:
 *  1. BGR → HSV 변환
 *  2. "검은 픽셀" 이진 마스크  (V < blackMaxValue AND S < blackMaxSaturation)
 *     - 유색 방 채움(주황·베이지·하늘색) 은 채도가 높아 자동 제거
 *     - 회색/흰색 배경도 V 값이 높아 제거
 *  3. Morphological Opening  (erode → dilate)
 *     - kernel = thicknessPx × thicknessPx
 *     - 두께 < thicknessPx 인 얇은 선(가구·문호·계단) 제거
 *     - 두꺼운 벽선은 그대로 유지
 *  4. 가우시안 블러 (Canny 노이즈 감소)
 *  5. Canny 엣지
 *  6. HoughLinesP
 *  7. 근접 선분 병합
 */
public class WallDetector {

    // ── 파라미터 ─────────────────────────────────────────────────────────────

    public record Params(
            double cannyLow,          // Canny 하한 (기본: 20)
            double cannyHigh,         // Canny 상한 (기본: 80)
            int    houghThreshold,    // HoughLinesP 누적 임계값 (기본: 35)
            int    minLengthPx,       // 최소 선분 길이 px (기본: 40)
            int    maxGapPx,          // 선분 최대 갭 px (기본: 10)
            int    mergeDistPx,       // 병합 수직 거리 px (기본: 12)
            double angleTolDeg,       // 각도 버킷 크기 deg (기본: 5.0)
            int    blackMaxValue,     // HSV V 임계값 — 이 이하가 "검정" (기본: 80)
            int    blackMaxSaturation,// HSV S 임계값 — 이 이하가 "무채색" (기본: 60)
            int    thicknessPx        // 모폴로지 커널 크기 — 이 이하 굵기 제거 (기본: 3)
    ) {
        /** 컬러 평면도(유색 방 채움 포함) 기본값 */
        public static Params defaults() {
            return new Params(
                    20, 80,   // canny
                    35,       // houghThreshold
                    40,       // minLengthPx
                    10,       // maxGapPx
                    12,       // mergeDistPx
                    5.0,      // angleTolDeg
                    80,       // blackMaxValue  — HSV V < 80
                    60,       // blackMaxSaturation — HSV S < 60
                    3         // thicknessPx  — erode 3×3 once
            );
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
        // ── 1. BufferedImage → BGR Mat ──────────────────────────────────────
        BufferedImage bgr = ensureType(img, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        Mat src = new Mat(bgr.getHeight(), bgr.getWidth(), CV_8UC3);
        src.data().put(pixels);

        // ── 2. BGR → HSV 변환 ───────────────────────────────────────────────
        Mat hsv = new Mat();
        cvtColor(src, hsv, COLOR_BGR2HSV);

        // ── 3. "검은 픽셀" 마스크 ─────────────────────────────────────────
        //   HSV: H(0-179), S(0-255), V(0-255)
        //   벽 검정선: S ≈ 0-60 (무채색), V ≈ 0-80 (어두움)
        //   유색 채움: S > 60 (채도 높음)  → 마스크에서 제외
        //   흰/회색 배경: V > 80 (밝음)    → 마스크에서 제외
        //
        //   JavaCV inRange() 는 Scalar 오버로드가 없어서
        //   split → threshold → bitwise_and 로 대체
        MatVector hsvChannels = new MatVector(3);
        split(hsv, hsvChannels);
        Mat mV = new Mat();   // V < blackMaxValue → 255
        Mat mS = new Mat();   // S < blackMaxSaturation → 255
        threshold(hsvChannels.get(2), mV, p.blackMaxValue(),      255, THRESH_BINARY_INV);
        threshold(hsvChannels.get(1), mS, p.blackMaxSaturation(), 255, THRESH_BINARY_INV);
        Mat blackMask = new Mat();
        bitwise_and(mV, mS, blackMask);

        // ── 4. Morphological Opening (굵기 필터) ───────────────────────────
        //   Opening = Erosion → Dilation
        //   erosion 커널이 thicknessPx×thicknessPx 이면,
        //   두께 < thicknessPx 인 선분은 완전히 제거됨
        int k = Math.max(3, p.thicknessPx() | 1); // 홀수 커널만 허용
        Mat kernel = getStructuringElement(MORPH_RECT, new Size(k, k));
        Mat opened = new Mat();
        morphologyEx(blackMask, opened, MORPH_OPEN, kernel);

        // 끊긴 구간 연결을 위한 소폭 팽창 (x방향 + y방향)
        Mat connKernel = getStructuringElement(MORPH_RECT, new Size(3, 3));
        Mat connected = new Mat();
        dilate(opened, connected, connKernel);

        // ── 5. 가우시안 블러 ────────────────────────────────────────────────
        Mat blurred = new Mat();
        GaussianBlur(connected, blurred, new Size(3, 3), 0);

        // ── 6. Canny 엣지 ────────────────────────────────────────────────
        Mat edges = new Mat();
        Canny(blurred, edges, p.cannyLow(), p.cannyHigh());

        // ── 7. HoughLinesP ──────────────────────────────────────────────────
        Vec4iVector lines = new Vec4iVector();
        HoughLinesP(edges, lines, 1.0, Math.PI / 180.0,
                p.houghThreshold(), p.minLengthPx(), p.maxGapPx());

        // ── 8. Vec4iVector → Segment 목록 ───────────────────────────────────
        List<Segment> raw = new ArrayList<>();
        for (long i = 0; i < lines.size(); i++) {
            Scalar4i s = lines.get(i);
            raw.add(new Segment(s.get(0), s.get(1), s.get(2), s.get(3)));
        }

        // ── 9. 근접 선분 병합 ────────────────────────────────────────────────
        return mergeSegments(raw, p.angleTolDeg(), p.mergeDistPx());
    }

    // ── 선분 병합 ─────────────────────────────────────────────────────────────

    private static List<Segment> mergeSegments(List<Segment> segs, double bucketDeg, int distPx) {
        if (segs.isEmpty()) return segs;

        Map<Integer, List<Segment>> byAngle = new LinkedHashMap<>();
        for (Segment s : segs) {
            double a = normalizedAngleDeg(s);
            int bucket = (int)(a / bucketDeg);
            byAngle.computeIfAbsent(bucket, k -> new ArrayList<>()).add(s);
        }

        List<Segment> result = new ArrayList<>();
        for (List<Segment> group : byAngle.values()) {
            double refAngleRad = Math.toRadians(
                    group.stream().mapToDouble(WallDetector::normalizedAngleDeg).average().orElse(0));
            double dx = Math.cos(refAngleRad);
            double dy = Math.sin(refAngleRad);
            double nx = -dy, ny = dx;

            group.sort(Comparator.comparingDouble(s -> perpOffset(s, nx, ny)));

            List<Segment> cluster = new ArrayList<>();
            cluster.add(group.get(0));
            double lastPerp = perpOffset(group.get(0), nx, ny);

            for (int i = 1; i < group.size(); i++) {
                Segment s = group.get(i);
                double perf = perpOffset(s, nx, ny);
                if (Math.abs(perf - lastPerp) <= distPx) {
                    cluster.add(s);
                    lastPerp = perf;
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

    private static double normalizedAngleDeg(Segment s) {
        double a = Math.toDegrees(Math.atan2(s.y2() - s.y1(), s.x2() - s.x1()));
        while (a <   0) a += 180;
        while (a >= 180) a -= 180;
        return a;
    }

    private static double perpOffset(Segment s, double nx, double ny) {
        double mx = (s.x1() + s.x2()) * 0.5;
        double my = (s.y1() + s.y2()) * 0.5;
        return mx * nx + my * ny;
    }

    private static Segment collapseCluster(List<Segment> cluster, double refRad) {
        if (cluster.size() == 1) return cluster.get(0);
        double dx = Math.cos(refRad), dy = Math.sin(refRad);
        double cx = cluster.stream().mapToDouble(s -> (s.x1() + s.x2()) * 0.5).average().orElse(0);
        double cy = cluster.stream().mapToDouble(s -> (s.y1() + s.y2()) * 0.5).average().orElse(0);
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
