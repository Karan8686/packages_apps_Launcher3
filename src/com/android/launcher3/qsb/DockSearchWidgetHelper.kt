/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.qsb

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.qsb.OSEManager.Companion.SEARCH_ENGINE_SETTINGS_KEY
import java.util.Locale

object DockSearchWidgetHelper {

    @JvmStatic
    fun isCustomWidgetEnabled(context: Context): Boolean {
        return getSelectedProviderFlattened(context).isNotEmpty()
    }

    @JvmStatic
    fun getSelectedProviderFlattened(context: Context): String {
        return LauncherPrefs.get(context).get(LauncherPrefs.DOCK_SEARCH_WIDGET)
    }

    @JvmStatic
    fun getSelectedProvider(context: Context): ComponentName? {
        val flattened = getSelectedProviderFlattened(context)
        if (flattened.isEmpty()) return null
        return ComponentName.unflattenFromString(flattened)
    }

    @JvmStatic
    fun setSelectedProvider(context: Context, provider: ComponentName?) {
        LauncherPrefs.get(context).put(LauncherPrefs.DOCK_SEARCH_WIDGET, provider?.flattenToString() ?: "")
        if (provider != null) {
            Settings.Secure.putString(
                context.contentResolver,
                SEARCH_ENGINE_SETTINGS_KEY,
                provider.packageName,
            )
        } else {
            Settings.Secure.putString(context.contentResolver, SEARCH_ENGINE_SETTINGS_KEY, null)
            clearPendingConfiguration(context)
        }
    }

    @JvmStatic
    fun getEligibleSearchWidgets(context: Context): List<AppWidgetProviderInfo> {
        val pm = context.packageManager
        return AppWidgetManager.getInstance(context)
            .installedProviders
            .asSequence()
            .filter { isEligibleDockSearchWidget(context, it) }
            .sortedWith(
                compareBy<AppWidgetProviderInfo>(
                    { safeAppLabel(pm, it.provider.packageName) },
                    { it.loadLabel(pm).toString() },
                )
            )
            .toList()
    }

    @JvmStatic
    fun isEligibleDockSearchWidget(context: Context, info: AppWidgetProviderInfo): Boolean {
        if ((info.widgetCategory and WIDGET_CATEGORY_SEARCHBOX) != 0) {
            return true
        }
        if (!isWideBarStyleWidget(info)) {
            return false
        }
        val pm = context.packageManager
        val widgetLabel = info.loadLabel(pm).toString().lowercase(Locale.US)
        val className = info.provider.shortClassName.lowercase(Locale.US)
        if (widgetLabel.contains("search") || className.contains("search")) {
            return true
        }
        return safeAppLabel(pm, info.provider.packageName)
            .lowercase(Locale.US)
            .contains("search")
    }

    @JvmStatic
    fun isWideBarStyleWidget(info: AppWidgetProviderInfo): Boolean {
        if (info.minWidth >= info.minHeight * 2) {
            return true
        }
        return info.minResizeWidth >= info.minResizeHeight * 2
    }

    @JvmStatic
    fun supportsConfiguration(info: AppWidgetProviderInfo?): Boolean {
        return info?.configure != null
    }

    @JvmStatic
    fun setPendingConfiguration(context: Context, pending: Boolean) {
        LauncherPrefs.get(context).put(LauncherPrefs.DOCK_SEARCH_WIDGET_PENDING_CONFIG, pending)
    }

    @JvmStatic
    fun consumePendingConfiguration(context: Context): Boolean {
        val pending = LauncherPrefs.get(context).get(LauncherPrefs.DOCK_SEARCH_WIDGET_PENDING_CONFIG)
        if (pending) {
            LauncherPrefs.get(context).put(LauncherPrefs.DOCK_SEARCH_WIDGET_PENDING_CONFIG, false)
        }
        return pending
    }

    @JvmStatic
    fun clearPendingConfiguration(context: Context) {
        LauncherPrefs.get(context).put(LauncherPrefs.DOCK_SEARCH_WIDGET_PENDING_CONFIG, false)
    }

    @JvmStatic
    fun getProviderInfo(context: Context, provider: ComponentName): AppWidgetProviderInfo? {
        return AppWidgetManager.getInstance(context)
            .installedProviders
            .firstOrNull { it.provider == provider }
    }

    @JvmStatic
    fun getWidgetLabel(context: Context, info: AppWidgetProviderInfo): String {
        val pm = context.packageManager
        val appLabel = safeAppLabel(pm, info.provider.packageName)
        return "$appLabel — ${info.loadLabel(pm)}"
    }

    @JvmStatic
    fun getSelectedWidgetLabel(context: Context): String {
        val provider = getSelectedProvider(context)
            ?: return context.getString(R.string.dock_search_widget_default)
        val info = getProviderInfo(context, provider)
        return if (info != null) {
            getWidgetLabel(context, info)
        } else {
            provider.flattenToString()
        }
    }

    private fun safeAppLabel(pm: PackageManager, packageName: String): String {
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
