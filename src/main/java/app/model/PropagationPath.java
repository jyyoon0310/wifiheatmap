package app.model;

import javafx.geometry.Point2D;

public class PropagationPath {

    public enum Type {
        LOS,
        REFLECTION,
        DIFFRACTION
    }

    public final Type type;
    public final Point2D p1;
    public final Point2D p2;
    public final Point2D p3; // null for LOS

    public PropagationPath(Type type, Point2D p1, Point2D p2, Point2D p3) {
        this.type = type;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;
    }
}
