package com.ramphoenix.camera;

/** The camera views the stock app exposes (per Beau's photo: rear, front, 360). */
public enum CameraMode {
    REAR("Rear"),
    FRONT("Front"),
    SURROUND_360("360");

    public final String label;
    CameraMode(String label) { this.label = label; }
}
