package com.example.andriodfypprototype.data.net

import com.example.andriodfypprototype.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A failure the station answered with, read from its `{"error": {code, message}}` envelope. */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message) {
    /** The session cannot be recovered by refreshing; the operator must sign in again. */
    val endsSession: Boolean get() = code in SESSION_ENDING

    companion object {
        val SESSION_ENDING = setOf(
            "refresh_token_reused", "refresh_token_expired", "invalid_refresh_token", "account_disabled", "token_revoked"
        )
    }
}

/** No route to the station: the phone is offline or the server is down. Never an error the operator caused. */
class OfflineException(cause: Throwable) : IOException("The station could not be reached", cause)

val WrJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

private val JSON_TYPE = "application/json".toMediaType()

fun parseError(status: Int, body: String?): ApiException {
    val env = body?.let { runCatching { WrJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull() }
    return ApiException(status, env?.error?.code ?: "http_$status", env?.error?.message ?: "The station answered $status")
}

/** Runs a call and turns transport and HTTP failures into [OfflineException] / [ApiException]. */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    throw parseError(e.code(), e.response()?.errorBody()?.string())
} catch (e: ApiException) {
    throw e
} catch (e: IOException) {
    throw if (e is OfflineException) e else OfflineException(e)
}

class Network(private val store: SecureStore, private val onSessionEnded: () -> Unit) {

    private val baseUrl = BuildConfig.API_BASE_URL

    /** Bearer token and the handset id on every call. */
    private val headers = Interceptor { chain ->
        val b = chain.request().newBuilder().header("Accept", "application/json")
        store.accessToken?.let { b.header("Authorization", "Bearer $it") }
        store.deviceId?.let { b.header("X-Device-ID", it) }
        chain.proceed(b.build())
    }

    /** Plain client for /auth/refresh, so a refresh never recurses into the authenticator. */
    private val bare = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * On `token_expired` the access token is rotated once and the request replayed. A refresh
     * token that was already used, has expired, or belongs to a disabled account ends the
     * session; the operator's unsynced changes stay on the phone for the next sign-in.
     */
    private val refresher = Authenticator { _: Route?, response: Response ->
        if (response.request.header("X-Retried-Auth") != null) return@Authenticator null
        val code = runCatching { parseError(response.code, response.peekBody(8_192).string()).code }.getOrNull()
        if (code != "token_expired") {
            if (code != null && code in ApiException.SESSION_ENDING) endSession()
            return@Authenticator null
        }
        val fresh = refreshAccessToken(response.request.header("Authorization")) ?: return@Authenticator null
        response.request.newBuilder()
            .header("Authorization", "Bearer $fresh")
            .header("X-Retried-Auth", "1")
            .build()
    }

    @Synchronized
    private fun refreshAccessToken(sentAuth: String?): String? {
        // Another call refreshed while this one waited: use its token rather than rotating again,
        // which the station would treat as refresh-token reuse.
        val current = store.accessToken
        if (current != null && sentAuth != "Bearer $current") return current
        val refresh = store.refreshToken ?: return null
        val body = WrJson.encodeToString(RefreshIn.serializer(), RefreshIn(refresh)).toRequestBody(JSON_TYPE)
        val req = Request.Builder().url(baseUrl + "auth/refresh").post(body).build()
        return try {
            bare.newCall(req).execute().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful) {
                    if (parseError(r.code, text).endsSession) endSession()
                    return null
                }
                val t = WrJson.decodeFromString(TokenOut.serializer(), text)
                store.saveTokens(t.accessToken, t.refreshToken)
                t.accessToken
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun endSession() {
        store.clearSession()
        onSessionEnded()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(headers)
        .authenticator(refresher)
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        .build()

    val api: WeedReaverApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(WrJson.asConverterFactory(JSON_TYPE))
        .build()
        .create(WeedReaverApi::class.java)
}
