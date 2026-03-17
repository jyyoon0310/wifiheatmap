package app.solver.v2.metal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JNI bridge for Metal compute backend.
 *
 * Native library name: libmetalsolverjni.dylib
 */
public final class MetalNativeBridge {
    private static volatile boolean loadTried = false;
    private static volatile boolean loaded = false;

    private MetalNativeBridge() {
    }

    public static boolean ensureLoaded() {
        if (loadTried) return loaded;
        synchronized (MetalNativeBridge.class) {
            if (loadTried) return loaded;
            loadTried = true;
            loaded = tryLoad();
            return loaded;
        }
    }

    private static boolean tryLoad() {
        try {
            System.loadLibrary("metalsolverjni");
            return true;
        } catch (Throwable ignored) {
            // fallback to local build path (IDE/Gradle run)
        }

        try {
            String userDir = System.getProperty("user.dir", ".");
            Path p = Paths.get(userDir, "build", "native", "libmetalsolverjni.dylib");
            if (Files.exists(p)) {
                System.load(p.toAbsolutePath().toString());
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isNativeAvailable() {
        return ensureLoaded() && nIsAvailable();
    }

    public static long create(int nx,
                              int ny,
                              float invDx,
                              float alpha,
                              float beta,
                              float[] cEx1,
                              float[] cEx2,
                              float[] cEy1,
                              float[] cEy2,
                              float[] bHx,
                              float[] cHx,
                              float[] bHy,
                              float[] cHy) {
        if (!ensureLoaded()) return 0L;
        return nCreate(nx, ny, invDx, alpha, beta, cEx1, cEx2, cEy1, cEy2, bHx, cHx, bHy, cHy);
    }

    public static void destroy(long handle) {
        if (handle == 0L || !loaded) return;
        nDestroy(handle);
    }

    public static void reset(long handle) {
        if (handle == 0L || !loaded) return;
        nReset(handle);
    }

    public static void step(long handle, int[] sourceX, int[] sourceY, float[] sourceValue, int sourceCount) {
        if (handle == 0L || !loaded) return;
        nStep(handle, sourceX, sourceY, sourceValue, sourceCount);
    }

    public static void readPower(long handle, float[] outPower) {
        if (handle == 0L || !loaded || outPower == null) return;
        nReadPower(handle, outPower);
    }

    private static native boolean nIsAvailable();

    private static native long nCreate(int nx,
                                       int ny,
                                       float invDx,
                                       float alpha,
                                       float beta,
                                       float[] cEx1,
                                       float[] cEx2,
                                       float[] cEy1,
                                       float[] cEy2,
                                       float[] bHx,
                                       float[] cHx,
                                       float[] bHy,
                                       float[] cHy);

    private static native void nDestroy(long handle);

    private static native void nReset(long handle);

    private static native void nStep(long handle, int[] sourceX, int[] sourceY, float[] sourceValue, int sourceCount);

    private static native void nReadPower(long handle, float[] outPower);
}
