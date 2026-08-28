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

    fun speakDeparture(firstInstruction: RouteInstruction?, nextInstruction: RouteInstruction?, totalDistanceFormatted: String) {
        val currentStreet = firstInstruction?.streetName?.cleanStreetName() ?: ""
        val nextStreet = nextInstruction?.streetName?.cleanStreetName() ?: ""
        val nextTurnPhrase = if (nextInstruction != null && nextInstruction.turnType != RouteInstruction.TurnType.STRAIGHT) {
            getTurnDirectionPhrase(nextInstruction)
        } else ""

        val laneAdviceAr = nextInstruction?.lanes?.let { lanes ->
            val activeLeft = lanes.firstOrNull()?.isActive == true
            val activeRight = lanes.lastOrNull()?.isActive == true
            when {
                activeLeft && !activeRight -> "، والزم المسار الأيسر"
                activeRight && !activeLeft -> "، والزم المسار الأيمن"
                else -> ""
            }
        } ?: ""

        val laneAdviceEn = nextInstruction?.lanes?.let { lanes ->
            val activeLeft = lanes.firstOrNull()?.isActive == true
            val activeRight = lanes.lastOrNull()?.isActive == true
            when {
                activeLeft && !activeRight -> ", use the left lane"
                activeRight && !activeLeft -> ", use the right lane"
                else -> ""
            }
        } ?: ""

        val text = if (isArabic) {
            if (currentStreet.isNotBlank()) {
                if (nextInstruction != null && nextInstruction.turnType != RouteInstruction.TurnType.STRAIGHT && nextStreet.isNotBlank()) {
                    "انطلق باتجاه $currentStreet ، ثم $nextTurnPhrase إلى $nextStreet$laneAdviceAr"
                } else {
                    "انطلق باتجاه $currentStreet ، المسافة $totalDistanceFormatted"
                }
            } else {
                "انطلق على المسار ، المسافة $totalDistanceFormatted"
            }
        } else {
            val nextTurnEn = if (nextInstruction != null && nextInstruction.turnType != RouteInstruction.TurnType.STRAIGHT) getTurnDirectionPhraseEn(nextInstruction) else ""
            if (currentStreet.isNotBlank()) {
                if (nextInstruction != null && nextInstruction.turnType != RouteInstruction.TurnType.STRAIGHT && nextStreet.isNotBlank()) {
                    "Head towards $currentStreet, then $nextTurnEn onto $nextStreet$laneAdviceEn"
                } else {
                    "Head towards $currentStreet, total distance $totalDistanceFormatted"
                }
            } else {
                "Head onto the route, total distance $totalDistanceFormatted"
            }
        }
        speak(text, flush = true)
    }

    fun speakAdvanceTurn(instruction: RouteInstruction, distanceMeters: Int) {
        val street = instruction.streetName.cleanStreetName()
        val turnPhrase = getTurnDirectionPhrase(instruction)
        val distText = formatDistanceVoice(distanceMeters)

        val laneAdviceAr = instruction.lanes?.let { lanes ->
            val activeLeft = lanes.firstOrNull()?.isActive == true
            val activeRight = lanes.lastOrNull()?.isActive == true
            when {
                activeLeft && !activeRight -> "، والزم المسار الأيسر"
                activeRight && !activeLeft -> "، والزم المسار الأيمن"
                else -> ""
            }
        } ?: ""

        val laneAdviceEn = instruction.lanes?.let { lanes ->
            val activeLeft = lanes.firstOrNull()?.isActive == true
            val activeRight = lanes.lastOrNull()?.isActive == true
            when {
                activeLeft && !activeRight -> ", use the left lane"
                activeRight && !activeLeft -> ", use the right lane"
                else -> ""
            }
        } ?: ""

        val text = if (isArabic) {
            if (street.isNotBlank()) {
                "بعد $distText ، $turnPhrase إلى $street$laneAdviceAr"
            } else {
                "بعد $distText ، $turnPhrase$laneAdviceAr"
            }
        } else {
            val englishTurn = getTurnDirectionPhraseEn(instruction)
            if (street.isNotBlank()) {
                "In $distText, $englishTurn onto $street$laneAdviceEn"
            } else {
                "In $distText, $englishTurn$laneAdviceEn"
            }
        }
        speak(text, flush = true)
    }

    fun speakExecuteTurn(instruction: RouteInstruction) {
        val street = instruction.streetName.cleanStreetName()
        val turnPhrase = getTurnDirectionPhrase(instruction)

        val text = if (isArabic) {
            if (street.isNotBlank()) {
                "$turnPhrase الآن إلى $street"
            } else {
                "$turnPhrase الآن"
            }
        } else {
            val englishTurn = getTurnDirectionPhraseEn(instruction)
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

    private fun getTurnDirectionPhrase(instruction: RouteInstruction): String {
        return when (instruction.turnType) {
            RouteInstruction.TurnType.SLIGHT_LEFT -> "الزم اليسار"
            RouteInstruction.TurnType.LEFT, RouteInstruction.TurnType.SHARP_LEFT -> "انعطف يساراً"
            RouteInstruction.TurnType.SLIGHT_RIGHT -> "الزم اليمين"
            RouteInstruction.TurnType.RIGHT, RouteInstruction.TurnType.SHARP_RIGHT -> "انعطف يميناً"
            RouteInstruction.TurnType.FINISH -> "وجهتك على الطريق"
            RouteInstruction.TurnType.UTURN -> "قم بالدوران للخلف"
            else -> "تابع السير إلى الأمام"
        }
    }

    private fun getTurnDirectionPhraseEn(instruction: RouteInstruction): String {
        return when (instruction.turnType) {
            RouteInstruction.TurnType.SLIGHT_LEFT -> "keep left"
            RouteInstruction.TurnType.LEFT -> "turn left"
            RouteInstruction.TurnType.SHARP_LEFT -> "make a sharp left"
            RouteInstruction.TurnType.SLIGHT_RIGHT -> "keep right"
            RouteInstruction.TurnType.RIGHT -> "turn right"
            RouteInstruction.TurnType.SHARP_RIGHT -> "make a sharp right"
            RouteInstruction.TurnType.FINISH -> "your destination is ahead"
            RouteInstruction.TurnType.UTURN -> "make a U-turn"
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
