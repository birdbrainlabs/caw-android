package com.caw.android.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import com.caw.android.R
import com.caw.android.remote.RemoteLink
import com.caw.android.remote.Shortcut
import com.caw.android.remote.ShortcutKeys

/**
 * The computer's shortcut buttons (protocol v4), as the dictation screen's grid and the touchpad's
 * row show them. A tap presses the shortcut on the computer at once; when Caw is not connected
 * nothing is sent, now or later, and [notConnected] says so on the screen.
 */
object ShortcutButtons {

    fun make(inflater: LayoutInflater, parent: ViewGroup, shortcut: Shortcut, notConnected: () -> Unit): TextView {
        val b = inflater.inflate(R.layout.shortcut_button, parent, false) as TextView
        b.text = shortcut.name
        b.contentDescription = "${shortcut.name}, ${ShortcutKeys.combo(shortcut)}"
        b.setOnClickListener { RemoteLink.sendShortcut(shortcut) { sent -> if (!sent) notConnected() } }
        return b
    }
}
