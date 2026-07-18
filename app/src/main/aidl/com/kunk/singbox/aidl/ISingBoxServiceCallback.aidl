package com.kunk.singbox.aidl;

oneway interface ISingBoxServiceCallback {
    void onStateChanged(
        int state,
        String activeLabel,
        String lastError,
        boolean manuallyStopped,
        long generation
    );
    void onUrlTestNodeDelayResult(long requestId, int delay);
}
