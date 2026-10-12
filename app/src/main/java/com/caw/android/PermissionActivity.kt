package com.caw.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/** Invisible activity that asks for the microphone (and notifications), then closes. An IME cannot ask by itself. */
class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = wanted().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState == null) requestPermissions(missing.toTypedArray(), REQUEST)
    }

    /**
     * The microphone, and on Android 13+ notifications too, for the quiet "Caw is ready" notice of
     * KeepAliveService. Declining notifications is fine: the service still runs, Android just hides it.
     */
    private fun wanted(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listOf(Manifest.permission.RECORD_AUDIO)
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Only the microphone matters here; notifications may be declined.
        val mic = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val granted = mic < 0 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Android will not ask again; open Caw's settings page so it can be switched on there.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    companion object {
        private const val REQUEST = 1
    }
}
