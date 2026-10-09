package com.example.andriodfypprototype.data.net

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The calls the field app makes (backend/README.md, "Field app endpoints"). Everything a screen
 * shows comes from the offline cache; the network is used only to sign in and to sync.
 */
interface WeedReaverApi {

    @POST("auth/login")
    suspend fun login(@Body body: LoginIn): TokenOut

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshIn): TokenOut

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshIn)

    @GET("sync/pull")
    suspend fun pull(@Query("since") since: String?): SyncPullOut

    @POST("sync/push")
    suspend fun push(@Body body: SyncPush): SyncPushOut

    @GET("reference")
    suspend fun reference(): ReferenceDto

    @Multipart
    @PUT("scans/{id}/photo")
    suspend fun putScanPhoto(@Path("id") scanId: String, @Part file: MultipartBody.Part): ScanDto

    @POST("devices/{id}/heartbeat")
    suspend fun heartbeat(@Path("id") deviceId: String, @Body body: Heartbeat)
}
