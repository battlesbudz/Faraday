package com.battlesbudz.jarvis.v2.privilege;

import android.os.Bundle;

/** Closed shell helper contract. Additions require an A1 charter amendment. */
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
}
