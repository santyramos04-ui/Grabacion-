package com.santy.grabacion.recorder

import android.content.Context
import android.os.Bundle

/** Formato del video. CameraX siempre produce MP4; lo que cambia es el rango dinámico y el códec. */
enum class VideoFormat {
    /** MP4 normal: H.264, 8 bits (SDR). Compatible con todo. */
    MP4_SDR,

    /** MP4 HDR: HLG 10 bits (HEVC). Solo si la cámara lo admite. */
    MP4_HDR,
}

/** Calidades de CameraX, de menor a mayor. */
enum class VideoQuality(val label: String) { SD("480p"), HD("720p"), FHD("1080p"), UHD("4K") }

/** Tasa de bits del video. AUTO deja que CameraX elija según la calidad. */
enum class Bitrate { AUTO, HIGH, MEDIUM, LOW }

/**
 * Preferencias de grabación compartidas por el vault (que las edita) y la colección (que las usa
 * al iniciar la grabación con el gesto). Se guardan en preferencias privadas.
 */
data class RecordingSettings(
    /** Duración máxima en minutos de grabación real (sin contar pausas); 0 = sin límite. */
    val durationMinutes: Int = 0,
    val format: VideoFormat = VideoFormat.MP4_SDR,
    val quality: VideoQuality = VideoQuality.FHD,
    /** Fotogramas por segundo; 0 = automático. */
    val frameRate: Int = 0,
    val stabilization: Boolean = false,
    val bitrate: Bitrate = Bitrate.AUTO,
    val audio: Boolean = true,
    val frontCamera: Boolean = false,
    /** Enciende la linterna durante la grabación (solo cámara trasera con flash). */
    val torch: Boolean = false,
) {
    val durationLimitMs: Long get() = durationMinutes * 60_000L

    /** Tasa de bits objetivo en bits/s, o null para la automática. */
    fun targetBitrate(): Int? {
        if (bitrate == Bitrate.AUTO) return null
        // Referencias aproximadas por calidad (H.264 a 30 fps).
        val base = when (quality) {
            VideoQuality.SD -> 2_500_000
            VideoQuality.HD -> 6_000_000
            VideoQuality.FHD -> 12_000_000
            VideoQuality.UHD -> 40_000_000
        }
        val factor = when (bitrate) {
            Bitrate.HIGH -> 1.5
            Bitrate.MEDIUM -> 1.0
            else -> 0.5
        }
        return (base * factor).toInt()
    }

    fun toBundle() = Bundle().apply {
        putInt(KEY_DURATION, durationMinutes)
        putString(KEY_FORMAT, format.name)
        putString(KEY_QUALITY, quality.name)
        putInt(KEY_FPS, frameRate)
        putBoolean(KEY_STABILIZATION, stabilization)
        putString(KEY_BITRATE, bitrate.name)
        putBoolean(KEY_AUDIO, audio)
        putBoolean(KEY_FRONT, frontCamera)
        putBoolean(KEY_TORCH, torch)
    }

    companion object {
        /** Duraciones rápidas que ofrece el vault, en minutos. */
        val PRESET_MINUTES = listOf(1, 2, 5, 10)

        private const val KEY_DURATION = "duracion_min"
        private const val KEY_FORMAT = "formato"
        private const val KEY_QUALITY = "calidad"
        private const val KEY_FPS = "fps"
        private const val KEY_STABILIZATION = "estabilizacion"
        private const val KEY_BITRATE = "tasa_bits"
        private const val KEY_AUDIO = "audio"
        private const val KEY_FRONT = "frontal"
        private const val KEY_TORCH = "linterna"

        fun fromBundle(bundle: Bundle?): RecordingSettings {
            if (bundle == null) return RecordingSettings()
            val defaults = RecordingSettings()
            return RecordingSettings(
                durationMinutes = bundle.getInt(KEY_DURATION, defaults.durationMinutes).coerceAtLeast(0),
                format = enumOr(bundle.getString(KEY_FORMAT), defaults.format),
                quality = enumOr(bundle.getString(KEY_QUALITY), defaults.quality),
                frameRate = bundle.getInt(KEY_FPS, defaults.frameRate),
                stabilization = bundle.getBoolean(KEY_STABILIZATION, defaults.stabilization),
                bitrate = enumOr(bundle.getString(KEY_BITRATE), defaults.bitrate),
                audio = bundle.getBoolean(KEY_AUDIO, defaults.audio),
                frontCamera = bundle.getBoolean(KEY_FRONT, defaults.frontCamera),
                torch = bundle.getBoolean(KEY_TORCH, defaults.torch),
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            runCatching { enumValueOf<T>(name!!) }.getOrDefault(default)
    }
}

/** Guarda y lee [RecordingSettings] entre sesiones. */
object RecordingSettingsStore {
    private const val PREFS = "grabacion"
    private const val LEGACY_PREFS = "ajustes"

    fun load(context: Context): RecordingSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("calidad")) return migrateLegacy(context)
        val bundle = Bundle()
        prefs.all.forEach { (key, value) ->
            when (value) {
                is Int -> bundle.putInt(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                is String -> bundle.putString(key, value)
            }
        }
        return RecordingSettings.fromBundle(bundle)
    }

    fun save(context: Context, settings: RecordingSettings) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val bundle = settings.toBundle()
        bundle.keySet().forEach { key ->
            when (val value = bundle.get(key)) {
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }

    /** Toma calidad, audio y cámara de las preferencias que usaba la versión anterior del vault. */
    private fun migrateLegacy(context: Context): RecordingSettings {
        val old = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val settings = RecordingSettings(
            quality = when (old.getString("quality", "FHD")) {
                "HD" -> VideoQuality.HD
                "UHD" -> VideoQuality.UHD
                else -> VideoQuality.FHD
            },
            audio = old.getBoolean("audio", true),
            frontCamera = old.getBoolean("front", false),
        )
        save(context, settings)
        return settings
    }
}
