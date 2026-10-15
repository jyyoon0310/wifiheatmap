package app.io;

import app.model.AP;
import app.model.Band;
import app.model.RadioConfig;
import app.model.Wall;
import app.model.WallMaterial;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 작업 파일(.wifisettings) 읽기·쓰기.
 *
 * <p>형식: zip 컨테이너 안에 {@code settings.properties}(축척·공유기·벽, UTF-8) 와
 * {@code floorplan.png}(평면도, 있을 때만). 예전 버전이 쓰던 zip 이 아닌 plain properties
 * 파일도 읽는다.</p>
 *
 * <p>화면과 무관한 순수 입출력이라 단위 테스트로 왕복(저장→불러오기)을 검증한다.
 * 예전엔 MainController 안에 화면 갱신 코드와 섞여 있었다.</p>
 */
public final class ProjectFile {
    private ProjectFile() {}

    public static final String EXTENSION = ".wifisettings";

    private static final String SETTINGS_ENTRY = "settings.properties";
    private static final String FLOORPLAN_ENTRY = "floorplan.png";

    /** 파일이 이 앱의 작업 파일 형식이 아닐 때. 메시지는 사용자에게 그대로 보여 줄 수 있다. */
    public static final class FormatException extends IOException {
        public FormatException(String message) { super(message); }
    }

    /**
     * 작업 파일 한 개의 내용.
     *
     * @param floorplanPath   평면도 원본 경로 (없으면 null). 파일에 평면도가 들어 있으면 그쪽이 우선
     * @param floorplan       파일에 들어 있는 평면도 (없으면 null)
     * @param scaleMPerPx     축척 [m/px] (없으면 NaN)
     * @param calibRealMeters 축척 맞추기에 입력한 실제 길이 [m] (없으면 NaN)
     * @param clientHeightM   단말 높이 [m] (없으면 NaN)
     * @param pathLossN       Legacy 경로손실 지수 (없으면 NaN)
     */
    public record Snapshot(String floorplanPath, BufferedImage floorplan,
                           double scaleMPerPx, double calibRealMeters,
                           double clientHeightM, double pathLossN,
                           List<AP> aps, List<Wall> walls) {
        public Snapshot {
            // List.copyOf 는 null 원소를 거부하므로 복사 후 읽기 전용으로 감싼다 (쓰기 쪽은 null 을 건너뛴다)
            aps = java.util.Collections.unmodifiableList(new ArrayList<>(aps));
            walls = java.util.Collections.unmodifiableList(new ArrayList<>(walls));
        }
    }

    // ── 쓰기 ──────────────────────────────────────────────────────────────────

    public static void write(File out, Snapshot s) throws IOException {
        Properties p = toProperties(s);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zos.putNextEntry(new ZipEntry(SETTINGS_ENTRY));
            ByteArrayOutputStream propsBuf = new ByteArrayOutputStream();
            try (OutputStreamWriter writer = new OutputStreamWriter(propsBuf, StandardCharsets.UTF_8)) {
                p.store(writer, "Wi-Fi Heatmap settings snapshot");
            }
            zos.write(propsBuf.toByteArray());
            zos.closeEntry();

            if (s.floorplan() != null) {
                zos.putNextEntry(new ZipEntry(FLOORPLAN_ENTRY));
                ImageIO.write(s.floorplan(), "png", zos);
                zos.closeEntry();
            }
        }
    }

    static Properties toProperties(Snapshot s) {
        Properties p = new Properties();
        p.setProperty("version", "1");
        if (s.floorplanPath() != null) p.setProperty("floorplan.path", s.floorplanPath());
        if (Double.isFinite(s.scaleMPerPx()) && s.scaleMPerPx() > 0.0) {
            p.setProperty("scale.m_per_px", Double.toString(s.scaleMPerPx()));
        }
        p.setProperty("state.calib_real_m", Double.toString(s.calibRealMeters()));
        p.setProperty("env.client_height_m", Double.toString(s.clientHeightM()));
        p.setProperty("env.path_loss_n", Double.toString(s.pathLossN()));

        p.setProperty("ap.count", Integer.toString(s.aps().size()));
        for (int i = 0; i < s.aps().size(); i++) {
            AP ap = s.aps().get(i);
            if (ap == null) continue;
            String key = "ap." + i + ".";
            p.setProperty(key + "name", nullToEmpty(ap.name));
            p.setProperty(key + "x", Double.toString(ap.x));
            p.setProperty(key + "y", Double.toString(ap.y));
            p.setProperty(key + "height_m", Double.toString(ap.heightM));
            p.setProperty(key + "enabled", Boolean.toString(ap.enabled));
            for (Band band : Band.values()) {
                RadioConfig rc = ap.radios.get(band);
                if (rc == null) continue;
                String rk = key + "radio." + band.name() + ".";
                p.setProperty(rk + "enabled", Boolean.toString(rc.enabled));
                p.setProperty(rk + "ssid", nullToEmpty(rc.ssid));
                p.setProperty(rk + "tx_dbm", Double.toString(rc.txPowerDbm));
                p.setProperty(rk + "gain_dbi", Double.toString(rc.antennaGain));
                p.setProperty(rk + "channel", Integer.toString(rc.channel));
                p.setProperty(rk + "bandwidth_mhz", Integer.toString(rc.channelWidth));
            }
        }

        p.setProperty("wall.count", Integer.toString(s.walls().size()));
        for (int i = 0; i < s.walls().size(); i++) {
            Wall w = s.walls().get(i);
            if (w == null) continue;
            String key = "wall." + i + ".";
            p.setProperty(key + "x1", Double.toString(w.x1));
            p.setProperty(key + "y1", Double.toString(w.y1));
            p.setProperty(key + "x2", Double.toString(w.x2));
            p.setProperty(key + "y2", Double.toString(w.y2));
            WallMaterial material = (w.getMaterial() == null) ? WallMaterial.CUSTOM : w.getMaterial();
            p.setProperty(key + "material", material.name());
            p.setProperty(key + "att_24_db", Double.toString(w.attenuationDb24));
            p.setProperty(key + "att_5_db", Double.toString(w.attenuationDb5));
        }
        return p;
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────────

    public static Snapshot read(File file) throws IOException {
        Properties p = new Properties();
        BufferedImage embedded = null;

        if (isZipFile(file)) {
            try (ZipFile zf = new ZipFile(file)) {
                ZipEntry propsEntry = zf.getEntry(SETTINGS_ENTRY);
                if (propsEntry == null) {
                    throw new FormatException(
                            "작업 파일을 열 수 없습니다. 이 앱에서 저장한 .wifisettings 파일인지 확인해 주세요.");
                }
                try (InputStreamReader r = new InputStreamReader(
                        zf.getInputStream(propsEntry), StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                ZipEntry imgEntry = zf.getEntry(FLOORPLAN_ENTRY);
                if (imgEntry != null) {
                    try (InputStream in = zf.getInputStream(imgEntry)) {
                        embedded = ImageIO.read(in);
                    }
                }
            }
        } else {
            // 구버전 — plain properties
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8)) {
                p.load(reader);
            }
        }
        return fromProperties(p, embedded);
    }

    static Snapshot fromProperties(Properties p, BufferedImage embedded) {
        double clientHeight = parseDouble(p.getProperty("env.client_height_m"), Double.NaN);
        double pathLossN = parseDouble(p.getProperty("env.path_loss_n"), Double.NaN);

        int apCount = Math.max(0, parseInt(p.getProperty("ap.count"), 0));
        List<AP> aps = new ArrayList<>(apCount);
        for (int i = 0; i < apCount; i++) {
            String key = "ap." + i + ".";
            AP ap = new AP();
            ap.name = p.getProperty(key + "name", ap.name);
            ap.x = parseDouble(p.getProperty(key + "x"), ap.x);
            ap.y = parseDouble(p.getProperty(key + "y"), ap.y);
            ap.heightM = Math.max(0.1, parseDouble(p.getProperty(key + "height_m"), ap.heightM));
            ap.enabled = parseBoolean(p.getProperty(key + "enabled"), ap.enabled);
            for (Band band : Band.values()) {
                RadioConfig rc = ap.radios.get(band);
                if (rc == null) continue;
                String rk = key + "radio." + band.name() + ".";
                rc.enabled = parseBoolean(p.getProperty(rk + "enabled"), rc.enabled);
                rc.ssid = p.getProperty(rk + "ssid", rc.ssid);
                // 저장된 출력을 그대로 읽는다. (예전엔 무조건 17dBm 으로 되돌려, 프리셋(20~23dBm)으로
                // 저장한 작업을 다시 열면 신호 지도가 약하게 나왔다.)
                rc.txPowerDbm = parseDouble(p.getProperty(rk + "tx_dbm"), RadioConfig.FIXED_TX_POWER_DBM);
                rc.antennaGain = parseDouble(p.getProperty(rk + "gain_dbi"), rc.antennaGain);
                rc.channel = parseInt(p.getProperty(rk + "channel"), rc.channel);
                rc.channelWidth = parseInt(p.getProperty(rk + "bandwidth_mhz"), rc.channelWidth);
            }
            aps.add(ap);
        }

        int wallCount = Math.max(0, parseInt(p.getProperty("wall.count"), 0));
        List<Wall> walls = new ArrayList<>(wallCount);
        for (int i = 0; i < wallCount; i++) {
            String key = "wall." + i + ".";
            Wall w = new Wall();
            w.x1 = parseDouble(p.getProperty(key + "x1"), w.x1);
            w.y1 = parseDouble(p.getProperty(key + "y1"), w.y1);
            w.x2 = parseDouble(p.getProperty(key + "x2"), w.x2);
            w.y2 = parseDouble(p.getProperty(key + "y2"), w.y2);
            w.setMaterial(parseWallMaterial(p.getProperty(key + "material")));
            w.attenuationDb24 = parseDouble(p.getProperty(key + "att_24_db"), w.attenuationDb24);
            w.attenuationDb5 = parseDouble(p.getProperty(key + "att_5_db"), w.attenuationDb5);
            w.attenuationDb = w.attenuationDb24;
            walls.add(w);
        }

        return new Snapshot(
                trimToNull(p.getProperty("floorplan.path")),
                embedded,
                parseDouble(p.getProperty("scale.m_per_px"), Double.NaN),
                parseDouble(p.getProperty("state.calib_real_m"), Double.NaN),
                Double.isNaN(clientHeight) ? Double.NaN : Math.max(0.1, clientHeight),
                Double.isNaN(pathLossN) ? Double.NaN : Math.max(1.0, pathLossN),
                aps, walls);
    }

    /** 파일 시그니처로 ZIP 여부 판별 (PK\003\004) */
    private static boolean isZipFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] sig = new byte[4];
            int n = in.read(sig);
            return n == 4 && sig[0] == 'P' && sig[1] == 'K' && sig[2] == 0x03 && sig[3] == 0x04;
        } catch (IOException ex) {
            return false;
        }
    }

    // ── 파싱 도우미 (값이 없거나 깨졌으면 fallback) ────────────────────────────

    private static String nullToEmpty(String value) {
        return (value == null) ? "" : value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null) return fallback;
        String v = raw.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v)) return true;
        if ("false".equals(v) || "0".equals(v) || "no".equals(v)) return false;
        return fallback;
    }

    private static WallMaterial parseWallMaterial(String raw) {
        if (raw == null || raw.isBlank()) return WallMaterial.CONCRETE_WALL;
        try {
            return WallMaterial.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return WallMaterial.CONCRETE_WALL;
        }
    }
}
