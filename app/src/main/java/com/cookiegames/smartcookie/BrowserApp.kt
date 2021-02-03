package com.cookiegames.smartcookie

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import com.cookiegames.smartcookie.browser.AdBlockChoice
import com.cookiegames.smartcookie.database.bookmark.BookmarkExporter
import com.cookiegames.smartcookie.database.bookmark.BookmarkRepository
import com.cookiegames.smartcookie.device.BuildInfo
import com.cookiegames.smartcookie.device.BuildType
import com.cookiegames.smartcookie.di.AppComponent
import com.cookiegames.smartcookie.di.DaggerAppComponent
import com.cookiegames.smartcookie.di.DatabaseScheduler
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.log.Logger
import com.cookiegames.smartcookie.preference.DeveloperPreferences
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.utils.FileUtils
import com.cookiegames.smartcookie.utils.MemoryLeakUtils
import com.cookiegames.smartcookie.utils.installMultiDex
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.plugins.RxJavaPlugins
import org.adblockplus.libadblockplus.android.AdblockEngine
import org.adblockplus.libadblockplus.android.AdblockEngineProvider.EngineCreatedListener
import org.adblockplus.libadblockplus.android.AdblockEngineProvider.EngineDisposedListener
import org.adblockplus.libadblockplus.android.AndroidHttpClientResourceWrapper
import org.adblockplus.libadblockplus.android.settings.AdblockHelper
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.util.HashMap
import javax.inject.Inject
import kotlin.system.exitProcess


class BrowserApp : Application() {

    @Inject internal lateinit var developerPreferences: DeveloperPreferences
    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject internal lateinit var bookmarkModel: BookmarkRepository
    @Inject @field:DatabaseScheduler internal lateinit var databaseScheduler: Scheduler
    @Inject internal lateinit var logger: Logger
    @Inject internal lateinit var buildInfo: BuildInfo

    lateinit var applicationComponent: AppComponent

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT < 21) {
            installMultiDex(context = base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build())
        }

        if (Build.VERSION.SDK_INT >= 28) {
            if (getProcessName() == "$packageName:incognito") {
                WebView.setDataDirectorySuffix("incognito")
            }
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (BuildConfig.DEBUG) {
                FileUtils.writeCrashToStorage(ex)
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex)
            } else {
                exitProcess(2)
            }
        }

        RxJavaPlugins.setErrorHandler { throwable: Throwable? ->
            if (BuildConfig.DEBUG && throwable != null) {
                FileUtils.writeCrashToStorage(throwable)
                throw throwable
            }
        }

        applicationComponent = DaggerAppComponent.builder()
                .application(this)
                .buildInfo(createBuildInfo())
                .build()
        injector.inject(this)

        Single.fromCallable(bookmarkModel::count)
                .filter { it == 0L }
                .flatMapCompletable {
                    val assetsBookmarks = BookmarkExporter.importBookmarksFromAssets(this@BrowserApp)
                    bookmarkModel.addBookmarkList(assetsBookmarks)
                }
                .subscribeOn(databaseScheduler)
                .subscribe()
        if (buildInfo.buildType == BuildType.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        registerActivityLifecycleCallbacks(object : MemoryLeakUtils.LifecycleAdapter() {
            override fun onActivityDestroyed(activity: Activity) {
                logger.log(TAG, "Cleaning up after the Android framework")
                MemoryLeakUtils.clearNextServedView(activity, this@BrowserApp)
            }
        })

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }

        if (!AdblockHelper.get().isInit) {

            // provide preloaded subscriptions
            val map: MutableMap<String, Int> = HashMap()
            map[AndroidHttpClientResourceWrapper.EASYLIST] = R.raw.easylist
            map[AndroidHttpClientResourceWrapper.EASYLIST_RUSSIAN] = R.raw.easylist
            map[AndroidHttpClientResourceWrapper.EASYLIST_CHINESE] = R.raw.easylist
            map[AndroidHttpClientResourceWrapper.ACCEPTABLE_ADS] = R.raw.exceptionrules
            val helper = AdblockHelper.get()
            helper
                    .init(this, AdblockEngine.BASE_PATH_DIRECTORY, AdblockHelper.PREFERENCE_NAME)

            if(userPreferences.adBlockType != AdBlockChoice.ELEMENT && userPreferences.adBlockType != AdBlockChoice.HYBRID){
                helper.setDisabledByDefault()
            }

            helper.siteKeysConfiguration.forceChecks = false

        }
    }
    private val engineCreatedListener = EngineCreatedListener {
        // put your Adblock FilterEngine init here
    }

    private val engineDisposedListener = EngineDisposedListener {
        // put your Adblock FilterEngine deinit here
    }
    /**
     * Create the [BuildType] from the [BuildConfig].
     */
    private fun createBuildInfo() = BuildInfo(when {
        BuildConfig.DEBUG -> BuildType.DEBUG
        else -> BuildType.RELEASE
    })

    companion object {
        private const val TAG = "BrowserApp"

        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT)
        }
    }

}