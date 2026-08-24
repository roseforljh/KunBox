package com.kunk.singbox.aidl;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IRootSingBoxService {
    Bundle getSnapshot();

    Bundle getCapabilityReport();

    ParcelFileDescriptor openCommandConnection();

    Bundle start(
        String configPath,
        String runtimeSessionId,
        String appMode,
        in String[] allowlist,
        in String[] blocklist,
        String selfPackage,
        boolean forceConnectionOwnerRouting,
        int appUid,
        boolean proxyIpv4,
        boolean proxyIpv6,
        boolean blockIpv4,
        boolean blockIpv6,
        boolean blockQuic,
        String apkPath
    );

    Bundle hotReload(String configPath, String runtimeSessionId);

    Bundle stop(String runtimeSessionId);

    boolean resetNetwork();
}
