package dev.photohub.shared.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

actual fun createHttpClient(): HttpClient = HttpClient(Java) {
    // Failures are surfaced as responses and classified by DriveApi, which
    // knows which statuses are retryable; throwing would lose that detail.
    expectSuccess = false

    install(HttpTimeout) {
        // Long enough for a large chunk on a slow link, short enough that a
        // dead connection does not stall a sync indefinitely.
        requestTimeoutMillis = 5 * 60 * 1000
        // An offline NAS/cloud must fail fast instead of holding the spinner.
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 60_000
    }
}

actual fun createNasHttpClient(): HttpClient {
    // QTS ships a self-signed certificate by default. The user opted into this
    // NAS by entering its address and password; other sources keep strict TLS.
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
    val ssl = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    }
    return HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 45_000
            socketTimeoutMillis = 45_000
        }
        engine {
            config {
                sslSocketFactory(ssl.socketFactory, trustAll)
                hostnameVerifier { _, _ -> true }
                retryOnConnectionFailure(true)
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(45, TimeUnit.SECONDS)
                writeTimeout(45, TimeUnit.SECONDS)
            }
        }
    }
}
