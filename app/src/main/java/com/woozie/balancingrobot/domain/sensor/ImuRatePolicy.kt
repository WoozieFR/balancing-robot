package com.woozie.balancingrobot.domain.sensor

object ImuRatePolicy {
    const val DEFAULT_HZ = 200
    const val MIN_HZ = 20
    const val MAX_HZ = 200

    fun isValid(rateHz: Int): Boolean = rateHz in MIN_HZ..MAX_HZ

    fun periodUs(rateHz: Int): Int {
        require(isValid(rateHz)) { "IMU rate must be in $MIN_HZ..$MAX_HZ Hz" }
        return 1_000_000 / rateHz
    }
}
