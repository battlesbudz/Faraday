package com.battlesbudz.jarvis.v2.privilege;

/** Closed shell helper contract. Additions require an A1 charter amendment. */
interface IPrivilegeHelper {
    int createVirtualDisplay(String name, int width, int height, int densityDpi);
    void releaseVirtualDisplay(int displayId);
    boolean enableAccessibilityService(String component);
    boolean grantPermission(String permissionName);
    String getPrivilegeState();
}
