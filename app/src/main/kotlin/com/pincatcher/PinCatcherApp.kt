package com.pincatcher

import android.app.Application
import com.pincatcher.data.BodyStore
import com.pincatcher.data.FlowRecorder
import com.pincatcher.data.FlowStore
import com.pincatcher.data.PincatcherDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class PinCatcherApp : Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@PinCatcherApp)
            modules(
                module {
                    single { PincatcherDatabase(androidContext()) }
                    single { FlowStore(get()) }
                    single { BodyStore(get()) }
                    single { FlowRecorder(get(), get()) }
                },
            )
        }
    }
}