package dev.photohub.shared.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.util.concurrent.TimeUnit

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
