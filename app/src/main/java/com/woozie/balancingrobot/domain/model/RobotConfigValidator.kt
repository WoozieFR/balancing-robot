package com.woozie.balancingrobot.domain.model

data class ConfigValidationError(val field: String, val message: String)

data class ConfigValidationResult(val errors: List<ConfigValidationError>) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Validates a complete configuration without correcting any value silently. */
object RobotConfigValidator {
    private const val EXPECTED_SCHEMA = 2
    private const val EXPECTED_BAUD_RATE = 1_000_000

    fun validate(config: RobotConfig): ConfigValidationResult {
        val errors = buildList {
            if (config.schemaVersion != EXPECTED_SCHEMA) error("schemaVersion", "unsupported schema")
            if (config.imuSign != -1 && config.imuSign != 1) error("imuSign", "must be -1 or 1")
            if (!config.zeroOffsetDeg.isFinite() || config.zeroOffsetDeg !in -180.0..180.0) {
                error("zeroOffsetDeg", "must be finite and in [-180, 180]")
            }
            if (!config.alpha.isFinite() || config.alpha !in 0.0..1.0) {
                error("alpha", "must be finite and in [0, 1]")
            }
            if (!config.targetDeg.isFinite() || config.targetDeg !in -180.0..180.0) {
                error("targetDeg", "must be finite and in [-180, 180]")
            }
            if (!config.kp.isFinite() || config.kp !in 0.0..2000.0) {
                error("kp", "must be finite and in [0, 2000]")
            }
            if (!config.kd.isFinite() || config.kd !in 0.0..2000.0) {
                error("kd", "must be finite and in [0, 2000]")
            }
            if (!config.speedTargetLimitCmPerSec.isFinite() ||
                config.speedTargetLimitCmPerSec !in 1.0..20.0
            ) {
                error("speedTargetLimitCmPerSec", "must be finite and in [1, 20]")
            }
            if (!config.speedTargetCmPerSec.isFinite() ||
                kotlin.math.abs(config.speedTargetCmPerSec) > config.speedTargetLimitCmPerSec
            ) {
                error("speedTargetCmPerSec", "must be within the configured speed target limit")
            }
            if (!config.joystickMaxLeanDeg.isFinite() || config.joystickMaxLeanDeg !in 0.5..15.0) {
                error("joystickMaxLeanDeg", "must be finite and in [0.5, 15] degrees")
            }
            if (!config.joystickLeanSlewRateDegPerSec.isFinite() ||
                config.joystickLeanSlewRateDegPerSec !in 1.0..180.0
            ) {
                error("joystickLeanSlewRateDegPerSec", "must be finite and in [1, 180] degrees/s")
            }
            if (!config.joystickDeadbandCmPerSec.isFinite() || config.joystickDeadbandCmPerSec !in 0.0..2.0) {
                error("joystickDeadbandCmPerSec", "must be finite and in [0, 2] cm/s")
            }
            if (!config.brakeKpDegPerCmPerSec.isFinite() || config.brakeKpDegPerCmPerSec !in 0.0..2.0) {
                error("brakeKpDegPerCmPerSec", "must be finite and in [0, 2] deg/(cm/s)")
            }
            if (!config.brakeLimitDeg.isFinite() || config.brakeLimitDeg !in 0.0..10.0) {
                error("brakeLimitDeg", "must be finite and in [0, 10] degrees")
            }
            if (!config.speedTargetSlewRateCmPerSec.isFinite() ||
                config.speedTargetSlewRateCmPerSec !in 1.0..100.0
            ) {
                error("speedTargetSlewRateCmPerSec", "must be finite and in [1, 100] cm/s²")
            }
            if (!config.speedKevDegPerCmPerSec.isFinite() ||
                config.speedKevDegPerCmPerSec !in 0.0..2.0
            ) {
                error("speedKevDegPerCmPerSec", "must be finite and in [0, 2]")
            }
            if (config.speedLoopRateHz !in 5..100) {
                error("speedLoopRateHz", "must be in [5, 100] Hz")
            }
            if (!config.speedFilterAlpha.isFinite() || config.speedFilterAlpha !in 0.01..1.0) {
                error("speedFilterAlpha", "must be finite and in [0.01, 1]")
            }
            if (!config.speedTargetAngleLimitDeg.isFinite() ||
                config.speedTargetAngleLimitDeg !in 1.0..15.0
            ) {
                error("speedTargetAngleLimitDeg", "must be finite and in [1, 15] degrees")
            }
            if (!config.speedIntegralGainDegPerCmPerSecSec.isFinite() ||
                config.speedIntegralGainDegPerCmPerSecSec !in 0.0..2.0
            ) {
                error("speedIntegralGainDegPerCmPerSecSec", "must be finite and in [0, 2]")
            }
            if (!config.speedAbsoluteAngleLimitDeg.isFinite() ||
                config.speedAbsoluteAngleLimitDeg !in 5.0..45.0
            ) {
                error("speedAbsoluteAngleLimitDeg", "must be finite and in [5, 45] degrees")
            }
            if (!config.speedTargetSlewRateDegPerSec.isFinite() ||
                config.speedTargetSlewRateDegPerSec !in 1.0..180.0
            ) {
                error("speedTargetSlewRateDegPerSec", "must be finite and in [1, 180] degrees/s")
            }
            if (config.speedFeedbackTimeoutMs !in 40..500) {
                error("speedFeedbackTimeoutMs", "must be in [40, 500] ms")
            }
            if (!config.speedQuietThresholdCmPerSec.isFinite() ||
                config.speedQuietThresholdCmPerSec !in 0.05..10.0
            ) {
                error("speedQuietThresholdCmPerSec", "must be finite and in [0.05, 10] cm/s")
            }
            if (config.speedQuietDurationMs !in 100..5_000) {
                error("speedQuietDurationMs", "must be in [100, 5000] ms")
            }
            if (!config.yawTargetDegPerSec.isFinite() || config.yawTargetDegPerSec !in -360.0..360.0) {
                error("yawTargetDegPerSec", "must be finite and in [-360, 360] deg/s")
            }
            if (!config.yawKpCommandPerDegPerSec.isFinite() ||
                config.yawKpCommandPerDegPerSec !in 0.0..20.0
            ) {
                error("yawKpCommandPerDegPerSec", "must be finite and in [0, 20]")
            }
            if (!config.wheelDiameterMm.isFinite() || config.wheelDiameterMm !in 10.0..300.0) {
                error("wheelDiameterMm", "must be finite and in [10, 300] mm")
            }
            if (!config.driveRatio.isFinite() || config.driveRatio !in 0.1..100.0) {
                error("driveRatio", "must be finite and in [0.1, 100]")
            }
            if (config.vmax !in 0..20_000) error("vmax", "must be in [0, 20000]")
            if (config.pwmMax !in 0..1_000) error("pwmMax", "must be in [0, 1000]")
            if (config.motorIds.size != 2) error("motorIds", "exactly two motor IDs are required")
            if (config.motorIds.any { it !in 0..252 }) error("motorIds", "each ID must be in [0, 252]")
            if (config.motorIds.toSet().size != config.motorIds.size) error("motorIds", "IDs must be distinct")
            if (config.motorSigns.size != config.motorIds.size) {
                error("motorSigns", "one sign is required for each motor ID")
            } else if (config.motorSigns.any { it != -1 && it != 1 }) {
                error("motorSigns", "each sign must be -1 or 1")
            }
            if (config.baudRate != EXPECTED_BAUD_RATE) {
                error("baudRate", "must be 1000000 for the STS3215 bus")
            }
            if (config.torqueLimit !in 0..1023) error("torqueLimit", "must be in [0, 1023]")
            if (config.imuTimeoutMs !in 20..1000) error("imuTimeoutMs", "must be in [20, 1000] ms")
            if (!config.fallAngleDeg.isFinite() || config.fallAngleDeg !in 5.0..90.0) {
                error("fallAngleDeg", "must be finite and in [5, 90] degrees")
            }
            if (config.fallDurationMs !in 20..1000) error("fallDurationMs", "must be in [20, 1000] ms")
            if (config.manualTimeoutMs !in 100..2000) error("manualTimeoutMs", "must be in [100, 2000] ms")
            if (config.logCapacity !in 1_000..500_000) error("logCapacity", "must be in [1000, 500000]")
            if (config.webPort !in 1024..65_535) error("webPort", "must be in [1024, 65535]")
        }
        return ConfigValidationResult(errors)
    }

    private fun MutableList<ConfigValidationError>.error(field: String, message: String) {
        add(ConfigValidationError(field, message))
    }
}
