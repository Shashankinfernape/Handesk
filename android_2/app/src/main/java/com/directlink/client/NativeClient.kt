package com.directlink.client

import android.view.Surface

class NativeClient {
    init {
        System.loadLibrary("native-lib")
    }

    var isConnected: Boolean = false
        private set

    fun connect(ip: String, surface: Surface) {
        connectNative(ip, surface)
        isConnected = true
    }

    fun disconnect() {
        disconnectNative()
        isConnected = false
    }

    // JNI External hooks
    private external fun connectNative(ip: String, surface: Surface)
    private external fun disconnectNative()
}
