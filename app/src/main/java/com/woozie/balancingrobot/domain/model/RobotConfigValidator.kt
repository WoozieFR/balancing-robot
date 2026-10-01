package com.woozie.balancingrobot.domain.model

data class ConfigValidationError(val field: String, val message: String)

data class ConfigValidationResult(val errors: List<ConfigValidationError>) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Validates a complete configuration without correcting any value silently. */
object RobotConfigValidator {
    private const val EXPECTED_SCHEMA = 1
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
