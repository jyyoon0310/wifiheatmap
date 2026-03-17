package app.controller;

import app.model.AP;
import app.model.Band;
import app.model.WifiEnvironment;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;

import java.util.HashSet;
import java.util.Set;

public class ApController {

    private final WifiEnvironment env;

    private AP hoverAp = null;
    private AP selectedAp = null;

    private boolean dragging = false;
    private double dragOffsetX = 0.0;
    private double dragOffsetY = 0.0;

    // hit-test 반경(px)
    public static final double HIT_R = 10.0;

    public ApController(WifiEnvironment env) {
        this.env = env;
    }

    public AP getHoverAp() { return hoverAp; }
    public AP getSelectedAp() { return selectedAp; }
    public boolean isDragging() { return dragging; }

    public void clearInteraction() {
        hoverAp = null;
        dragging = false;
        dragOffsetX = 0.0;
        dragOffsetY = 0.0;
    }

    public void clearSelection() {
        selectedAp = null;
    }

    public AP findApNear(double x, double y) {
        AP best = null;
        double bestD2 = HIT_R * HIT_R;

        for (AP ap : env.getAps()) {
            if (ap == null || !ap.enabled) continue;

            double dx = ap.x - x;
            double dy = ap.y - y;
            double d2 = dx * dx + dy * dy;

            if (d2 <= bestD2) {
                bestD2 = d2;
                best = ap;
            }
        }
        return best;
    }

    public void onMouseMoved(double x, double y, Runnable requestRender) {
        AP hit = findApNear(x, y);
        if (hit != hoverAp) {
            hoverAp = hit;
            if (requestRender != null) requestRender.run();
        }
    }

    public void onMousePressed(double x, double y, MouseButton button, Runnable requestRender) {
        if (button != MouseButton.PRIMARY) return;

        AP hit = findApNear(x, y);
        if (hit != null) {
            selectedAp = hit;
            dragging = true;
            dragOffsetX = hit.x - x;
            dragOffsetY = hit.y - y;

            if (requestRender != null) requestRender.run();
        } else {
            // 빈 곳 press는 생성 X (생성은 click에서만)
            dragging = false;
        }
    }

    public void onMouseDragged(double x, double y, Runnable requestRender) {
        if (!dragging || selectedAp == null) return;

        selectedAp.x = x + dragOffsetX;
        selectedAp.y = y + dragOffsetY;

        if (requestRender != null) requestRender.run();
    }

    public void onMouseReleased(Runnable requestRender) {
        if (!dragging) return;
        dragging = false;
        if (requestRender != null) requestRender.run();
    }

    /**
     * AP툴에서만 동작:
     * - AP 위 클릭: 선택
     * - 빈 곳 클릭: 새 AP 생성 + 선택
     */
    public void onMouseClicked(double x, double y, MouseButton button, Runnable requestRender) {
        if (button != MouseButton.PRIMARY) return;

        AP hit = findApNear(x, y);
        if (hit != null) {
            selectedAp = hit;
            if (requestRender != null) requestRender.run();
            return;
        }

        AP ap = addApAt(x, y);
        selectedAp = ap;
        if (requestRender != null) requestRender.run();
    }

    public boolean onKeyPressed(KeyCode code, Runnable requestRender) {
        if (code == KeyCode.DELETE || code == KeyCode.BACK_SPACE) {
            if (selectedAp != null) {
                env.getAps().remove(selectedAp);
                selectedAp = null;
                hoverAp = null;
                dragging = false;
                if (requestRender != null) requestRender.run();
                return true;
            }
        }
        return false;
    }

    private AP addApAt(double x, double y) {
        AP ap = new AP();
        ap.name = nextApName();
        ap.x = x;
        ap.y = y;
        ap.enabled = true;

        try {
            ap.radios.get(Band.GHZ_24).ssid = ap.name + "_24G";
            ap.radios.get(Band.GHZ_5).ssid  = ap.name + "_5G";
            ap.radios.get(Band.GHZ_6).ssid  = ap.name + "_6G";
        } catch (Exception ignored) {}

        env.getAps().add(ap);
        return ap;
    }

    private String nextApName() {
        Set<String> used = new HashSet<>();
        for (AP existing : env.getAps()) {
            if (existing == null || existing.name == null) continue;
            used.add(existing.name.trim());
        }

        int idx = 1;
        while (used.contains("AP-" + idx)) {
            idx++;
        }
        return "AP-" + idx;
    }
}
