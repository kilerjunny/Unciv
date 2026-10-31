package com.unciv.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.WorkManager
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.unciv.logic.IdChecker
import com.unciv.logic.automation.civilization.GrandStrategyThermal
import com.unciv.logic.files.SAVE_FILES_FOLDER
import com.unciv.logic.files.UncivFiles
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.screens.multiplayerscreens.AddFriendScreen
import com.unciv.utils.Concurrency.runOnGLThread
import com.unciv.utils.Dispatcher
import com.unciv.utils.Display
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import java.io.File
import java.lang.Exception
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class AndroidLauncher : AndroidApplication() {

    private var game: AndroidGame? = null

    // GS_THERMAL_MONITOR_V1: Sampling only while the Activity is resumed, 1 read/15 seconds.
    // Does not keep the screen on, create a service, or run while the game is paused.
    private val thermalHandler = Handler(Looper.getMainLooper())
    private val thermalTask = object : Runnable {
        override fun run() {
            sampleThermalState()
            thermalHandler.postDelayed(this, 15_000L)
        }
    }

    private fun startThermalSampling() {
        thermalHandler.removeCallbacks(thermalTask)
        thermalTask.run()
    }

    private fun stopThermalSampling() {
        thermalHandler.removeCallbacks(thermalTask)
    }

    private fun sampleThermalState() {
        // ACTION_BATTERY_CHANGED is sticky: no broadcast receiver registration needed.
        val battery = try {
            applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        val rawTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tempC = if (rawTemp > 0 && rawTemp < 1000) rawTemp / 10f else null
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging: Boolean? = when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        val thermalLevel: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
            } catch (_: Exception) {
                null
            }
        } else null
        GrandStrategyThermal.record(tempC, thermalLevel, charging)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup Android logging
        Log.backend = AndroidLogBackend(this)

        // Setup Android display
        val displayImpl = AndroidDisplay(this)
        Display.platform = displayImpl

        // Setup Android fonts
        Fonts.fontImplementation = AndroidFont()

        // Setup Android custom saver-loader
        UncivFiles.saverLoader = AndroidSaverLoader(this)
        UncivFiles.preferExternalStorage = true

        val settings = UncivFiles.getSettingsForPlatformLaunchers(filesDir.path)
        val config = AndroidApplicationConfiguration().apply { useImmersiveMode = settings.androidHideSystemUi }

        // Setup orientation
        displayImpl.setOrientation(settings.displayOrientation)

        // Create notification channels for Multiplayer notificator
        MultiplayerTurnCheckWorker.createNotificationChannels(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            createSaveFolder()
            copyMods()
        }

        game = AndroidGame(this)
        initialize(game, config)

        // Setup display cutout AFTER libGDX initialize() — libGDX window setup resets layoutInDisplayCutoutMode
        displayImpl.setCutoutFromUiThread(settings.androidCutout)

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView, ::insetsListener)
        // Force immediate insets dispatch — libGDX init triggers insets before listener is registered
        window.decorView.requestApplyInsets()

        // can be triggered via `adb shell am start -a android.intent.action.VIEW -d https://unciv.app/g/G-ef0f5e5a-f1db-4a54-9d94-92ca986afe8a-9 com.unciv.app`
        // or whatever your game id is
        game!!.setDeepLinkedGame(intent)
        game!!.addScreenObscuredListener()
        processPossibleFriendDeepLink(intent)
    }

    private fun insetsListener(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val settings = try {
            game!!.settings // settings is a lateinit, and this listener *will* be called before it's done
        } catch (_: Throwable) {
            UncivFiles.getSettingsForPlatformLaunchers(filesDir.path)
        }

        // If settings.androidCutout is false, padding is applied
        if (!settings.androidCutout) {
            val cutoutInsets = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(cutoutInsets.left, cutoutInsets.top, cutoutInsets.right, cutoutInsets.bottom)
        } else {
            view.setPadding(0, 0, 0, 0)
        }

        return insets
    }

    /**
     * Copies mods from external data directory (where users can access) to the private one (where
     * libGDX reads from). Note: deletes all files currently in the private mod directory and
     * replaces them with the ones in the external folder!)
     */
    private fun copyMods() {
        // Mod directory in the internal app data (where Gdx.files.local looks)
        val internalModsDir = File("${filesDir.path}/mods")

        // Mod directory in the shared app data (where the user can see and modify)
        val externalPath = getExternalFilesDir(null)?.path ?: return
        val externalModsDir = File("$externalPath/mods")

        try { // Rarely we get a kotlin.io.AccessDeniedException, if so - no biggie
            // Copy external mod directory (with data user put in it) to internal (where it can be read)
            if (!externalModsDir.exists()) externalModsDir.mkdirs() // this can fail sometimes, which is why we check if it exists again in the next line
            if (externalModsDir.exists()) externalModsDir.copyRecursively(internalModsDir, true)
        } catch (ex: Exception) {}
    }

    // Blind attempt to prevent issues like #9604, #9847, #10302, #13113, #15029, #15219
    private fun createSaveFolder() {
        val parent = getExternalFilesDir(null) ?: filesDir
        val newFolder = File(parent, SAVE_FILES_FOLDER)
        if (!newFolder.mkdirs()) return
        // Force MediaStore update
        MediaScannerConnection.scanFile(context, arrayOf(newFolder.absolutePath), null, null)
    }

    override fun onPause() {
        stopThermalSampling()
        val game = this.game!!
        if (game.isInitializedProxy()
                && game.gameInfo != null
                && game.settings.multiplayer.turnCheckerEnabled
                && game.files.getMultiplayerSaves().any()
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                MultiplayerTurnCheckWorker.startTurnChecker(
                    applicationContext,
                    game.files,
                    game.gameInfo!!,
                    game.settings.multiplayer
                )
            }
        }
        super.onPause()
    }

    override fun onResume() {
        try {
            WorkManager.getInstance(applicationContext).cancelAllWorkByTag(MultiplayerTurnCheckWorker.WORK_TAG)
            with(NotificationManagerCompat.from(this)) {
                cancel(MultiplayerTurnCheckWorker.NOTIFICATION_ID_INFO)
                cancel(MultiplayerTurnCheckWorker.NOTIFICATION_ID_SERVICE)
            }
        } catch (ignore: Exception) {
            /* Sometimes this fails for no apparent reason - the multiplayer checker failing to
               cancel should not be enough of a reason for the game to crash! */
        }
        super.onResume()
        startThermalSampling()
    }

    override fun onDestroy() {
        stopThermalSampling()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null)
            return
        game?.setDeepLinkedGame(intent)
        processPossibleFriendDeepLink(intent)
    }
   
    private fun processPossibleFriendDeepLink(intent: Intent) {
        // can be triggered via
        // `adb shell am start -a android.intent.action.VIEW -d https://unciv.app/p/P-63971008-a533-47f6-ad6a-57b616626138-9?name=Yairm210 com.unciv.app`
        // or whatever your friend id and name are
        if (intent.data != null && IdChecker.isFriendDeepLink(intent.data.toString())) {
            val newFriend = IdChecker.checkAndReturnPlayerUuid(intent.data.toString())
            if (newFriend != null) runOnGLThread {
                game!!.pushScreen{ AddFriendScreen(newFriend.name, newFriend.playerID) }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val saverLoader = UncivFiles.saverLoader as AndroidSaverLoader
        saverLoader.onActivityResult(requestCode, data)
        super.onActivityResult(requestCode, resultCode, data)
    }
}
