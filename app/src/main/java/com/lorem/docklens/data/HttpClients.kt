package com.lorem.docklens.data

import android.annotation.SuppressLint
import android.util.Log
import com.lorem.docklens.BuildConfig
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Single place where every OkHttp client in the app is built.
 *
 * Both the Hugging Face catalog (Retrofit) and [ModelDownloadWorker] previously
 * created their own clients with slightly different settings, which meant a TLS
 * failure was handled in one path and reported as "no internet" in the other.
 */
object HttpClients {

    private const val TAG = "HttpClients"

    /** Catalog / metadata calls. Carries the Hugging Face bearer token. */
    val api: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HuggingFaceAuthInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Model file downloads. Auth is added per-request by the worker because the
     * token may come from WorkManager input data in a freshly restarted process.
     */
    val download: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Debug-only client that accepts any server certificate.
     *
     * Returns `null` in release builds, so there is no code path in a shipped APK
     * that can skip certificate validation. It exists purely so a developer behind
     * an intercepting proxy (Charles/Proxyman/corporate VPN) or on a captive-portal
     * hotspot can still pull models instead of being told "no internet".
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    fun relaxedTls(base: OkHttpClient): OkHttpClient? {
        if (!BuildConfig.DEBUG) return null

        Log.w(TAG, "Falling back to a debug-only client that does NOT verify TLS certificates.")

        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String
            ) = Unit

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }

        return base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}

/**
 * True when the failure is "the certificate chain could not be validated" rather
 * than "the network is down".
 *
 * [SSLHandshakeException] extends `IOException`, so without this check a MITM
 * proxy or a wrong device clock is misreported to the user as being offline.
 */
internal fun Throwable.isTlsTrustFailure(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 10) {
        when (current) {
            is SSLHandshakeException,
            is CertificateException,
            is CertPathValidatorException -> return true

            is SSLException ->
                if (current.message?.contains("trust anchor", ignoreCase = true) == true) return true
        }
        current = current.cause
        depth++
    }
    return false
}

/** Human-readable guidance shown whenever [isTlsTrustFailure] is true. */
internal const val TLS_TRUST_ERROR =
    "Secure connection to Hugging Face failed: the server certificate could not be verified. " +
        "This is usually a VPN/proxy intercepting HTTPS, a captive-portal or hotspot login page, " +
        "or a wrong date & time on the device. Check the clock, disable any proxy/VPN, " +
        "or switch networks and retry."

