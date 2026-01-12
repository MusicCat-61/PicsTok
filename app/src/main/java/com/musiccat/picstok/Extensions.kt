package com.musiccat.picstok

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader

val Application.imageLoader: ImageLoader
    get() = SingletonImageLoader.get(this)