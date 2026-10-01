package com.woozie.balancingrobot.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.woozie.balancingrobot.domain.sensor.ImuRatePolicy
import com.woozie.balancingrobot.domain.model.Axis
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
    private val vmaxKey = intPreferencesKey("vmax")
    private val motorIdsKey = stringPreferencesKey("motor_ids")
    private val motorSignsKey = stringPreferencesKey("motor_signs")
    private val torqueLimitKey = intPreferencesKey("torque_limit")
    private val imuTimeoutKey = intPreferencesKey("imu_timeout_ms")
    private val fallAngleKey = doublePreferencesKey("fall_angle_deg")
    private val fallDurationKey = intPreferencesKey("fall_duration_ms")
    private val manualTimeoutKey = intPreferencesKey("manual_timeout_ms")

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
            vmax = preferences[vmaxKey] ?: defaults.vmax,
            motorIds = ids,
            motorSigns = signs,
            torqueLimit = preferences[torqueLimitKey] ?: defaults.torqueLimit,
            imuTimeoutMs = (preferences[imuTimeoutKey] ?: defaults.imuTimeoutMs.toInt()).toLong(),
            fallAngleDeg = preferences[fallAngleKey] ?: defaults.fallAngleDeg,
            fallDurationMs = (preferences[fallDurationKey] ?: defaults.fallDurationMs.toInt()).toLong(),
            manualTimeoutMs = (preferences[manualTimeoutKey] ?: defaults.manualTimeoutMs.toInt()).toLong(),
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
            preferences[vmaxKey] = config.vmax
            preferences[motorIdsKey] = config.motorIds.joinToString(",")
            preferences[motorSignsKey] = config.motorSigns.joinToString(",")
            preferences[torqueLimitKey] = config.torqueLimit
            preferences[imuTimeoutKey] = config.imuTimeoutMs.toInt()
            preferences[fallAngleKey] = config.fallAngleDeg
            preferences[fallDurationKey] = config.fallDurationMs.toInt()
            preferences[manualTimeoutKey] = config.manualTimeoutMs.toInt()
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
