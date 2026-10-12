package com.caw.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.caw.android.remote.PairTarget
import com.caw.android.remote.PairingCode
import com.caw.android.remote.RemoteLink
import com.google.zxing.integration.android.IntentIntegrator

/**
 * "Send to computer", opened from the dictation screen's Computer row ([DictationActivity]). Pairs with Caw Desktop by QR code or by typing the
 * address and code, shows the paired computer and whether it is connected, and holds the switch
 * that sends dictation to the computer as well (dictation screen) or instead of the phone's field (IME).
 */
class ComputerActivity : Activity(), RemoteLink.Observer {

    private lateinit var stateView: TextView
    private lateinit var sendSwitch: Switch
    private lateinit var forget: Button
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var code: EditText
    private lateinit var pairButton: Button
    private lateinit var result: TextView
    private var pairing = false
    private var settingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_computer)
        RemoteLink.init(this)
        stateView = findViewById(R.id.state)
        sendSwitch = findViewById(R.id.send_switch)
        forget = findViewById(R.id.forget)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        code = findViewById(R.id.code)
        pairButton = findViewById(R.id.pair)
        result = findViewById(R.id.result)

        sendSwitch.setOnCheckedChangeListener { _, on -> if (!settingSwitch) RemoteLink.setEnabled(on) }
        forget.setOnClickListener {
            RemoteLink.forget()
            result.text = ""
        }
        findViewById<View>(R.id.scan).setOnClickListener { scan() }
        pairButton.setOnClickListener { pairFromForm() }
    }

    override fun onStart() {
        super.onStart()
        RemoteLink.addObserver(this)
        RemoteLink.kick()
    }

    override fun onStop() {
        RemoteLink.removeObserver(this)
        super.onStop()
    }

    override fun onState(state: RemoteLink.State) {
        val d = state.desktop
        stateView.text = when {
            d == null -> "No computer paired."
            !state.enabled -> "Paired with ${d.name} (${d.host}:${d.port}). Send to computer is off."
            state.connected -> "Paired with ${d.name}: connected (${d.host}:${d.port})."
            else -> "Paired with ${d.name}: not connected" +
                (state.problem?.let { " — $it" } ?: "") +
                (if (state.waiting > 0) " (${state.waiting} waiting)" else "") + "."
        }
        settingSwitch = true
        sendSwitch.isChecked = state.enabled
        settingSwitch = false
        sendSwitch.isEnabled = d != null
        forget.visibility = if (d != null) View.VISIBLE else View.GONE
    }

    // ---- pairing ----

    private fun pairFromForm() {
        val h = host.text.toString().trim()
        val p = port.text.toString().trim().ifEmpty { PairTarget.DEFAULT_PORT.toString() }.toIntOrNull()
        when {
            h.isEmpty() -> result.text = "Type the computer's address."
            p == null || p !in 1..65535 -> result.text = "The port is a number up to 65535 (Caw Desktop uses 47820)."
            else -> pair(PairTarget(h, p, code.text.toString(), null))
        }
    }

    private fun pair(target: PairTarget) {
        if (pairing) return
        PairingCode.problem(target.code)?.let {
            result.text = it
            return
        }
        pairing = true
        pairButton.isEnabled = false
        result.text = "Pairing with ${target.name ?: target.host}…"
        RemoteLink.pair(target.host, target.port, target.code, target.name) { r ->
            pairing = false
            pairButton.isEnabled = true
            result.text = when (r) {
                is RemoteLink.PairResult.Paired -> {
                    code.setText("")
                    "Paired with ${r.name}"
                }
                is RemoteLink.PairResult.Failed -> "Pairing failed: ${r.reason}"
            }
        }
    }

    // ---- QR ----

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(getString(R.string.computer_scan_prompt))
            .setOrientationLocked(false)
            .setBeepEnabled(false)
            .initiateScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scan()
        } else {
            result.text = "Scanning needs the camera. You can type the address and code instead."
        }
    }

    @Deprecated("Activity result API; this screen is a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val scanned = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanned == null) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val contents = scanned.contents ?: return // cancelled
        val target = PairTarget.parse(contents)
        if (target == null) {
            result.text = "That QR code is not a Caw pairing code."
            return
        }
        host.setText(target.host)
        port.setText(target.port.toString())
        code.setText(PairingCode.display(target.code))
        pair(target)
    }

    companion object {
        private const val REQUEST_CAMERA = 2
    }
}
