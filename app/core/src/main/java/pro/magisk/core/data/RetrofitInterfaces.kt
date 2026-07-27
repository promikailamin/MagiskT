package pro.magisk.core.data

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

interface RawUrl {

    @GET
    @Streaming
    suspend fun fetchFile(@Url url: String): ResponseBody

    @GET
    suspend fun fetchString(@Url url: String): String

}
