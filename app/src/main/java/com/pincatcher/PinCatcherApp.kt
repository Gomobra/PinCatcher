package com.pincatcher

import android.app.Application
import androidx.room.Room
import com.pincatcher.core.data.db.PinCatcherDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class PinCatcherApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val database = Room.databaseBuilder(
            applicationContext,
            PinCatcherDatabase::class.java,
            PinCatcherDatabase.NAME,
        ).build()

        startKoin {
            androidContext(this@PinCatcherApp)
            modules(
                module {
                    single { database }
                    single { get<PinCatcherDatabase>().flowDao() }
                    single { get<PinCatcherDatabase>().sessionDao() }
                    single { get<PinCatcherDatabase>().bodyDao() }
                },
            )
        }
    }
}
