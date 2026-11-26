package dev.photohub.shared.source.nas

import dev.photohub.shared.platform.createNasHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small client for QTS's File Station web API.
 *
 * QNAP exposes a server-side `get_thumb` endpoint that returns a JPEG without
 * downloading the original over SFTP. That is dramatically cheaper for the
 * 94k-image archive: an 18 KB request instead of a multi-megabyte one. Photo
 * Station itself is disabled on this NAS, but File Station is enabled and its
 * API is part of QTS.
 *
 * The HTTPS certificate is self-signed; [createNasHttpClient] is the dedicated
 * relaxed client for this one host. Cloud sources still verify TLS normally.
 */
class FileStationApi(
    baseUrl: String,
    private val username: String,
    private val password: () -> String?,
    private val client: HttpClient = createNasHttpClient(),
) {
    private val root = baseUrl.trimEnd('/')
    private val loginLock = Mutex()
    private var sid: String? = null

    /** Returns a server-rendered JPEG, or null if the API is unavailable. */
    suspend fun thumbnail(path: String, name: String, size: Int = 320): ByteArray? {
        val token = session() ?: return null
        val bytes = requestThumb(token, path, name, size)
        if (bytes != null) return bytes

        // A stale SID is the common reason for a sudden 403. Log in once more
        // and retry this single thumbnail before giving up.
        invalidate(token)
        val fresh = session() ?: return null
        return requestThumb(fresh, path, name, size)
    }

    private suspend fun requestThumb(token: String, path: String, name: String, size: Int): ByteArray? {
        val response = client.get("$root/cgi-bin/filemanager/utilRequest.cgi") {
            parameter("func", "get_thumb")
            parameter("sid", token)
            parameter("path", path)
            parameter("name", name)
            parameter("size", size.toString())
        }
        if (response.status.value != 200) return null
        val bytes = response.bodyAsBytes()
        // QTS returns a tiny blank placeholder for videos and a JSON error
        // object on some failures. Both are useless as a grid thumbnail;
        // falling back to the SFTP renderer handles videos correctly.
        if (bytes.size < 512 || bytes.firstOrNull() == '{'.code.toByte()) return null
        return bytes
    }

    private suspend fun session(): String? {
        sid?.let { return it }
        return loginLock.withLock {
            sid ?: login().also { sid = it }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun login(): String? {
        val secret = password() ?: return null
        val encoded = Base64.Default.encode(secret.encodeToByteArray())
        val response = client.post("$root/cgi-bin/authLogin.cgi") {
            parameter("user", username)
            parameter("pwd", encoded)
        }
        if (response.status.value != 200) return null
        val xml = response.bodyAsText()
        return SID_REGEX.find(xml)?.groupValues?.get(1)
    }

    private fun invalidate(token: String) {
        if (sid == token) sid = null
    }

    private companion object {
        val SID_REGEX = Regex("<authSid><!\\[CDATA\\[(.*?)\\]\\]>")
    }
}
