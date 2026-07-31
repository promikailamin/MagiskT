package pro.magisk.core

import android.app.Application
import android.content.Context
import pro.magisk.StubApk
import pro.magisk.core.utils.RootUtils

/**
 * Entry point of the Magisk app process.
 *
 * When loaded as a stub replacement [constructor(o: Any)],
 * it rewires class loading so that the real root-service
 * ([RootUtils]) is used instead of the stub's placeholder.
 */
open class App() : Application() {

    /**
     * Stub constructor – called when the hidden APK has been
     * swapped in by [pro.magisk.stub.DynLoad].
     *
     * @param o The [StubApk.Data] object (passed as `Any` to
     *          avoid a compile-time dependency on the stub module).
     */
    constructor(o: Any) : this() {
        val data = StubApk.Data(o)
        data.classToComponent[RootUtils::class.java.name] = data.rootService.name
        data.rootService = RootUtils::class.java
        Info.stub = data
    }

    override fun attachBaseContext(context: Context) {
        if (context is Application) {
            AppContext.attach_application(context)
        } else {
            super.attachBaseContext(context)
            AppContext.attach_application(this)
        }
    }
}
