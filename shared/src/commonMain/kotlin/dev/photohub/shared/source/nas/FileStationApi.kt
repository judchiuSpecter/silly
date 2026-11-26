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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Small client for QTS's File Station web API.
 *
 * This replaces both slow paths that made the 96k-photo NAS source fragile:
 * one batched SSH `find` no longer has to hold a socket open for minutes, and
 * grid thumbnails no longer download multi-megabyte originals. File Station
 * returns compact JSON pages (2,000 files per request) and a server-rendered
 * JPEG for each image. Photo Station itself is disabled on this NAS; File
 * Station is enabled and is part of QTS.
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
    private val json = Json { ignoreUnknownKeys = true }
    private val loginLock = Mutex()
    private var sid: String? = null

    /** One page of a directory. `total` is the directory's full item count. */
    suspend fun list(path: String, start: Int, limit: Int): FileList {
        val token = session() ?: error("Not connected to NAS File Station.")
        return requestList(token, path, start, limit)
            ?: run {
                // A stale SID is the common reason for a sudden 403. Log in
                // once more and retry this single page before failing.
                invalidate(token)
                val fresh = session() ?: error("Not connected to NAS File Station.")
                requestList(fresh, path, start, limit)
                    ?: error("File Station listing failed for $path")
            }
    }

    /** Returns a server-rendered JPEG, or null if the API is unavailable. */
    suspend fun thumbnail(path: String, name: String, size: Int = 320): ByteArray? {
        val token = session() ?: return null
        val bytes = requestThumb(token, path, name, size)
        if (bytes != null) return bytes

        invalidate(token)
        val fresh = session() ?: return null
        return requestThumb(fresh, path, name, size)
    }

    private suspend fun requestList(
        token: String,
        path: String,
        start: Int,
        limit: Int,
    ): FileList? = try {
        val response = client.get("$root/cgi-bin/filemanager/utilRequest.cgi") {
            parameter("func", "get_list")
            parameter("sid", token)
            parameter("is_iso", "0")
            parameter("list_mode", "all")
            parameter("path", path)
            parameter("limit", limit.toString())
            parameter("start", start.toString())
            parameter("sort", "mt")
            parameter("dir", "DESC")
        }
        if (response.status.value != 200) return null
        val body = response.bodyAsText()
        if (body.isBlank() || body.trimStart().startsWith("{")) return null
        return runCatching { json.decodeFromString<FileList>(body) }.getOrNull()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        println("[fileStation] list $path: ${error::class.simpleName}: ${error.message}")
        null
    }

    private suspend fun requestThumb(
        token: String,
        path: String,
        name: String,
        size: Int,
    ): ByteArray? {
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
    private suspend fun login(): String? = try {
        val secret = password() ?: return null
        val encoded = Base64.Default.encode(secret.encodeToByteArray())
        val response = client.post("$root/cgi-bin/authLogin.cgi") {
            parameter("user", username)
            parameter("pwd", encoded)
        }
        if (response.status.value != 200) return null
        val xml = response.bodyAsText()
        SID_REGEX.find(xml)?.groupValues?.get(1)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        println("[fileStation] login: ${error::class.simpleName}: ${error.message}")
        null
    }

    private fun invalidate(token: String) {
        if (sid == token) sid = null
    }

    @Serializable
    data class FileList(
        val total: Int = 0,
        val datas: List<FileEntry> = emptyList(),
    )

    @Serializable
    data class FileEntry(
        val filename: String = "",
        val isfolder: Int = 0,
        val filesize: String = "0",
        val epochmt: Long = 0L,
    )

    private companion object {
        val SID_REGEX = Regex("<authSid><!\\[CDATA\\[(.*?)\\]\\]>")
    }
}
