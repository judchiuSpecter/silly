package dev.photohub.shared.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    // DriveApi classifies statuses into retryable vs permanent, so responses
    // must reach it rather than being turned into exceptions here.
    expectSuccess = false

    install(HttpTimeout) {
        requestTimeoutMillis = 5 * 60 * 1000
        // Offline devices must fail fast: a long connect wait keeps the
        // refresh spinner up even though no source can be reached.
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 60_000
    }

    engine {
        config {
            // Mobile networks drop connections constantly; letting OkHttp retry
            // a connection-level failure avoids surfacing noise as upload errors.
            retryOnConnectionFailure(true)
            connectTimeout(5, TimeUnit.SECONDS)
            readTimeout(60, TimeUnit.SECONDS)
            writeTimeout(60, TimeUnit.SECONDS)
        }
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
            // Rogers hairpin connections can take longer than a clean LAN
            // socket; keep this bounded but generous enough for mobile data.
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
