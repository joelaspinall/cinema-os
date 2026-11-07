package com.joelaspinall.cinemaos.data

import com.joelaspinall.cinemaos.data.db.ApiCacheRow
import com.joelaspinall.cinemaos.data.db.CinemaDao
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import java.io.IOException

/**
 * TMDB's REST endpoints. Responses come back as JSON text and are read by [Tmdb].
 * `kind` is "movie" or "tv", so films and series share the same calls.
 */
interface TmdbApi {
    @GET("configuration") suspend fun configuration(): String

    @GET("search/{kind}")
    suspend fun search(@Path("kind") kind: String, @Query("query") query: String, @QueryMap extra: Map<String, String> = emptyMap()): String

    @GET("search/multi")
    suspend fun searchAll(@Query("query") query: String, @QueryMap extra: Map<String, String> = emptyMap()): String

    @GET("{kind}/popular") suspend fun popular(@Path("kind") kind: String, @Query("region") region: String): String

    @GET("{kind}/{id}") suspend fun details(@Path("kind") kind: String, @Path("id") id: Int): String

    @GET("{kind}/{id}/recommendations") suspend fun recommendations(@Path("kind") kind: String, @Path("id") id: Int): String

    @GET("{kind}/{id}/watch/providers") suspend fun providers(@Path("kind") kind: String, @Path("id") id: Int): String

    @GET("watch/providers/{kind}") suspend fun providerList(@Path("kind") kind: String, @Query("watch_region") region: String): String

    @GET("discover/{kind}") suspend fun discover(@Path("kind") kind: String, @QueryMap params: Map<String, String>): String

    @GET("genre/{kind}/list") suspend fun genres(@Path("kind") kind: String): String

    @GET("{kind}/{id}/images")
    suspend fun images(@Path("kind") kind: String, @Path("id") id: Int, @Query("include_image_language") languages: String = "en,null"): String

    companion object {
        fun create(tokenProvider: () -> String, dao: CinemaDao?): TmdbApi {
            val client = Http.client.newBuilder()
                .addInterceptor(TmdbAuth(tokenProvider))
                .apply { if (dao != null) addInterceptor(OfflineCache(dao)) }
                .build()
            return Retrofit.Builder()
                .baseUrl("https://api.themoviedb.org/3/")
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()
                .create(TmdbApi::class.java)
        }
    }
}

/** Adds Joel's TMDB key: the long "Read Access Token" as a header, or the short "API key" as a parameter. */
class TmdbAuth(private val token: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val t = token().trim()
        if (t.isBlank()) throw IOException("Add your TMDB key in Settings first.")
        val req = chain.request()
        val authed = if (t.length > 40) {
            req.newBuilder().header("Authorization", "Bearer $t").build()
        } else {
            req.newBuilder().url(req.url.newBuilder().addQueryParameter("api_key", t).build()).build()
        }
        return chain.proceed(authed.newBuilder().header("Accept", "application/json").build())
    }
}

/**
 * Saves every good TMDB response in the database. When the TV is offline (or TMDB is down),
 * the saved copy is returned instead, so pages keep working with the last known data.
 * The cache key leaves out the API key so it is never stored.
 */
class OfflineCache(private val dao: CinemaDao) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val key = req.url.newBuilder().removeAllQueryParameters("api_key").build().toString()
        val res = try {
            chain.proceed(req)
        } catch (e: IOException) {
            val hit = runCatching { dao.cached(key) }.getOrNull() ?: throw e
            return Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK (offline copy)")
                .body(hit.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        if (!res.isSuccessful) return res
        val text = res.body?.string() ?: ""
        runCatching { dao.cache(ApiCacheRow(key, text, System.currentTimeMillis())) }
        return res.newBuilder().body(text.toResponseBody("application/json".toMediaType())).build()
    }
}
