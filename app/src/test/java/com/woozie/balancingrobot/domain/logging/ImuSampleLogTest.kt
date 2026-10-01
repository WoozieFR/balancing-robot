package com.woozie.balancingrobot.domain.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class ImuSampleLogTest {
    @Test
    fun ringKeepsNewestRecordsAndExportsCsv() {
        val log = ImuSampleLog(capacity = 2)
        log.append(ImuLogRecord(1L, 1.0, 1.1, 2.0, 0.01))
        log.append(ImuLogRecord(2L, 2.0, 2.1, 3.0, 0.01))
        log.append(ImuLogRecord(3L, null, null, 4.0, 0.02))

        assertEquals(listOf(2L, 3L), log.snapshot().map { it.timestampNs })
        assertEquals(3, log.toCsv().trim().lines().size)
    }
}
