package com.battlesbudz.jarvis.v2.privilege;

import android.os.Bundle;

/** Closed shell helper contract. Additions require an A1 charter amendment. Battles approved readFrame on 2026-11-23. */
interface IPrivilegeHelper {
    int createVirtualDisplay(String name, int width, int height, int densityDpi);
    void releaseVirtualDisplay(int displayId);
    boolean enableAccessibilityService(String component);
    boolean grantPermission(String permissionName);
    String getPrivilegeState();
    boolean injectInput(int displayId, in Bundle event);
    boolean moveTaskToDisplay(int taskId, int displayId);
    String getClipboardText();
    boolean setClipboardText(String text);
    /** Returns the newest captured frame of a helper-owned display as a Bundle with keys "width" (int), "height" (int), "rgba" (tightly packed RGBA_8888 byte[]; long edge <= 360 px), and "capturedAtMs" (wall-clock long). Returns null when the display is not helper-owned or no frame is available yet. */
    Bundle readFrame(int displayId);
}
