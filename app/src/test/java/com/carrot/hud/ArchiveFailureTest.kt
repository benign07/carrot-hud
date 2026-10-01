package com.carrot.hud

import java.io.IOException
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test

class ArchiveFailureTest {
    @Test fun localDirectoryFailureBeforeHttpDoesNotMeanDisconnected() {
        val text = ArchiveFailure.message(IOException("disk unavailable"), ArchiveStage.STORAGE, false, 3)
        assertTrue(text.startsWith("휴대폰 기록 저장 오류"))
        assertFalse(text.startsWith("오파 연결 대기"))
    }
    @Test fun vanishedDeviceMidPassIsConnectionWaitEvenAfterAnEarlierResponse() {
        assertTrue(ArchiveFailure.message(ChunkConnectionException(IOException()), ArchiveStage.DOWNLOAD, true, 3).startsWith("오파 연결 대기"))
    }
    @Test fun downloadErrorsHaveTypedServerStorageAndIntegrityReasons() {
        assertTrue(ArchiveFailure.message(ChunkServerException(500), ArchiveStage.DOWNLOAD, true, 3).startsWith("기기 기록 응답 오류"))
        assertTrue(ArchiveFailure.message(ChunkStorageException(IOException()), ArchiveStage.DOWNLOAD, true, 3).startsWith("휴대폰 기록 저장 오류"))
        assertTrue(ArchiveFailure.message(ChunkIntegrityException("Hash"), ArchiveStage.DOWNLOAD, true, 3).startsWith("기록 무결성 확인 실패"))
    }
    @Test fun networkFailureBeforeAnyResponseIsConnectionWait() {
        assertTrue(ArchiveFailure.message(IOException(), ArchiveStage.INDEX, false, 3).startsWith("오파 연결 대기"))
    }
    @Test fun respondingServerWithBadIndexIsNotOffline() {
        for (error in listOf(JSONException("bad json"), IllegalArgumentException("HTTP 500"), IOException("body interrupted"))) {
            val text = ArchiveFailure.message(error, ArchiveStage.INDEX, true, 3)
            assertTrue(text.startsWith("기기 기록 목록 확인 실패"))
            assertFalse(text.startsWith("오파 연결 대기"))
        }
    }
    @Test fun integrityFailureKeepsPreviouslyArchivedRecordsAndPcStatus() {
        val text = ArchiveFailure.message(IllegalStateException("hash mismatch"), ArchiveStage.DOWNLOAD, true, 3)
        assertTrue(text.startsWith("기록 무결성 확인 실패"))
        assertTrue(text.contains("휴대폰 보관 3 개")); assertTrue(text.contains("PC 전송은 별도 진행"))
    }
    @Test fun localManifestWriteFailureIsAStorageError() {
        assertTrue(ArchiveFailure.message(IOException("disk full"), ArchiveStage.MANIFEST, true, 3).startsWith("휴대폰 기록 저장 오류"))
    }
    @Test fun ambiguousDownloadIoDoesNotClaimTheVehicleIsOffline() {
        assertTrue(ArchiveFailure.message(IOException(), ArchiveStage.DOWNLOAD, true, 3).startsWith("기록 보관/전송 오류"))
    }
}
