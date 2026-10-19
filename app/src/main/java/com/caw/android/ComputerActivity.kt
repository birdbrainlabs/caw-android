package com.caw.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.caw.android.remote.PairTarget
import com.caw.android.remote.PairedDesktop
import com.caw.android.remote.PairedDesktops
import com.caw.android.remote.PairingCode
import com.caw.android.remote.RemoteLink
import com.google.zxing.integration.android.IntentIntegrator

/**
 * "Send to computer", opened from the dictation screen's Computer row ([DictationActivity]). Lists
 * the paired computers (tap one to make it the active one, the one Caw sends to; Remove forgets
 * one), adds a computer by pairing with Caw Desktop by QR code or by typing the address and code,
 * shows whether the active one is connected, and holds the switch that sends dictation to the
 * computer as well (dictation screen) or instead of the phone's field (IME).
 */
class ComputerActivity : Activity(), RemoteLink.Observer {

    private lateinit var stateView: TextView
    private lateinit var sendSwitch: Switch
    private lateinit var computersTitle: View
    private lateinit var computers: LinearLayout
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var code: EditText
    private lateinit var pairButton: Button
    private lateinit var result: TextView
    private var pairing = false
    private var settingSwitch = false
    private var dialog: AlertDialog? = null
    /** What the computer rows show (rebuilt only when it changes, so a tap is not lost to a rebuild). */
    private var shownRows: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_computer)
        RemoteLink.init(this)
        stateView = findViewById(R.id.state)
        sendSwitch = findViewById(R.id.send_switch)
        computersTitle = findViewById(R.id.computers_title)
        computers = findViewById(R.id.computers)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        code = findViewById(R.id.code)
        pairButton = findViewById(R.id.pair)
        result = findViewById(R.id.result)

        sendSwitch.setOnCheckedChangeListener { _, on -> if (!settingSwitch) RemoteLink.setEnabled(on) }
        findViewById<View>(R.id.scan).setOnClickListener { scan() }
        pairButton.setOnClickListener { pairFromForm() }
    }

    override fun onStart() {
        super.onStart()
        RemoteLink.addObserver(this)
        RemoteLink.kick()
    }

    override fun onStop() {
        dialog?.dismiss()
        dialog = null
        RemoteLink.removeObserver(this)
        super.onStop()
    }

    override fun onState(state: RemoteLink.State) {
        val d = state.desktop
        stateView.text = when {
            d == null && state.desktops.isNotEmpty() -> "Choose a computer."
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
        renderComputers(state)
    }

    // ---- the paired computers ----

    private fun renderComputers(state: RemoteLink.State) {
        val rows = Triple(state.desktops, state.desktop?.id, state.connection)
        if (rows == shownRows) return
        shownRows = rows
        computersTitle.visibility = if (state.desktops.isEmpty()) View.GONE else View.VISIBLE
        computers.removeAllViews()
        for (d in state.desktops) {
            val row = layoutInflater.inflate(R.layout.computer_row, computers, false)
            val label = PairedDesktops.label(d, state.desktops)
            val active = d.id == state.desktop?.id
            val name = row.findViewById<TextView>(R.id.computer_name)
            val status = row.findViewById<TextView>(R.id.computer_status)
            val pick = row.findViewById<View>(R.id.computer_pick)
            name.text = if (active) getString(R.string.computer_active_name, label) else label
            name.typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            if (active) {
                val connection = getString(when (state.connection) {
                    RemoteLink.Connection.CONNECTED -> R.string.computer_connected
                    RemoteLink.Connection.CONNECTING -> R.string.computer_connecting
                    else -> R.string.computer_not_connected
                })
                status.text = connection
                pick.contentDescription = getString(R.string.computer_active_description, label, connection)
            } else {
                status.visibility = View.GONE
                pick.contentDescription = getString(R.string.computer_other_description, label)
            }
            // The user always picks: tapping a computer is the only way it becomes the active one.
            pick.setOnClickListener {
                if (!active) {
                    RemoteLink.select(d.id)
                    result.text = ""
                }
            }
            row.findViewById<View>(R.id.computer_remove).setOnClickListener { confirmRemove(d, label) }
            computers.addView(row)
        }
    }

    private fun confirmRemove(d: PairedDesktop, label: String) {
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.computer_remove_title, label))
            .setMessage(R.string.computer_remove_help)
            .setPositiveButton(R.string.computer_remove) { _, _ ->
                RemoteLink.remove(d.id)
                result.text = ""
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        dlg.setOnDismissListener { if (dialog === dlg) dialog = null }
        dialog = dlg
        dlg.show()
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
