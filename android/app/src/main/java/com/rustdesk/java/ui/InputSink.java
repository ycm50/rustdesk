package com.rustdesk.java.ui;

/** Receives input already translated into remote desktop pixel coordinates. */
public interface InputSink {

    int BUTTON_LEFT = 1;
    int BUTTON_RIGHT = 2;
    int BUTTON_MIDDLE = 4;

    void onMouseMove(float remoteX, float remoteY);

    void onMouseDown(float remoteX, float remoteY, int button);

    void onMouseUp(float remoteX, float remoteY, int button);

    void onWheel(float remoteX, float remoteY, int delta);
}
