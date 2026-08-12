package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Uploading the session that is still being written would ship a truncated log and then move the
 * open file away, so the selection rule is the one part of the uploader worth pinning. The config
 * parse gets the same treatment: a typo there silently turns telemetry off.
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

    /** The name the receiver stores under. A decorated one is rejected with 400, so it must not pass. */
    @Test
    fun `a decorated session name is not a session`() {
        assertFalse(TelemetryUpload.finished("session-1765432109876-0.3.0.jsonl", Long.MAX_VALUE))
    }

    @Test
    fun `both keys read, trailing slash and whitespace dropped`(@TempDir dir: Path) {
        val file = dir.resolve("upload.properties")
        file.writeText(
            """
            # the token stays on the machine that plays
            url = http://192.0.2.1:8420/
            token =  not-the-real-one
            """.trimIndent(),
        )
        assertEquals("http://192.0.2.1:8420" to "not-the-real-one", TelemetryUpload.credentials(file))
    }

    @Test
    fun `half a config is no config`(@TempDir dir: Path) {
        val noToken = dir.resolve("no-token.properties")
        noToken.writeText("url=http://192.0.2.1:8420")
        assertNull(TelemetryUpload.credentials(noToken))

        val noUrl = dir.resolve("no-url.properties")
        noUrl.writeText("token=not-the-real-one")
        assertNull(TelemetryUpload.credentials(noUrl))

        val blank = dir.resolve("blank.properties")
        blank.writeText("url=\ntoken=   \n")
        assertNull(TelemetryUpload.credentials(blank))
    }

    /** The normal state for anyone who is not the author: no file, no upload, no complaint. */
    @Test
    fun `an absent config is no config`(@TempDir dir: Path) {
        assertNull(TelemetryUpload.credentials(dir.resolve("upload.properties")))
    }
}
