package app.solver.v2;

import app.engine.FdtdWaveSimulator;
import app.model.Band;
import app.model.WifiEnvironment;
import javafx.scene.image.WritableImage;

import java.util.ServiceLoader;

/**
 * Solver v2 런타임 진입점.
 *
 * - 기본은 CPU FDTD 엔진(FdtdWaveSimulator)을 사용한다.
 * - GPU backend가 ServiceLoader로 제공되면 AUTO 모드에서 자동 선택한다.
 * - MainController는 이 타입만 참조하므로, 엔진 교체가 UI 코드에 침투하지 않는다.
 */
public final class SolverV2Engine {

    public enum BackendPreference {
        AUTO,
        CPU,
        GPU_PREFERRED
    }

    private interface Backend {
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
    }

    private static final class CpuBackend implements Backend {
        private final FdtdWaveSimulator sim;

        CpuBackend(WifiEnvironment env, int widthPx, int heightPx, int cellPx, Band displayBandFilter) {
            this.sim = new FdtdWaveSimulator(env, widthPx, heightPx, cellPx, displayBandFilter);
        }

        @Override public int widthPx() { return sim.widthPx(); }
        @Override public int heightPx() { return sim.heightPx(); }
        @Override public int cellPx() { return sim.cellPx(); }
        @Override public long stepCount() { return sim.stepCount(); }
        @Override public double timeNs() { return sim.timeNs(); }
        @Override public void reset() { sim.reset(); }
        @Override public void refreshSources(WifiEnvironment env) { sim.refreshSources(env); }
        @Override public void step(int subSteps) { sim.step(subSteps); }
        @Override public WritableImage renderFrame() { return sim.renderFrame(); }
        @Override public double dxMeters() { return sim.dxMeters(); }
        @Override public double dtSeconds() { return sim.dtSeconds(); }
        @Override public int pmlCells() { return sim.pmlCells(); }
        @Override public Band displayBandFilter() { return sim.displayBandFilter(); }
        @Override public int sourceCount() { return sim.sourceCount(); }
        @Override public double courantNumber() { return sim.courantNumber(); }
        @Override public FdtdWaveSimulator.MaterialStats materialStats() { return sim.materialStats(); }
        @Override public String diagnosticsSummary() { return sim.diagnosticsSummary(); }
    }

    private static final class GpuBackend implements Backend {
        private final GpuWaveSolver.Session session;

        GpuBackend(GpuWaveSolver.Session session) {
            this.session = session;
        }

        @Override public int widthPx() { return session.widthPx(); }
        @Override public int heightPx() { return session.heightPx(); }
        @Override public int cellPx() { return session.cellPx(); }
        @Override public long stepCount() { return session.stepCount(); }
        @Override public double timeNs() { return session.timeNs(); }
        @Override public void reset() { session.reset(); }
        @Override public void refreshSources(WifiEnvironment env) { session.refreshSources(env); }
        @Override public void step(int subSteps) { session.step(subSteps); }
        @Override public WritableImage renderFrame() { return session.renderFrame(); }
        @Override public double dxMeters() { return session.dxMeters(); }
        @Override public double dtSeconds() { return session.dtSeconds(); }
        @Override public int pmlCells() { return session.pmlCells(); }
        @Override public Band displayBandFilter() { return session.displayBandFilter(); }
        @Override public int sourceCount() { return session.sourceCount(); }
        @Override public double courantNumber() { return session.courantNumber(); }
        @Override public FdtdWaveSimulator.MaterialStats materialStats() { return session.materialStats(); }
        @Override public String diagnosticsSummary() { return session.diagnosticsSummary(); }
    }

    private final Backend backend;
    private final String backendName;

    public SolverV2Engine(WifiEnvironment env, int widthPx, int heightPx, int cellPx) {
        this(env, widthPx, heightPx, cellPx, null, BackendPreference.AUTO);
    }

    public SolverV2Engine(WifiEnvironment env, int widthPx, int heightPx, int cellPx, Band displayBandFilter) {
        this(env, widthPx, heightPx, cellPx, displayBandFilter, BackendPreference.AUTO);
    }

    public SolverV2Engine(WifiEnvironment env,
                          int widthPx,
                          int heightPx,
                          int cellPx,
                          Band displayBandFilter,
                          BackendPreference preference) {
        BackendPreference pref = (preference == null) ? BackendPreference.AUTO : preference;
        GpuWaveSolver.Session gpuSession = (pref == BackendPreference.CPU)
                ? null
                : loadGpuSession(env, widthPx, heightPx, cellPx, displayBandFilter);

        if (gpuSession != null) {
            this.backend = new GpuBackend(gpuSession);
            this.backendName = "GPU";
        } else {
            if (pref == BackendPreference.GPU_PREFERRED) {
                System.out.println("[SolverV2] GPU backend를 찾지 못해 CPU로 fallback.");
            }
            this.backend = new CpuBackend(env, widthPx, heightPx, cellPx, displayBandFilter);
            this.backendName = "CPU";
        }
    }

    private static GpuWaveSolver.Session loadGpuSession(WifiEnvironment env,
                                                        int widthPx,
                                                        int heightPx,
                                                        int cellPx,
                                                        Band displayBandFilter) {
        for (GpuWaveSolver solver : ServiceLoader.load(GpuWaveSolver.class)) {
            if (solver == null) continue;
            try {
                if (!solver.isAvailable()) continue;
                GpuWaveSolver.Session session =
                        solver.createSession(env, widthPx, heightPx, cellPx, displayBandFilter);
                if (session != null) return session;
            } catch (Throwable ex) {
                System.err.println("[SolverV2] GPU backend init 실패: " + ex.getMessage());
            }
        }
        return null;
    }

    public String backendName() { return backendName; }
    public boolean isGpuBackend() { return "GPU".equals(backendName); }

    public int widthPx() { return backend.widthPx(); }
    public int heightPx() { return backend.heightPx(); }
    public int cellPx() { return backend.cellPx(); }
    public long stepCount() { return backend.stepCount(); }
    public double timeNs() { return backend.timeNs(); }
    public void reset() { backend.reset(); }
    public void refreshSources(WifiEnvironment env) { backend.refreshSources(env); }
    public void step(int subSteps) { backend.step(subSteps); }
    public WritableImage renderFrame() { return backend.renderFrame(); }
    public double dxMeters() { return backend.dxMeters(); }
    public double dtSeconds() { return backend.dtSeconds(); }
    public int pmlCells() { return backend.pmlCells(); }
    public Band displayBandFilter() { return backend.displayBandFilter(); }
    public int sourceCount() { return backend.sourceCount(); }
    public double courantNumber() { return backend.courantNumber(); }
    public FdtdWaveSimulator.MaterialStats materialStats() { return backend.materialStats(); }
    public String diagnosticsSummary() { return backend.diagnosticsSummary(); }
}
