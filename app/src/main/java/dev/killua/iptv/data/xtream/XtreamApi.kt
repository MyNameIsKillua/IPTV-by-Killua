package dev.killua.iptv.data.xtream

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

internal interface XtreamApi {
    @GET
    suspend fun request(
        @Url endpoint: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String? = null,
        @Query("vod_id") vodId: String? = null,
        @Query("series_id") seriesId: String? = null,
        @Query("stream_id") streamId: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<ResponseBody>
}
