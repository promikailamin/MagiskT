package pro.magisk.core

import android.app.Application
import android.content.Context
import timber.log.Timber

/** Entry point of the Magisk app process. */
class App : Application() {

    override fun attachBaseContext(context: Context) {
        Timber.i("App.attachBaseContext: context=%s", context.javaClass.simpleName)
        if (context is Application) {
            AppContext.attachApplication(context)
        } else {
            super.attachBaseContext(context)
            AppContext.attachApplication(this)
        }
    }
}
