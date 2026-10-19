package com.caw.android.session

enum class StatusKind { READY, INFO, WARNING }

/** Status words, as in Caw for Mac. Only the ready text is reworded (no Fn key on a phone). */
data class Status(val text: String, val kind: StatusKind) {
    companion object {
        val READY = Status("Ready — hold to talk", StatusKind.READY)
        val LOADING_MODEL = Status("Loading speech model…", StatusKind.INFO)
        val REQUESTING_PERMISSIONS = Status("Requesting transcription permissions…", StatusKind.INFO)
        val LISTENING = Status("Listening", StatusKind.INFO)
        val FINISHING = Status("Finishing transcript…", StatusKind.INFO)
        val MAX_LENGTH = Status("Recording stopped after 5 minutes — finishing transcript…", StatusKind.INFO)
        val TRANSCRIPT_READY = Status("Transcript ready", StatusKind.INFO)
        val NO_AUDIO = Status("The microphone sent no audio — check Sound settings", StatusKind.WARNING)
        val ONLY_SILENCE = Status("Only silence reached Caw — check the input device", StatusKind.WARNING)
        val DIDNT_CATCH = Status("Didn't catch that — try again", StatusKind.WARNING)
        /** No words, and the sound was rumble: a finger over the mic (see MicCoverHint). */
        val MIC_COVERED = Status("I can't hear you", StatusKind.WARNING)
        val MIC_PERMISSION_REQUIRED = Status("Microphone permission is required", StatusKind.WARNING)
        val INSERT_FAILED = Status("That application did not accept the inserted text.", StatusKind.WARNING)

        fun startFailed(error: String) = Status("Could not start transcription: $error", StatusKind.WARNING)

        // "Send to computer" (caw-desktop's PROTOCOL.md). These stand in for READY while the mode is on.

        /** Connected: what is said goes to [computer]. */
        fun sendingTo(computer: String) = Status("Sending to $computer — hold to talk", StatusKind.READY)

        /** Not connected; [waiting] messages go when it connects. */
        fun notConnected(computer: String, waiting: Int) = Status(
            "Not connected to $computer" + when (waiting) {
                0 -> ""
                1 -> " — 1 message waiting"
                else -> " — $waiting messages waiting"
            },
            StatusKind.WARNING,
        )

        /**
         * The ready text, plain or with the paired computer: "Sending to Desk — hold to talk"
         * while connected, "Not connected to Desk — N messages waiting" while away. Both the
         * IME pill and the dictation screen show this.
         */
        fun readyFor(computer: String?, sending: Boolean, connected: Boolean, waiting: Int): Status = when {
            computer == null || !sending -> READY
            connected -> sendingTo(computer)
            else -> notConnected(computer, waiting)
        }

        // The dictation screen (DictationActivity).

        /** Copy put the whole transcript on the clipboard. */
        val COPIED = Status("Copied", StatusKind.INFO)

        /** Clear emptied the transcript; tapping the status brings it back. */
        val CLEARED = Status("Cleared — tap here to undo", StatusKind.INFO)

        /** The computer answered `error` for something the phone sent. */
        fun computerRefused(computer: String, reason: String) =
            Status("$computer could not do that ($reason)", StatusKind.WARNING)

        // The touchpad screen (TouchpadActivity): its connection line.

        val TOUCHPAD_NOT_PAIRED = Status("No computer paired — tap Computer on Caw's screen to pair one", StatusKind.WARNING)

        fun touchpadConnected(computer: String) = Status("Connected to $computer", StatusKind.READY)

        fun touchpadReconnecting(computer: String) = Status("Reconnecting to $computer…", StatusKind.WARNING)

        /** The desktop answered `pointer_unavailable`: it cannot move its mouse (no input device access). */
        fun pointerUnavailable(computer: String) =
            Status("Connected to $computer, but it can't move the mouse (pointer_unavailable)", StatusKind.WARNING)

        /** The touchpad's connection line. */
        fun touchpadLink(computer: String?, connected: Boolean, pointerUnavailable: Boolean): Status = when {
            computer == null -> TOUCHPAD_NOT_PAIRED
            !connected -> touchpadReconnecting(computer)
            pointerUnavailable -> pointerUnavailable(computer)
            else -> touchpadConnected(computer)
        }
    }
}
