package pro.magisk.core.di

import android.content.Context
import com.squareup.moshi.Moshi
import pro.magisk.ProviderInstaller
import pro.magisk.core.BuildConfig
import pro.magisk.core.model.DateTimeAdapter
import pro.magisk.core.utils.LocaleSetting
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.File

private object SystemDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return Dns.SYSTEM.lookup(hostname)
    }
}


fun createOkHttpClient(context: Context): OkHttpClient {
    val appCache = Cache(File(context.cacheDir, "okhttp"), 10 * 1024 * 1024)
    val builder = OkHttpClient.Builder().cache(appCache)

    if (BuildConfig.DEBUG) {
        builder.addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
    } else {
        builder.connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
    }

    builder.dns(SystemDns)

    builder.addInterceptor { chain ->
        val request = chain.request().newBuilder()
        request.header("User-Agent", "Magisk/${BuildConfig.APP_VERSION_CODE}")
        request.header("Accept-Language", LocaleSetting.instance.currentLocale.toLanguageTag())
        chain.proceed(request.build())
    }

    ProviderInstaller.install(context)

    return builder.build()
}

fun createMoshiConverterFactory(): MoshiConverterFactory {
    val moshi = Moshi.Builder().add(DateTimeAdapter()).build()
    return MoshiConverterFactory.create(moshi)
}

fun createRetrofit(okHttpClient: OkHttpClient): Retrofit.Builder {
    return Retrofit.Builder()
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(createMoshiConverterFactory())
        .client(okHttpClient)
}

inline fun <reified T> createApiService(retrofitBuilder: Retrofit.Builder, baseUrl: String): T {
    return retrofitBuilder
        .baseUrl(baseUrl)
        .build()
        .create(T::class.java)
}
