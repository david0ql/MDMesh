package com.dallycontrol.core.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.dallycontrol.core.BuildConfig
import com.dallycontrol.core.config.ServerConfigStore
import com.dallycontrol.core.net.BaseUrlInterceptor
import com.dallycontrol.core.net.MdmApi
import com.dallycontrol.proto.ProtocolJson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Provides the networking stack: OkHttp + Retrofit wired to the shared
 * [ProtocolJson] instance via the kotlinx-serialization converter. Base URL comes
 * from [BuildConfig.MDM_BASE_URL] so it varies per build type without code change.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val jsonMediaType = "application/json".toMediaType()
    private const val MAX_IDLE_CONNECTIONS = 5
    private const val IDLE_KEEP_ALIVE_SEC = 30L
    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val READ_TIMEOUT_SEC = 20L

    @Provides
    @Singleton
    fun provideOkHttp(
        serverConfig: ServerConfigStore,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): OkHttpClient {
        val dnsPrefs = context.getSharedPreferences("mdm_dns", android.content.Context.MODE_PRIVATE)
        val dns = com.dallycontrol.core.net.RememberingDns(object : com.dallycontrol.core.net.RememberingDns.Store {
            override fun get(host: String) = dnsPrefs.getString(host, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            override fun put(host: String, addresses: List<String>) {
                if (addresses.isNotEmpty()) dnsPrefs.edit().putString(host, addresses.joinToString(",")).apply()
            }
        })
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            // A carrier DNS that fails now and then must not stop downloads: fall back to the last good answer.
            .dns(dns)
            // Idle pooled connections live 30 s, not OkHttp's 5 min: carrier NATs (and the emulator's) drop
            // idle TCP silently, and a check-in written into such a dead socket hung until the read timeout,
            // leaving a woken device's commands for the next wake or the 15-minute floor.
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_KEEP_ALIVE_SEC, TimeUnit.SECONDS))
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            // Resolve the real server (provisioned at enrollment) per-request, so the Retrofit base
            // below is only a placeholder and one APK serves every deployment.
            .addInterceptor(BaseUrlInterceptor(serverConfig))
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.MDM_BASE_URL)
        .client(client)
        .addConverterFactory(ProtocolJson.json.asConverterFactory(jsonMediaType))
        .build()

    @Provides
    @Singleton
    fun provideMdmApi(retrofit: Retrofit): MdmApi = retrofit.create(MdmApi::class.java)
}
