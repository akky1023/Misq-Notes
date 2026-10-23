package jp.handwriting.note

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

internal enum class AppLanguage(val tag: String) {
    SYSTEM(""), JAPANESE("ja"), ENGLISH("en");
    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag?.substringBefore('-')?.lowercase(Locale.ROOT) } ?: SYSTEM
    }
}

internal object AppLanguages {
    private const val PREFERENCES = "app-language"
    fun current(context: Context): AppLanguage = if (Build.VERSION.SDK_INT >= 33) {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        AppLanguage.fromTag(if (locales.isEmpty) "" else locales[0].toLanguageTag())
    } else AppLanguage.fromTag(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("language", ""))

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val language = current(context)
        if (language == AppLanguage.SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(language.tag))
        return context.createConfigurationContext(configuration)
    }
    fun select(context: Context, language: AppLanguage) {
        if (language == current(context)) return
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            val activity = checkNotNull(context.activity())
            @Suppress("ApplySharedPref")
            val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString("language", language.tag).commit()
            check(saved) { AppMessages.text(R.string.language_save_failed) }
            activity.recreate()
        }
    }
    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}

/** UI and IO messages share the activity's current Android resource configuration. */
internal object AppMessages {
    @Volatile private var resources: Resources? = null
    fun configure(context: Context) { resources = context.resources }
    fun text(id: Int, vararg arguments: Any?): String {
        val current = resources ?: return "Application message ($id)"
        return if (arguments.isEmpty()) current.getString(id) else current.getString(id, *arguments)
    }
}
