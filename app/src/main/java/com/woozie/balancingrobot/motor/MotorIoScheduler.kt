package com.woozie.balancingrobot.motor

import com.woozie.balancingrobot.domain.model.MotorControlMode
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

data class MotorFrame(
    val values: List<Int>,
    val timestampNs: Long = System.nanoTime(),
    val sequence: Long = 0L,
)

/** A timestamped record of the command that really reached the serial bus. */
data class MotorWriteTrace(
    val sequence: Long,
    val values: List<Int>,
    val submittedAtNs: Long,
    val startedAtNs: Long,
    val finishedAtNs: Long,
)

data class MotorVelocitySnapshot(
    val sequence: Long,
    val values: List<Int>,
    val timestampsNs: List<Long>,
)

data class MotorIoMetrics(
    val writtenFrames: Long = 0,
    val supersededFrames: Long = 0,
    val telemetryFrames: Long = 0,
    val lastWriteLatencyMs: Double? = null,
    val busBusySkips: Long = 0,
    val lastWriteSequence: Long? = null,
    val velocityFeedbackFrames: Long = 0,
    val velocityFeedbackSkips: Long = 0,
    val lastVelocityFeedbackNs: Long? = null,
)

/**
 * Real-time motor I/O scheduler.
 *
 * The writer and telemetry loops have separate executors. They still share
 * one fair lock because the Feetech bus is half-duplex, but telemetry uses
 * tryLock and a short transaction timeout: a slow read can therefore skip a
 * telemetry slot, never block the 200 Hz writer thread.
 */
class MotorIoScheduler(
    private val bus: FeetechBus,
    private val motorIds: List<Int>,
    private val motorSigns: List<Int>,
    private val controlMode: MotorControlMode,
    private val commandLimit: Int,
    private val manualTimeoutMs: Long = 300,
    private val onTelemetry: (List<FeetechTelemetry>) -> Unit,
    private val onFault: (String) -> Unit,
    private val onWrite: (MotorWriteTrace) -> Unit = {},
    private val clockNs: () -> Long = System::nanoTime,
) : Closeable {
    companion object {
        private const val CONTROL_PERIOD_MS = 5L
        private const val VELOCITY_SLOT_MS = 10L
        private const val VELOCITY_TRANSACTION_TIMEOUT_MS = 4
        private const val TELEMETRY_TRANSACTION_TIMEOUT_MS = 10
        private const val HEALTH_READ_EVERY_VISITS = 5
        private const val TELEMETRY_FAILURE_LIMIT = 3
    }

    private val writerExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "robot-motor-writer").apply { isDaemon = true }
    }
    private val telemetryExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "robot-motor-telemetry").apply { isDaemon = true }
    }
    private val busLock = ReentrantLock(true)
    private val latestControlFrame = AtomicReference<MotorFrame?>(null)
    private val latestWriteTrace = AtomicReference<MotorWriteTrace?>(null)
    private val latestVelocitySnapshot = AtomicReference<MotorVelocitySnapshot?>(null)
    private val nextSequence = AtomicLong(0L)
    private val nextVelocitySequence = AtomicLong(0L)
    private val urgentActions = ConcurrentLinkedQueue<() -> Unit>()
    private val started = AtomicBoolean(false)

    @Volatile
    private var armed = false
    @Volatile
    private var writtenFrames = 0L
    @Volatile
    private var supersededFrames = 0L
    @Volatile
    private var telemetryFrames = 0L
    @Volatile
    private var busBusySkips = 0L
    @Volatile
    private var lastWriteLatencyMs: Double? = null
    @Volatile
    private var lastWriteSequence: Long? = null
    @Volatile
    private var velocityFeedbackFrames = 0L
    @Volatile
    private var velocityFeedbackSkips = 0L
    @Volatile
    private var lastVelocityFeedbackNs: Long? = null
    private var telemetryIndex = 0
    private val telemetryCache = ConcurrentHashMap<Int, FeetechTelemetry>()
    private val telemetryFailures = ConcurrentHashMap<Int, Int>()
    private val velocityVisitCounts = ConcurrentHashMap<Int, Int>()
    private val velocityValues = ConcurrentHashMap<Int, Int>()
    private val velocityTimestamps = ConcurrentHashMap<Int, Long>()
    private val velocityVersions = ConcurrentHashMap<Int, Long>()
    private val publishedVelocityVersions = ConcurrentHashMap<Int, Long>()
    @Volatile
    private var telemetryFaultReported = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        writerExecutor.scheduleAtFixedRate(::writeTick, 0, CONTROL_PERIOD_MS, TimeUnit.MILLISECONDS)
        telemetryExecutor.scheduleAtFixedRate(
            ::telemetryTick,
            0,
            VELOCITY_SLOT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun arm(): Boolean {
        if (writerExecutor.isShutdown) return false
        val future = writerExecutor.submit<Boolean> {
            busLock.lock()
            try {
                writeControl(List(motorIds.size) { 0 })
                bus.writeSyncTorqueEnable(motorIds, true)
                armed = true
                telemetryFailures.clear()
                telemetryFaultReported = false
                latestControlFrame.set(null)
                true
            } catch (error: Throwable) {
                onFault("Armement moteur impossible : ${error.message ?: error.javaClass.simpleName}")
                armed = false
                false
            } finally {
                busLock.unlock()
            }
        }
        return runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    fun disarm() {
        if (writerExecutor.isShutdown) return
        latestControlFrame.set(null)
        armed = false
        telemetryFailures.clear()
        telemetryFaultReported = false
        urgentActions += {
            runCatching {
                writeControl(List(motorIds.size) { 0 })
                bus.writeSyncTorqueEnable(motorIds, false)
            }.onFailure { onFault("Désarmement moteur incomplet : ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun submitManual(value: Int, held: Boolean) {
        val bounded = value.coerceIn(-commandLimit, commandLimit)
        latestControlFrame.set(newFrame(
            if (!armed || !held) List(motorIds.size) { 0 }
            else motorSigns.map { sign -> bounded * sign },
        ))
    }

    fun submitControl(values: List<Int>) {
        latestControlFrame.set(newFrame(
            if (!armed || values.size != motorIds.size) List(motorIds.size) { 0 }
            else values.map { it.coerceIn(-commandLimit, commandLimit) },
        ))
    }

    fun safetyStop() = disarm()

    fun lastWriteTrace(): MotorWriteTrace? = latestWriteTrace.get()

    fun velocitySnapshot(): MotorVelocitySnapshot? = latestVelocitySnapshot.get()

    fun metrics(): MotorIoMetrics = MotorIoMetrics(
        writtenFrames = writtenFrames,
        supersededFrames = supersededFrames,
        telemetryFrames = telemetryFrames,
        lastWriteLatencyMs = lastWriteLatencyMs,
        busBusySkips = busBusySkips,
        lastWriteSequence = lastWriteSequence,
        velocityFeedbackFrames = velocityFeedbackFrames,
        velocityFeedbackSkips = velocityFeedbackSkips,
        lastVelocityFeedbackNs = lastVelocityFeedbackNs,
    )

    private fun newFrame(values: List<Int>): MotorFrame = MotorFrame(
        values = values,
        timestampNs = clockNs(),
        sequence = nextSequence.incrementAndGet(),
    )

    private fun writeTick() {
        if (writerExecutor.isShutdown) return
        processUrgentActions()
        if (!armed) return

        val frame = latestControlFrame.get()
        val output = if (frame != null &&
            clockNs() - frame.timestampNs <= manualTimeoutMs * 1_000_000L
        ) frame.values else List(motorIds.size) { 0 }

        if (!busLock.tryLock()) {
            busBusySkips++
            return
        }
        try {
            val startedNs = clockNs()
            writeControl(output)
            val finishedNs = clockNs()
            val writeTrace = MotorWriteTrace(
                sequence = frame?.sequence ?: 0L,
                values = output,
                submittedAtNs = frame?.timestampNs ?: 0L,
                startedAtNs = startedNs,
                finishedAtNs = finishedNs,
            )
            latestWriteTrace.set(writeTrace)
            onWrite(writeTrace)
            writtenFrames++
            lastWriteSequence = writeTrace.sequence.takeIf { it != 0L }
            lastWriteLatencyMs = (finishedNs - startedNs) / 1_000_000.0
            if (frame != null && frame.sequence != latestControlFrame.get()?.sequence) {
                supersededFrames++
            }
        } catch (error: Throwable) {
            onFault("Écriture ${controlMode.label} impossible : ${error.message ?: error.javaClass.simpleName}")
        } finally {
            busLock.unlock()
        }
    }

    private fun processUrgentActions() {
        while (true) {
            val urgent = urgentActions.poll() ?: break
            busLock.lock()
            try {
                urgent()
            } catch (error: Throwable) {
                onFault("Action moteur impossible : ${error.message ?: error.javaClass.simpleName}")
            } finally {
                busLock.unlock()
            }
        }
    }

    private fun telemetryTick() {
        if (telemetryExecutor.isShutdown || motorIds.isEmpty()) return
        val id = motorIds[telemetryIndex++ % motorIds.size]
        val visit = (velocityVisitCounts[id] ?: 0) + 1
        velocityVisitCounts[id] = visit
        val healthRead = visit == 1 || (visit - 1) % HEALTH_READ_EVERY_VISITS == 0
        if (!busLock.tryLock()) {
            velocityFeedbackSkips++
            return
        }
        val telemetry: FeetechTelemetry?
        val velocity: Int?
        try {
            if (healthRead) {
                telemetry = runCatching {
                    bus.readTelemetry(id, TELEMETRY_TRANSACTION_TIMEOUT_MS)
                }.getOrNull()
                velocity = telemetry?.velocity
            } else {
                telemetry = null
                velocity = runCatching {
                    bus.readVelocity(id, VELOCITY_TRANSACTION_TIMEOUT_MS)
                }.getOrNull()
            }
        } finally {
            busLock.unlock()
        }

        if (velocity != null) {
            val sampleTimestampNs = clockNs()
            velocityValues[id] = velocity
            velocityTimestamps[id] = sampleTimestampNs
            velocityVersions[id] = (velocityVersions[id] ?: 0L) + 1L
            publishVelocityPairIfComplete()
        } else {
            velocityFeedbackSkips++
        }

        if (healthRead && telemetry != null) {
            telemetryCache[id] = telemetry
            telemetryFailures[id] = 0
            if (motorIds.all { telemetryCache.containsKey(it) }) {
                telemetryFrames++
                onTelemetry(motorIds.mapNotNull { telemetryCache[it] })
            }
            return
        }

        if (!healthRead) return

        val failures = (telemetryFailures[id] ?: 0) + 1
        telemetryFailures[id] = failures
        if (armed && failures >= TELEMETRY_FAILURE_LIMIT && !telemetryFaultReported) {
            telemetryFaultReported = true
            onFault("Moteur attendu silencieux : arrêt du groupe (ID $id, $failures lectures manquées)")
        }
    }

    private fun publishVelocityPairIfComplete() {
        if (motorIds.any { id ->
                velocityValues[id] == null || velocityTimestamps[id] == null ||
                    (velocityVersions[id] ?: 0L) <= (publishedVelocityVersions[id] ?: 0L)
            }
        ) return
        val sequence = nextVelocitySequence.incrementAndGet()
        val timestamps = motorIds.map { id -> checkNotNull(velocityTimestamps[id]) }
        val snapshot = MotorVelocitySnapshot(
            sequence = sequence,
            values = motorIds.map { id -> checkNotNull(velocityValues[id]) },
            timestampsNs = timestamps,
        )
        motorIds.forEach { id -> publishedVelocityVersions[id] = checkNotNull(velocityVersions[id]) }
        latestVelocitySnapshot.set(snapshot)
        velocityFeedbackFrames++
        lastVelocityFeedbackNs = timestamps.maxOrNull()
    }

    private fun writeControl(values: List<Int>): Int = when (controlMode) {
        MotorControlMode.VELOCITY -> bus.writeSyncVelocity(motorIds, values)
        MotorControlMode.PWM -> bus.writeSyncPwm(motorIds, values)
    }

    override fun close() {
        if (!writerExecutor.isShutdown) {
            disarm()
            writerExecutor.shutdown()
            telemetryExecutor.shutdown()
            runCatching { writerExecutor.awaitTermination(2, TimeUnit.SECONDS) }
            runCatching { telemetryExecutor.awaitTermination(2, TimeUnit.SECONDS) }
            writerExecutor.shutdownNow()
            telemetryExecutor.shutdownNow()
        }
    }
}
