package com.directlink.client

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

object DebugStats {
    var packetsReceived by mutableStateOf(0)
    var framesCompleted by mutableStateOf(0)
    var framesDropped by mutableStateOf(0)
    var naluFound by mutableStateOf(0)
    var spsFound by mutableStateOf(false)
    var ppsFound by mutableStateOf(false)
    var idrFound by mutableStateOf(false)
    var decoderConfigured by mutableStateOf(false)
    var framesDecoded by mutableStateOf(0)
    var lastError by mutableStateOf("")
}
