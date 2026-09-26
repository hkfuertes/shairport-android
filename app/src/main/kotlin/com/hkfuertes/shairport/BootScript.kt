package com.hkfuertes.shairport

import android.content.Context
import android.util.Log

/** "Start at boot": a Magisk service.d script (runs as root at every boot) that starts the receiver. */
object BootScript {
    private const val TAG = "Shairport"
    private const val PATH = "/data/adb/service.d/shairport.sh"

    /** Installs or removes the script to match the setting. Blocking (su): call off the main thread. */
    fun sync(context: Context) {
        val enabled = Prefs.get(context).getBoolean(Prefs.START_AT_BOOT, false)
        val script = """
            #!/system/bin/sh
            # Start the Shairport AirPlay receiver at boot (written by the app's "Start at boot").
            until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
            am start-foreground-service -n ${context.packageName}/.ReceiverService
        """.trimIndent() + "\n"
        val command = if (enabled) "mkdir -p /data/adb/service.d && cat > $PATH && chmod 755 $PATH" else "rm -f $PATH"
        runCatching {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            process.outputStream.use { if (enabled) it.write(script.toByteArray()) }
            val exit = process.waitFor()
            Log.i(TAG, "Boot script ${if (enabled) "installed" else "removed"} (exit $exit)")
        }.onFailure { Log.w(TAG, "Could not update the boot script", it) }
    }
}
