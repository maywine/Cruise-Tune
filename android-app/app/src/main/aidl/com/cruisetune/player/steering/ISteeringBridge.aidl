package com.cruisetune.player.steering;
import com.cruisetune.player.steering.ISteeringSink;
interface ISteeringBridge {
    oneway void start(ISteeringSink sink);
    int getControlIndex();
}
