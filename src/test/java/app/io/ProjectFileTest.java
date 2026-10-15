package app.io;

import app.model.AP;
import app.model.ApPreset;
import app.model.Band;
import app.model.RadioConfig;
import app.model.Wall;
import app.model.WallMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 작업 파일 저장 → 불러오기 왕복 테스트. */
class ProjectFileTest {

    @TempDir Path tmp;

    @Test
    void roundTripKeepsEverything() throws Exception {
        AP a = new AP();
        a.name = "거실";
        a.resetSsidsFromName();
        a.x = 120.5; a.y = 80.25; a.heightM = 2.2;
        ApPreset.LGU_WIFI7.applyTo(a);              // 프리셋 출력(20~23dBm)이 그대로 돌아와야 한다
        a.radios.get(Band.GHZ_6).enabled = false;

        AP b = new AP();
        b.name = "방";
        b.resetSsidsFromName();
        b.x = 300; b.y = 200;
        b.enabled = false;
        RadioConfig b5 = b.radios.get(Band.GHZ_5);
        b5.channel = 149; b5.channelWidth = 160; b5.antennaGain = 3.5;

        Wall w1 = new Wall(0, 0, 400, 0, WallMaterial.CONCRETE_WALL);
        Wall w2 = new Wall(200, 0, 200, 300, WallMaterial.WINDOW);
        w2.setAttenuationDb(4.5, 7.25);             // 사용자가 직접 바꾼 감쇠값

        BufferedImage floor = new BufferedImage(7, 5, BufferedImage.TYPE_INT_ARGB);
        floor.setRGB(3, 2, 0xFF336699);

        ProjectFile.Snapshot in = new ProjectFile.Snapshot(
                "/somewhere/plan.png", floor, 0.0321, 4.5, 1.3, 3.2,
                List.of(a, b), List.of(w1, w2));
        File f = tmp.resolve("t" + ProjectFile.EXTENSION).toFile();
        ProjectFile.write(f, in);

        ProjectFile.Snapshot out = ProjectFile.read(f);

        assertEquals("/somewhere/plan.png", out.floorplanPath());
        assertNotNull(out.floorplan());
        assertEquals(7, out.floorplan().getWidth());
        assertEquals(0xFF336699, out.floorplan().getRGB(3, 2));
        assertEquals(0.0321, out.scaleMPerPx());
        assertEquals(4.5, out.calibRealMeters());
        assertEquals(1.3, out.clientHeightM());
        assertEquals(3.2, out.pathLossN());

        assertEquals(2, out.aps().size());
        assertApEquals(a, out.aps().get(0));
        assertApEquals(b, out.aps().get(1));

        assertEquals(2, out.walls().size());
        assertWallEquals(w1, out.walls().get(0));
        assertWallEquals(w2, out.walls().get(1));
    }

    @Test
    void readsOldPlainPropertiesFile() throws Exception {
        File f = tmp.resolve("old.wifisettings").toFile();
        Files.writeString(f.toPath(), String.join("\n",
                "version=1",
                "scale.m_per_px=0.05",
                "ap.count=1",
                "ap.0.name=예전",
                "ap.0.x=10", "ap.0.y=20",
                "wall.count=1",
                "wall.0.x1=0", "wall.0.y1=0", "wall.0.x2=10", "wall.0.y2=0",
                "wall.0.material=DRY_WALL"), StandardCharsets.UTF_8);

        ProjectFile.Snapshot s = ProjectFile.read(f);
        assertEquals(0.05, s.scaleMPerPx());
        assertNull(s.floorplan());
        assertTrue(Double.isNaN(s.calibRealMeters()), "없는 값은 NaN — 호출자가 현재 값을 유지한다");
        assertEquals("예전", s.aps().get(0).name);
        assertEquals(RadioConfig.FIXED_TX_POWER_DBM, s.aps().get(0).radios.get(Band.GHZ_24).txPowerDbm);
        assertEquals(WallMaterial.DRY_WALL, s.walls().get(0).getMaterial());
    }

    @Test
    void rejectsZipWithoutSettings() throws Exception {
        File f = tmp.resolve("bad.wifisettings").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f));
             OutputStreamWriter w = new OutputStreamWriter(zos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("other.txt"));
            w.write("x");
        }
        ProjectFile.FormatException ex =
                assertThrows(ProjectFile.FormatException.class, () -> ProjectFile.read(f));
        assertTrue(ex.getMessage().contains(".wifisettings"));
    }

    private static void assertApEquals(AP e, AP g) {
        assertEquals(e.name, g.name);
        assertEquals(e.x, g.x);
        assertEquals(e.y, g.y);
        assertEquals(e.heightM, g.heightM);
        assertEquals(e.enabled, g.enabled);
        for (Band band : Band.values()) {
            RadioConfig re = e.radios.get(band), rg = g.radios.get(band);
            String at = e.name + " " + band;
            assertEquals(re.enabled, rg.enabled, at);
            assertEquals(re.ssid, rg.ssid, at);
            assertEquals(re.txPowerDbm, rg.txPowerDbm, at);
            assertEquals(re.antennaGain, rg.antennaGain, at);
            assertEquals(re.channel, rg.channel, at);
            assertEquals(re.channelWidth, rg.channelWidth, at);
        }
    }

    private static void assertWallEquals(Wall e, Wall g) {
        assertEquals(e.x1, g.x1);
        assertEquals(e.y1, g.y1);
        assertEquals(e.x2, g.x2);
        assertEquals(e.y2, g.y2);
        assertEquals(e.getMaterial(), g.getMaterial());
        assertEquals(e.attenuationDb24, g.attenuationDb24);
        assertEquals(e.attenuationDb5, g.attenuationDb5);
    }
}
