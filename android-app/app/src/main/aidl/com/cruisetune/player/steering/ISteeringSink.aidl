package com.cruisetune.player.steering;
oneway interface ISteeringSink {
    void onConnection(boolean connected, String state, String detail);
    void onKey(int keyCode, int event, int parameter, int extra, int callerUid, long receivedAt);
    void onDiagnostic(String detail);
}
