package com.taptrack.carrot

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.f2prateek.rx.preferences2.RxSharedPreferences
import com.taptrack.carrot.kioskcontrol.TappyNotificationManager
import io.reactivex.Observable
import timber.log.Timber

class CarrotApplication : Application() {
    private lateinit var prefs: SharedPreferences
    private lateinit var rxPrefs: RxSharedPreferences

    override fun onCreate() {
        super.onCreate()

        TappyNotificationManager.createNotificationChannelIfOreo(this)

        prefs = getSharedPreferences(PREFS_GLOBAL,Context.MODE_PRIVATE)
        rxPrefs = RxSharedPreferences.create(prefs)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    fun getAutolaunchEnabled() : Observable<Boolean> = rxPrefs.getBoolean(KEY_AUTOLAUNCH,false).asObservable()

    fun setAutolaunchEnabled(shouldLaunch: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOLAUNCH,shouldLaunch).apply()
    }

    companion object {
        const val ACTION_TAG_FOUND = "com.taptrack.carrot.action.TAG_FOUND"
        const val ACTION_NDEF_FOUND = "com.taptrack.carrot.action.NDEF_FOUND"
        const val EXTRA_TAG_TYPE_INT = "com.taptrack.carrot.extra.TAG_TYPE"

        private val PREFS_GLOBAL = CarrotApplication::class.java.name+".PREFS_GLOBAL"
        private val KEY_AUTOLAUNCH = CarrotApplication::class.java.name+".KEY_AUTOLAUNCH"
    }
}

fun Context.getCarrotApplication() = this.applicationContext as CarrotApplication
