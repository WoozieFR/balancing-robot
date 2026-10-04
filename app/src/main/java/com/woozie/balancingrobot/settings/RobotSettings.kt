package com.woozie.balancingrobot.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.woozie.balancingrobot.domain.sensor.ImuRatePolicy
import com.woozie.balancingrobot.domain.gamepad.GamepadConfig
import com.woozie.balancingrobot.domain.model.Axis
import com.woozie.balancingrobot.domain.model.MotorControlMode
import com.woozie.balancingrobot.domain.model.RobotConfig
import com.woozie.balancingrobot.domain.model.RobotConfigValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.robotSettingsDataStore by preferencesDataStore(name = "robot_settings")

object RobotSettings {
    const val DEFAULT_WEB_PORT = 8766
    private val webPortKey = intPreferencesKey("web_port")
    private val imuRateHzKey = intPreferencesKey("imu_rate_hz")
    private val pdTargetKey = doublePreferencesKey("pd_target_deg")
    private val pdAngleKey = doublePreferencesKey("pd_angle_deg")
    private val pdGyroKey = doublePreferencesKey("pd_gyro_dps")
    private val pdKpKey = doublePreferencesKey("pd_kp")
    private val pdKdKey = doublePreferencesKey("pd_kd")
    private val axisKey = stringPreferencesKey("axis")
    private val imuSignKey = intPreferencesKey("imu_sign")
    private val zeroOffsetKey = doublePreferencesKey("zero_offset_deg")
    private val alphaKey = doublePreferencesKey("alpha")
    private val targetKey = doublePreferencesKey("target_deg")
    private val kpKey = doublePreferencesKey("kp")
    private val kdKey = doublePreferencesKey("kd")
    private val speedLoopEnabledKey = booleanPreferencesKey("speed_loop_enabled")
    private val speedTargetKey = doublePreferencesKey("speed_target_cm_per_sec")
    private val speedTargetLimitKey = doublePreferencesKey("speed_target_limit_cm_per_sec")
    private val speedKevKey = doublePreferencesKey("speed_kev_deg_per_cm_per_sec")
    private val speedLoopRateKey = intPreferencesKey("speed_loop_rate_hz")
    private val speedFilterAlphaKey = doublePreferencesKey("speed_filter_alpha")
    private val speedTargetAngleLimitKey = doublePreferencesKey("speed_target_angle_limit_deg")
    private val speedIntegralGainKey = doublePreferencesKey("speed_integral_gain_deg_per_cmps_s")
    private val speedAbsoluteAngleLimitKey = doublePreferencesKey("speed_absolute_angle_limit_deg")
    private val speedTargetSlewRateKey = doublePreferencesKey("speed_target_slew_rate_deg_per_sec")
    private val speedFeedbackTimeoutKey = intPreferencesKey("speed_feedback_timeout_ms")
    private val yawTargetKey = doublePreferencesKey("yaw_target_deg_per_sec")
    private val yawKpKey = doublePreferencesKey("yaw_kp_command_per_deg_per_sec")
    private val wheelDiameterKey = doublePreferencesKey("wheel_diameter_mm")
    private val driveRatioKey = doublePreferencesKey("drive_ratio")
    private val vmaxKey = intPreferencesKey("vmax")
    private val motorControlModeKey = stringPreferencesKey("motor_control_mode")
    private val pwmMaxKey = intPreferencesKey("pwm_max")
    private val motorIdsKey = stringPreferencesKey("motor_ids")
    private val motorSignsKey = stringPreferencesKey("motor_signs")
    private val torqueLimitKey = intPreferencesKey("torque_limit")
    private val imuTimeoutKey = intPreferencesKey("imu_timeout_ms")
    private val fallAngleKey = doublePreferencesKey("fall_angle_deg")
    private val fallDurationKey = intPreferencesKey("fall_duration_ms")
    private val manualTimeoutKey = intPreferencesKey("manual_timeout_ms")
    private val inhibitSafetyAutoDisarmKey = booleanPreferencesKey("inhibit_safety_auto_disarm")
    private val gamepadMaxSpeedKey = doublePreferencesKey("gamepad_max_speed_cmps")
    private val gamepadMaxYawKey = doublePreferencesKey("gamepad_max_yaw_dps")
    private val gamepadDeadZoneKey = doublePreferencesKey("gamepad_dead_zone")
    private val gamepadExponentKey = doublePreferencesKey("gamepad_response_exponent")
    private val gamepadPrecisionKey = doublePreferencesKey("gamepad_precision_scale")
    private val gamepadSpeedSignKey = intPreferencesKey("gamepad_speed_sign")
    private val gamepadYawSignKey = intPreferencesKey("gamepad_yaw_sign")

    fun webPort(context: Context): Flow<Int> = context.robotSettingsDataStore.data.map { preferences ->
        preferences[webPortKey] ?: DEFAULT_WEB_PORT
    }

    suspend fun saveWebPort(context: Context, port: Int) {
        context.robotSettingsDataStore.edit { preferences ->
            preferences[webPortKey] = port
        }
    }

    fun imuRateHz(context: Context): Flow<Int> = context.robotSettingsDataStore.data.map { preferences ->
        preferences[imuRateHzKey]?.takeIf(ImuRatePolicy::isValid) ?: ImuRatePolicy.DEFAULT_HZ
    }

    suspend fun saveImuRateHz(context: Context, rateHz: Int) {
        require(ImuRatePolicy.isValid(rateHz)) { "Fréquence IMU invalide : $rateHz Hz" }
        context.robotSettingsDataStore.edit { preferences ->
            preferences[imuRateHzKey] = rateHz
        }
    }

    fun gamepadConfig(context: Context): Flow<GamepadConfig> =
        context.robotSettingsDataStore.data.map { preferences ->
            val defaults = GamepadConfig()
            GamepadConfig(
                maxSpeedCmPerSec = preferences[gamepadMaxSpeedKey] ?: defaults.maxSpeedCmPerSec,
                maxYawDegPerSec = preferences[gamepadMaxYawKey] ?: defaults.maxYawDegPerSec,
                deadZone = preferences[gamepadDeadZoneKey] ?: defaults.deadZone,
                responseExponent = preferences[gamepadExponentKey] ?: defaults.responseExponent,
                precisionScale = preferences[gamepadPrecisionKey] ?: defaults.precisionScale,
                speedSign = (preferences[gamepadSpeedSignKey] ?: defaults.speedSign).let { if (it < 0) -1 else 1 },
                yawSign = (preferences[gamepadYawSignKey] ?: defaults.yawSign).let { if (it < 0) -1 else 1 },
            ).takeIf {
                it.maxSpeedCmPerSec in 0.0..100.0 &&
                    it.maxYawDegPerSec in 0.0..720.0 &&
                    it.deadZone in 0.0..0.95 &&
                    it.responseExponent in 1.0..3.0 &&
                    it.precisionScale in 0.05..1.0
            } ?: defaults
        }

    suspend fun saveGamepadConfig(context: Context, config: GamepadConfig) {
        require(config.maxSpeedCmPerSec in 0.0..100.0)
        require(config.maxYawDegPerSec in 0.0..720.0)
        require(config.deadZone in 0.0..0.95)
        require(config.responseExponent in 1.0..3.0)
        require(config.precisionScale in 0.05..1.0)
        require(config.speedSign == -1 || config.speedSign == 1)
        require(config.yawSign == -1 || config.yawSign == 1)
        context.robotSettingsDataStore.edit { preferences ->
            preferences[gamepadMaxSpeedKey] = config.maxSpeedCmPerSec
            preferences[gamepadMaxYawKey] = config.maxYawDegPerSec
            preferences[gamepadDeadZoneKey] = config.deadZone
            preferences[gamepadExponentKey] = config.responseExponent
            preferences[gamepadPrecisionKey] = config.precisionScale
            preferences[gamepadSpeedSignKey] = config.speedSign
            preferences[gamepadYawSignKey] = config.yawSign
        }
    }

    fun pdSimulation(context: Context): Flow<PdSimulationSettings> =
        context.robotSettingsDataStore.data.map { preferences ->
            PdSimulationSettings(
                targetDeg = preferences[pdTargetKey] ?: 0.0,
                angleDeg = preferences[pdAngleKey] ?: 5.0,
                gyroDps = preferences[pdGyroKey] ?: 0.0,
                kp = preferences[pdKpKey] ?: 10.0,
                kd = preferences[pdKdKey] ?: 0.5,
            )
        }

    suspend fun savePdSimulation(context: Context, settings: PdSimulationSettings) {
        context.robotSettingsDataStore.edit { preferences ->
            preferences[pdTargetKey] = settings.targetDeg
            preferences[pdAngleKey] = settings.angleDeg
            preferences[pdGyroKey] = settings.gyroDps
            preferences[pdKpKey] = settings.kp
            preferences[pdKdKey] = settings.kd
        }
    }

    fun robotConfig(context: Context): Flow<RobotConfig> = context.robotSettingsDataStore.data.map { preferences ->
        val defaults = RobotConfig()
        val ids = preferences[motorIdsKey]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 2 } ?: defaults.motorIds
        val signs = preferences[motorSignsKey]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == ids.size } ?: defaults.motorSigns
        val config = RobotConfig(
            axis = runCatching { Axis.valueOf(preferences[axisKey] ?: defaults.axis.name) }.getOrDefault(defaults.axis),
            imuSign = preferences[imuSignKey] ?: defaults.imuSign,
            zeroOffsetDeg = preferences[zeroOffsetKey] ?: defaults.zeroOffsetDeg,
            alpha = preferences[alphaKey] ?: defaults.alpha,
            targetDeg = preferences[targetKey] ?: defaults.targetDeg,
            kp = preferences[kpKey] ?: defaults.kp,
            kd = preferences[kdKey] ?: defaults.kd,
            speedLoopEnabled = preferences[speedLoopEnabledKey] ?: defaults.speedLoopEnabled,
            speedTargetCmPerSec = preferences[speedTargetKey] ?: defaults.speedTargetCmPerSec,
            speedTargetLimitCmPerSec = preferences[speedTargetLimitKey] ?: defaults.speedTargetLimitCmPerSec,
            speedKevDegPerCmPerSec = preferences[speedKevKey] ?: defaults.speedKevDegPerCmPerSec,
            speedLoopRateHz = preferences[speedLoopRateKey] ?: defaults.speedLoopRateHz,
            speedFilterAlpha = preferences[speedFilterAlphaKey] ?: defaults.speedFilterAlpha,
            speedTargetAngleLimitDeg = preferences[speedTargetAngleLimitKey]
                ?: defaults.speedTargetAngleLimitDeg,
            speedIntegralGainDegPerCmPerSecSec = preferences[speedIntegralGainKey]
                ?: defaults.speedIntegralGainDegPerCmPerSecSec,
            speedAbsoluteAngleLimitDeg = preferences[speedAbsoluteAngleLimitKey]
                ?: defaults.speedAbsoluteAngleLimitDeg,
            speedTargetSlewRateDegPerSec = preferences[speedTargetSlewRateKey]
                ?: defaults.speedTargetSlewRateDegPerSec,
            speedFeedbackTimeoutMs = (preferences[speedFeedbackTimeoutKey]
                ?: defaults.speedFeedbackTimeoutMs.toInt()).toLong(),
            yawTargetDegPerSec = preferences[yawTargetKey] ?: defaults.yawTargetDegPerSec,
            yawKpCommandPerDegPerSec = preferences[yawKpKey] ?: defaults.yawKpCommandPerDegPerSec,
            wheelDiameterMm = preferences[wheelDiameterKey] ?: defaults.wheelDiameterMm,
            driveRatio = preferences[driveRatioKey] ?: defaults.driveRatio,
            vmax = preferences[vmaxKey] ?: defaults.vmax,
            motorControlMode = runCatching {
                MotorControlMode.valueOf(preferences[motorControlModeKey] ?: defaults.motorControlMode.name)
            }.getOrDefault(defaults.motorControlMode),
            pwmMax = preferences[pwmMaxKey] ?: defaults.pwmMax,
            motorIds = ids,
            motorSigns = signs,
            torqueLimit = preferences[torqueLimitKey] ?: defaults.torqueLimit,
            imuTimeoutMs = (preferences[imuTimeoutKey] ?: defaults.imuTimeoutMs.toInt()).toLong(),
            fallAngleDeg = preferences[fallAngleKey] ?: defaults.fallAngleDeg,
            fallDurationMs = (preferences[fallDurationKey] ?: defaults.fallDurationMs.toInt()).toLong(),
            manualTimeoutMs = (preferences[manualTimeoutKey] ?: defaults.manualTimeoutMs.toInt()).toLong(),
            inhibitSafetyAutoDisarm = preferences[inhibitSafetyAutoDisarmKey]
                ?: defaults.inhibitSafetyAutoDisarm,
        )
        if (RobotConfigValidator.validate(config).isValid) config else defaults
    }

    suspend fun saveRobotConfig(context: Context, config: RobotConfig) {
        require(RobotConfigValidator.validate(config).isValid) { "Configuration robot invalide" }
        context.robotSettingsDataStore.edit { preferences ->
            preferences[axisKey] = config.axis.name
            preferences[imuSignKey] = config.imuSign
            preferences[zeroOffsetKey] = config.zeroOffsetDeg
            preferences[alphaKey] = config.alpha
            preferences[targetKey] = config.targetDeg
            preferences[kpKey] = config.kp
            preferences[kdKey] = config.kd
            preferences[speedLoopEnabledKey] = config.speedLoopEnabled
            preferences[speedTargetKey] = config.speedTargetCmPerSec
            preferences[speedTargetLimitKey] = config.speedTargetLimitCmPerSec
            preferences[speedKevKey] = config.speedKevDegPerCmPerSec
            preferences[speedLoopRateKey] = config.speedLoopRateHz
            preferences[speedFilterAlphaKey] = config.speedFilterAlpha
            preferences[speedTargetAngleLimitKey] = config.speedTargetAngleLimitDeg
            preferences[speedIntegralGainKey] = config.speedIntegralGainDegPerCmPerSecSec
            preferences[speedAbsoluteAngleLimitKey] = config.speedAbsoluteAngleLimitDeg
            preferences[speedTargetSlewRateKey] = config.speedTargetSlewRateDegPerSec
            preferences[speedFeedbackTimeoutKey] = config.speedFeedbackTimeoutMs.toInt()
            preferences[yawTargetKey] = config.yawTargetDegPerSec
            preferences[yawKpKey] = config.yawKpCommandPerDegPerSec
            preferences[wheelDiameterKey] = config.wheelDiameterMm
            preferences[driveRatioKey] = config.driveRatio
            preferences[vmaxKey] = config.vmax
            preferences[motorControlModeKey] = config.motorControlMode.name
            preferences[pwmMaxKey] = config.pwmMax
            preferences[motorIdsKey] = config.motorIds.joinToString(",")
            preferences[motorSignsKey] = config.motorSigns.joinToString(",")
            preferences[torqueLimitKey] = config.torqueLimit
            preferences[imuTimeoutKey] = config.imuTimeoutMs.toInt()
            preferences[fallAngleKey] = config.fallAngleDeg
            preferences[fallDurationKey] = config.fallDurationMs.toInt()
            preferences[manualTimeoutKey] = config.manualTimeoutMs.toInt()
            preferences[inhibitSafetyAutoDisarmKey] = config.inhibitSafetyAutoDisarm
        }
    }
}

data class PdSimulationSettings(
    val targetDeg: Double = 0.0,
    val angleDeg: Double = 5.0,
    val gyroDps: Double = 0.0,
    val kp: Double = 10.0,
    val kd: Double = 0.5,
)
