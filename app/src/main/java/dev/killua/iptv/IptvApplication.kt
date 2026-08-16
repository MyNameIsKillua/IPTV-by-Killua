package dev.killua.iptv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader

class IptvApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = container.artworkImageLoader
}
