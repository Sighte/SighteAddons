package sighteaddons

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Uploading the session that is still being written would ship a truncated log and then move the
 * open file away, so the selection rule is the one part of the uploader worth pinning.
 */
class TelemetryUploadTest {
    @Test
    fun `only sessions older than this process are uploaded`() {
        assertTrue(TelemetryUpload.finished("session-1786530882102.jsonl", 1786530882103))
        assertFalse(TelemetryUpload.finished("session-1786530882102.jsonl", 1786530882102))
        assertFalse(TelemetryUpload.finished("session-1786530882999.jsonl", 1786530882102))
    }

    @Test
    fun `anything that is not a session file is left alone`() {
        assertFalse(TelemetryUpload.finished("uploaded", Long.MAX_VALUE))
        assertFalse(TelemetryUpload.finished("history.jsonl", Long.MAX_VALUE))
        assertFalse(TelemetryUpload.finished("session-.jsonl", Long.MAX_VALUE))
        assertFalse(TelemetryUpload.finished("session-abc.jsonl", Long.MAX_VALUE))
    }
}
