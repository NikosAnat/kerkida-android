package app.kerkida

import android.app.Application
import app.kerkida.alerts.AlertScheduler
import app.kerkida.data.AppContainer
import app.kerkida.features.Feature
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import kotlin.concurrent.thread

class KerkidaApp : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Reading the saved data takes seconds on a slow phone, so it stays off the main thread.
        thread(name = "kerkida-start") {
            if (container.flags.isOn(Feature.SMART_ALERTS)) AlertScheduler.start(this)
            app.kerkida.assistant.AssistantScheduler.sync(this)
        }
    }

    /** Crests and faces stay on the phone between visits. Wikimedia asks every app to name itself. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val named = request.url.host.endsWith("wikimedia.org")
                chain.proceed(if (named) request.newBuilder().header("User-Agent", "Kerkida/0.1 (Android)").build() else request)
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("kerkida-images"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
