package com.offlinemap.baghdad.engine

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.util.Log
import com.offlinemap.baghdad.data.model.RouteInstruction
import java.util.Locale

class VoiceGuidanceManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val prefs: SharedPreferences = context.getSharedPreferences("voice_guidance_prefs", Context.MODE_PRIVATE)

    var isMuted: Boolean
        get() = prefs.getBoolean("is_muted", false)
        set(value) {
            prefs.edit().putBoolean("is_muted", value).apply()
            if (value) {
                stop()
            }
        }

    var isArabic: Boolean
        get() = prefs.getBoolean("is_arabic", isSystemLanguageArabic())
        set(value) {
            prefs.edit().putBoolean("is_arabic", value).apply()
            updateLanguage()
        }

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("VoiceGuidance", "Failed to create TextToSpeech instance", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            updateLanguage()
            val ttsInstance = tts
            ttsInstance?.setPitch(1.0f)
            ttsInstance?.setSpeechRate(0.95f) // Natural speaking pace
            Log.i("VoiceGuidance", "TTS initialized successfully.")
        } else {
            Log.e("VoiceGuidance", "TTS Initialization failed with status: $status")
            isInitialized = false
        }
    }

    fun updateLanguage() {
        val ttsInstance = tts ?: return
        val arabicLocale = Locale("ar")
        val available = ttsInstance.isLanguageAvailable(arabicLocale)

        if (isArabic && (available == TextToSpeech.LANG_AVAILABLE || available == TextToSpeech.LANG_COUNTRY_AVAILABLE || available == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE)) {
            ttsInstance.language = arabicLocale
            Log.i("VoiceGuidance", "TTS switched to Arabic language.")
        } else {
            ttsInstance.language = Locale.ENGLISH
            Log.i("VoiceGuidance", "TTS switched to English language.")
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (isMuted || !isInitialized || text.isBlank()) return

        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        try {
            tts?.speak(text, mode, null, "NAV_VOICE_${System.currentTimeMillis()}")
            Log.d("VoiceGuidance", "Voice announcement: $text")
        } catch (e: Exception) {
            Log.e("VoiceGuidance", "Error during TTS speak", e)
        }
    }

    fun speakDeparture(firstInstruction: RouteInstruction?, totalDistanceFormatted: String) {
        val street = firstInstruction?.streetName?.cleanStreetName() ?: ""
        val text = if (isArabic) {
            if (street.isNotBlank()) {
                "انطلق باتجاه $street ، المسافة $totalDistanceFormatted"
            } else {
                "انطلق على المسار ، المسافة $totalDistanceFormatted"
            }
        } else {
            if (street.isNotBlank()) {
                "Head towards $street, total distance $totalDistanceFormatted"
            } else {
                "Head onto the route, total distance $totalDistanceFormatted"
            }
        }
        speak(text, flush = true)
    }

    fun speakAdvanceTurn(instruction: RouteInstruction, distanceMeters: Int) {
        val street = instruction.streetName.cleanStreetName()
        val turnPhrase = getTurnDirectionPhrase(instruction.sign)
        val distText = formatDistanceVoice(distanceMeters)

        val text = if (isArabic) {
            if (street.isNotBlank()) {
                "بعد $distText ، $turnPhrase إلى $street"
            } else {
                "بعد $distText ، $turnPhrase"
            }
        } else {
            val englishTurn = getTurnDirectionPhraseEn(instruction.sign)
            if (street.isNotBlank()) {
                "In $distText, $englishTurn onto $street"
            } else {
                "In $distText, $englishTurn"
            }
        }
        speak(text, flush = true)
    }

    fun speakExecuteTurn(instruction: RouteInstruction) {
        val street = instruction.streetName.cleanStreetName()
        val turnPhrase = getTurnDirectionPhrase(instruction.sign)

        val text = if (isArabic) {
            if (street.isNotBlank()) {
                "$turnPhrase الآن إلى $street"
            } else {
                "$turnPhrase الآن"
            }
        } else {
            val englishTurn = getTurnDirectionPhraseEn(instruction.sign)
            if (street.isNotBlank()) {
                "$englishTurn now onto $street"
            } else {
                "$englishTurn now"
            }
        }
        speak(text, flush = true)
    }

    fun speakArrival(destinationName: String = "") {
        val text = if (isArabic) {
            if (destinationName.isNotBlank()) {
                "لقد وصلت إلى وجهتك: $destinationName"
            } else {
                "لقد وصلت إلى وجهتك"
            }
        } else {
            if (destinationName.isNotBlank()) {
                "You have arrived at $destinationName"
            } else {
                "You have arrived at your destination"
            }
        }
        speak(text, flush = true)
    }

    fun speakRecalculating() {
        val text = if (isArabic) "إعادة حساب المسار" else "Recalculating route"
        speak(text, flush = true)
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        isInitialized = false
    }

    private fun getTurnDirectionPhrase(sign: Int): String {
        return when (sign) {
            -1 -> "انعطف يساراً قليلاً"
            -2 -> "انعطف يساراً"
            -3 -> "انعطف يساراً حاداً"
            1 -> "انعطف يميناً قليلاً"
            2 -> "انعطف يميناً"
            3 -> "انعطف يميناً حاداً"
            4 -> "وجهتك على الطريق"
            -6, 6 -> "قم بالدوران للخلف"
            else -> "تابع السير إلى الأمام"
        }
    }

    private fun getTurnDirectionPhraseEn(sign: Int): String {
        return when (sign) {
            -1 -> "keep left"
            -2 -> "turn left"
            -3 -> "make a sharp left"
            1 -> "keep right"
            2 -> "turn right"
            3 -> "make a sharp right"
            4 -> "your destination is ahead"
            -6, 6 -> "make a U-turn"
            else -> "continue straight"
        }
    }

    private fun formatDistanceVoice(meters: Int): String {
        return if (isArabic) {
            when {
                meters >= 1000 -> "${meters / 1000} كيلومتر"
                meters >= 500 -> "خمسمائة متر"
                meters >= 400 -> "أربعمائة متر"
                meters >= 300 -> "ثلاثمائة متر"
                meters >= 200 -> "مائتي متر"
                meters >= 100 -> "مائة متر"
                else -> "$meters متر"
            }
        } else {
            when {
                meters >= 1000 -> "${meters / 1000} kilometers"
                else -> "$meters meters"
            }
        }
    }

    private fun String.cleanStreetName(): String {
        return this.replace("شارع", "").trim()
    }

    private fun isSystemLanguageArabic(): Boolean {
        val lang = Locale.getDefault().language
        return lang.startsWith("ar", ignoreCase = true)
    }
}
