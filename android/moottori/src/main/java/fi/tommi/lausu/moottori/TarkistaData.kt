// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Tommi Haanranta
package fi.tommi.lausu.moottori

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Kertoo asetuksille, mitkä kielet moottori tarjoaa. */
class TarkistaData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kielet = arrayListOf("fin-FIN", "eng-USA")
        setResult(
            TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
            Intent()
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, kielet)
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf()),
        )
        finish()
    }
}
