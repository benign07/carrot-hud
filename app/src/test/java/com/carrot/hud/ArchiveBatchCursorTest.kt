package com.carrot.hud

import org.junit.Assert.*
import org.junit.Test

class ArchiveBatchCursorTest {
    @Test fun failedPrefixCannotStarveLaterChunksAndEachPassIsBounded() {
        val cursor = ArchiveBatchCursor(); val batches = mutableListOf<List<Int>>()
        repeat(3) {
            val attempted = mutableListOf<Int>()
            cursor.run(13) { index -> attempted.add(index); ArchiveBatchCursor.Visit.ATTEMPTED }
            batches.add(attempted)
        }
        assertEquals(listOf(0,1,2,3,4,5), batches[0])
        assertEquals(listOf(6,7,8,9,10,11), batches[1])
        assertEquals(listOf(12,0,1,2,3,4), batches[2])
    }
    @Test fun cachedRecordsDoNotConsumeNetworkBudgetAndThrownIoStillAdvances() {
        val cursor = ArchiveBatchCursor()
        assertThrows(java.io.IOException::class.java) { cursor.run(4) { throw java.io.IOException() } }
        val seen = mutableListOf<Int>()
        cursor.run(4, 1) { index -> seen.add(index); if (index == 1) ArchiveBatchCursor.Visit.CACHED else ArchiveBatchCursor.Visit.ATTEMPTED }
        assertEquals(listOf(1,2), seen)
    }
    @Test fun localRevalidationOfHundredsOfRecordsDoesNotDelayNewDownloads() {
        val cursor = ArchiveBatchCursor(); val local = mutableListOf<Int>(); val requests = mutableListOf<Int>()
        cursor.run(513) { index ->
            if (index < 500) { local.add(index); ArchiveBatchCursor.Visit.CACHED }
            else { requests.add(index); ArchiveBatchCursor.Visit.ATTEMPTED }
        }
        assertEquals(500, local.size)
        assertEquals(listOf(500,501,502,503,504,505), requests)
    }
    @Test fun stopCancellationAndChangedListRemainBounded() {
        val cursor = ArchiveBatchCursor(); var calls = 0
        cursor.run(0) { calls++; ArchiveBatchCursor.Visit.ATTEMPTED }
        cursor.run(10, cancelled = { true }) { calls++; ArchiveBatchCursor.Visit.ATTEMPTED }
        assertEquals(0, calls)
        cursor.run(10) { calls++; ArchiveBatchCursor.Visit.STOP }
        assertEquals(1, calls)
        val seen = mutableListOf<Int>()
        cursor.run(2) { index -> seen.add(index); ArchiveBatchCursor.Visit.ATTEMPTED }
        assertEquals(listOf(1,0), seen)
    }
}
