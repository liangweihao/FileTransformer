package com.nothing.filetransformer

import android.app.Application
import com.nothing.filetransformer.service.NotificationHelper

class FileTransformerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }
}
