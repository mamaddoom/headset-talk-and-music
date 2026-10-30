package com.mamaddoom.ridetalk

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.TypedValue
import java.util.Locale

/** Theme (pink / blue), app language (Persian / English) and the matching launcher icon. */
object Look {

    fun theme(settings: Settings) = if (settings.blueTheme) R.style.AppTheme_Blue else R.style.AppTheme

    /** Applies the chosen app language to [base]; the phone's language when none was chosen. */
    fun wrap(base: Context): Context {
        val lang = Settings(base).language
        if (lang.isEmpty()) return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Shows the blue or the pink helmet on the home screen. Two launcher
     * entries point at the same screen; exactly one is enabled. The pink one
     * keeps the original component name, so existing shortcuts keep working.
     *
     * Switching makes many launchers close the app's open screen, so this is
     * only called once the app is in the background (see HomeActivity.onStop),
     * and it does nothing when the icon already matches.
     */
    fun applyLauncherIcon(context: Context, blue: Boolean) {
        val pm = context.packageManager
        // Class names live in the code's package, which need not equal the application id.
        fun cn(cls: String) = ComponentName(context.packageName, "${HomeActivity::class.java.name.substringBeforeLast('.')}.$cls")
        /** Enabled now, counting "default" as what the manifest says (pink on, blue off). */
        fun enabled(cls: String) = when (pm.getComponentEnabledSetting(cn(cls))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> cls == "MainActivity"
            else -> false
        }
        if (enabled("BlueLauncher") == blue && enabled("MainActivity") == !blue) return
        fun set(cls: String, on: Boolean) {
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(cn(cls), state, PackageManager.DONT_KILL_APP)
        }
        // Enable the new one first so there is never a moment with no launcher entry.
        if (blue) {
            set("BlueLauncher", true)
            set("MainActivity", false)
        } else {
            set("MainActivity", true)
            set("BlueLauncher", false)
        }
    }
}

/** A colour that is either a theme attribute (R.attr.cAccent, ...) or a plain colour resource. */
fun Context.colorOf(id: Int): Int {
    if (resources.getResourceTypeName(id) != "attr") return getColor(id)
    val tv = TypedValue()
    theme.resolveAttribute(id, tv, true)
    return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
}
