package app.solver.v2;

import app.engine.FdtdWaveSimulator;
import app.model.Band;
import app.model.WifiEnvironment;
import javafx.scene.image.WritableImage;

/**
 * Optional GPU solver backend SPI.
 *
 * 기본 프로젝트는 CPU(FdtdWaveSimulator)만 포함한다.
 * 외부 모듈이 ServiceLoader로 이 인터페이스 구현을 제공하면
 * SolverV2Engine이 GPU 세션을 자동 선택(AUTO)할 수 있다.
 */
public interface GpuWaveSolver {

    boolean isAvailable();

    Session createSession(WifiEnvironment env,
                          int widthPx,
                          int heightPx,
                          int cellPx,
                          Band displayBandFilter);

    interface Session {
        int widthPx();
        int heightPx();
        int cellPx();

        long stepCount();
        double timeNs();

        void reset();
        void refreshSources(WifiEnvironment env);
        void step(int subSteps);
        WritableImage renderFrame();

        double dxMeters();
        double dtSeconds();
        int pmlCells();
        Band displayBandFilter();
        int sourceCount();
        double courantNumber();
        FdtdWaveSimulator.MaterialStats materialStats();
        String diagnosticsSummary();

        /**
         * Per-frame visualization debug summary.
         * (예: max/mean/norm/floor/coverage)
         * 기본 구현은 빈 문자열이며, backend가 제공하면 LeftPanel/Canvas HUD에 표시된다.
         */
        default String visualDebugSummary() {
            return "";
        }
    }
}
