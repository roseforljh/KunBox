package com.kunk.singbox.aidl;

import android.os.Bundle;
import com.kunk.singbox.aidl.ISingBoxServiceCallback;

interface ISingBoxService {
    Bundle getStateSnapshot();

    void registerCallback(ISingBoxServiceCallback callback);

    void unregisterCallback(ISingBoxServiceCallback callback);

    oneway void notifyAppLifecycle(boolean isForeground);

    int hotReloadConfig(String configContent);

    oneway void requestUrlTestNodeDelay(long requestId, String groupTag, String nodeTag, int timeoutMs);
}
