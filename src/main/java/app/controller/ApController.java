package app.controller;

import app.dialog.ApEditorDialog;
import app.model.Band;
import app.model.AP;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.stage.Window;

public class ApController {

    private final ObservableList<AP> aps;
    private final Runnable redraw;
    private final Runnable regenHeatmapIfPresent;

    public ApController(ObservableList<AP> aps, Runnable redraw, Runnable regenHeatmapIfPresent) {
        this.aps = aps;
        this.redraw = redraw;
        this.regenHeatmapIfPresent = regenHeatmapIfPresent;
    }

    public ListView<AP> buildApListView(Window owner) {
        ListView<AP> apList = new ListView<>(aps);

        apList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(AP item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item.toString());
                setPadding(new Insets(6, 8, 6, 8));
            }
        });

        apList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                AP sel = apList.getSelectionModel().getSelectedItem();
                if (sel != null) editAp(owner, sel);
            }
        });

        apList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                AP sel = apList.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    aps.remove(sel);
                    redraw.run();
                    regenHeatmapIfPresent.run();
                }
            }
        });

        return apList;
    }

    public void addAPAt(double x, double y) {
        AP ap = new AP();
        ap.name = "AP-" + (aps.size() + 1);
        ap.x = x;
        ap.y = y;

        // 밴드별 SSID 기본값
        ap.radios.get(Band.GHZ_24).ssid = ap.name + "_24G";
        ap.radios.get(Band.GHZ_5).ssid  = ap.name + "_5G";
        ap.radios.get(Band.GHZ_6).ssid  = ap.name + "_6G";

        aps.add(ap);
        redraw.run();
    }

    public AP findApNear(double x, double y, double rPx) {
        for (AP ap : aps) {
            if (new Point2D(ap.x, ap.y).distance(x, y) <= rPx) return ap;
        }
        return null;
    }

    public void editAp(Window owner, AP ap) {
        ApEditorDialog.Result r = ApEditorDialog.show(owner, ap, () -> {
            redraw.run();
            regenHeatmapIfPresent.run();
        });

        if (r == ApEditorDialog.Result.DELETE) {
            aps.remove(ap);
            redraw.run();
            regenHeatmapIfPresent.run();
        } else if (r == ApEditorDialog.Result.OK) {
            // 리스트 갱신 강제(ObservableList지만 객체 내부 변경이라)
            int idx = aps.indexOf(ap);
            if (idx >= 0) aps.set(idx, ap);
            redraw.run();
            regenHeatmapIfPresent.run();
        }
    }
}