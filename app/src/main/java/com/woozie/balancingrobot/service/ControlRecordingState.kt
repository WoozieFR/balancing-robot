package com.woozie.balancingrobot.service

data class ControlRecordingState(
    val recording: Boolean = false,
    val samples: Int = 0,
)
