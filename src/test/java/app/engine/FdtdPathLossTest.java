package app.engine;

import app.model.AP;
import app.model.Band;
import app.model.WifiEnvironment;
import javafx.geometry.Point2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2D FDTD → 3D 절대 경로 손실 변환 검증 — 벽이 없는 빈 공간에서는 자유공간 손실(FSPL)과 같아야 한다.
 *
 * <p>예전 정밀 검증은 공유기 옆 전력을 0 dB 로 둔 상대값이라, 같은 조건에서 FSPL 보다 수십 dB 작게 나왔다.</p>
 */
class FdtdPathLossTest {

    @Test
    void freeSpaceMatchesFspl() {
        double scale = 0.03;                    // 0.03 m/px → 300 px = 9 m
        int w = 300, h = 300;
        WifiEnvironment env = new WifiEnvironment();
        env.setScaleMPerPx(scale);
        AP ap = new AP();
        ap.x = 150; ap.y = 150;
        env.getAps().add(ap);

        FdtdWaveSimulator sim = new FdtdWaveSimulator(env, w, h, 2, Band.GHZ_24);
        try {
            for (int s = 0; s < 3000; s += 100) sim.step(100);
            double f = ap.radios.get(Band.GHZ_24).centerFreqGhz();
            FdtdPathLoss pl = new FdtdPathLoss(sim, List.of(new Point2D(ap.x, ap.y)), scale, f);

            // 1 m 이내는 정의상 FSPL
            assertEquals(DpmPathGrid.fspl(0.6, f), pl.pathLossDb(150 + 20, 150), 1e-9);
            // 그 밖에서는 FDTD 로 잰 값이 FSPL 과 ±3 dB 이내 (격자 분산·간섭 무늬 허용)
            for (double rM : new double[]{1.8, 2.7, 3.6}) {
                double px = 150 + rM / scale;
                assertEquals(DpmPathGrid.fspl(rM, f), pl.pathLossDb(px, 150), 3.0, "r=" + rM + " m");
                assertEquals(DpmPathGrid.fspl(rM, f), pl.pathLossDb(150, 150 - rM / scale), 3.0, "r=" + rM + " m (위쪽)");
            }
        } finally {
            sim.shutdown();
        }
    }
}
