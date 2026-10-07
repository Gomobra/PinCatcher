package com.pincatcher

import android.app.Application
import com.pincatcher.core.data.db.BodyStore
import com.pincatcher.core.data.db.FlowStore
import com.pincatcher.core.data.db.PincatcherDatabase
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
                },
            )
        }
    }
}