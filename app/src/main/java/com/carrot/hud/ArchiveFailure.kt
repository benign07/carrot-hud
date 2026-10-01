package com.carrot.hud

import java.io.IOException
import org.json.JSONException

enum class ArchiveStage { STORAGE, INDEX, DOWNLOAD, MANIFEST }

/** Error labels distinguish transport reachability from local record integrity. */
object ArchiveFailure {
    fun message(error: Exception, stage: ArchiveStage, deviceResponded: Boolean, count: Int): String {
        val reason = when {
            stage == ArchiveStage.STORAGE || stage == ArchiveStage.MANIFEST -> "휴대폰 기록 저장 오류 · 재시도 대기"
            error is ChunkStorageException -> "휴대폰 기록 저장 오류 · 재시도 대기"
            error is ChunkServerException -> "기기 기록 응답 오류 · 재시도 대기"
            error is ChunkIntegrityException -> "기록 무결성 확인 실패 · 재시도 대기"
            !deviceResponded && error is IOException -> "오파 연결 대기"
            error is JSONException || stage == ArchiveStage.INDEX -> "기기 기록 목록 확인 실패 · 재시도 대기"
            error is IllegalArgumentException || error is IllegalStateException -> "기록 무결성 확인 실패 · 재시도 대기"
            else -> "기록 보관/전송 오류 · 재시도 대기"
        }
        return "$reason · 휴대폰 보관 $count 개 · PC 전송은 별도 진행"
    }
}
