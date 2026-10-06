package com.robb3n.petrel

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.ensureChannel(this)
        GroupsRepository.start()
        ExitIpRepository.start(this)
    }

    companion object {
        @Volatile
        var instance: App? = null
            private set
    }
}
