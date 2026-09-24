/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.app.Activity.RESULT_OK
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.android.launcher3.BaseActivity
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.LauncherConstants.ActivityCodes.REQUEST_BIND_DOCK_SEARCH_WIDGET
import com.android.launcher3.LauncherConstants.ActivityCodes.REQUEST_CONFIGURE_DOCK_SEARCH_WIDGET
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.qsb.OSEManager.Companion.OSE_LOOPER
import com.android.launcher3.qsb.QsbAppWidgetHost.Callbacks
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.MutableListenableRef
import com.android.launcher3.widget.util.WidgetSizeHandler
import javax.inject.Inject

/**
 * Manager for default search widget
 *
 * Listens to OSEManager for any OSE changes and provides the updated widget configurations
 */
@LauncherAppSingleton
class OseWidgetManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    oseManager: OSEManager,
    private val widgetHost: QsbAppWidgetHost,
    private val sizeHandler: WidgetSizeHandler,
    private val idp: InvariantDeviceProfile,
    tracker: DaggerSingletonTracker,
) {

    private val mutableProviderInfo = MutableListenableRef<AppWidgetProviderInfo?>(null)
    val providerInfo = mutableProviderInfo.asListenable()

    private val mutableViews = MutableListenableRef<RemoteViews?>(null)
    val views = mutableViews.asListenable()

    private val executor = OSE_LOOPER

    @Volatile private var pendingConfigActivity = false
    @Volatile private var pendingBindRequest = false

    init {
        widgetHost.setCallbacks(
            object : Callbacks {

                override fun onProviderChanged(appWidget: AppWidgetProviderInfo?) =
                    mutableProviderInfo.dispatchValue(appWidget)

                override fun onViewsChanged(views: RemoteViews?) = mutableViews.dispatchValue(views)
            }
        )
        widgetHost.startListening()

        tracker.addCloseable(oseManager.oseInfo.forEach(executor) { reloadWidget() })

        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (LauncherPrefs.DOCK_SEARCH_WIDGET.sharedPrefKey == key) {
                executor.execute { reloadWidget() }
            }
        }
        LauncherPrefs.getPrefs(context).registerOnSharedPreferenceChangeListener(prefListener)
        tracker.addCloseable {
            LauncherPrefs.getPrefs(context).unregisterOnSharedPreferenceChangeListener(prefListener)
        }

        val idpListener = OnIDPChangeListener { updateWidgetSizeAsync() }
        idp.addOnChangeListener(idpListener)
        tracker.addCloseable {
            idp.removeOnChangeListener(idpListener)
            widgetHost.stopListening()
        }

        executor.execute { reloadWidget() }
    }

    private fun reloadWidget() {
        if (!DockSearchWidgetHelper.isCustomWidgetEnabled(context)) {
            clearActiveWidget()
            return
        }

        val selectedProvider =
            DockSearchWidgetHelper.getSelectedProvider(context)
                ?: run {
                    clearActiveWidget()
                    return
                }

        val searchWidget =
            DockSearchWidgetHelper.getProviderInfo(context, selectedProvider)
                ?: findSearchWidgetForPackage(context, selectedProvider.packageName)
                ?: run {
                    clearActiveWidget()
                    return
                }

        val currentWidgetId = widgetHost.getBoundWidgetId()
        val currentInfo =
            if (currentWidgetId != INVALID_APPWIDGET_ID)
                AppWidgetManager.getInstance(context).getAppWidgetInfo(currentWidgetId)
            else null

        if (currentInfo?.provider == searchWidget.provider) {
            widgetHost.setActiveWidget(currentWidgetId, currentInfo)
            updateWidgetSizeAsync()
            return
        }

        val widgetId = widgetHost.allocateAppWidgetId()
        val bindOptions = sizeHandler.getWidgetSizeOptions(idp.numColumns, 1)
        val bindSuccess =
            AppWidgetManager.getInstance(context)
                .bindAppWidgetIdIfAllowed(
                    widgetId,
                    searchWidget.profile,
                    searchWidget.provider,
                    bindOptions,
                )

        if (bindSuccess) {
            widgetHost.setActiveWidget(widgetId, searchWidget)
            updateWidgetSizeAsync()
            if (DockSearchWidgetHelper.consumePendingConfiguration(context)) {
                pendingConfigActivity = true
            }
        } else {
            widgetHost.deleteAppWidgetId(widgetId)
            pendingBindRequest = true
            clearActiveWidget()
        }
    }

    private fun clearActiveWidget() {
        widgetHost.setActiveWidget(INVALID_APPWIDGET_ID, null)
        dispatchNullValues()
    }

    private fun updateWidgetSizeAsync() {
        val widgetId = widgetHost.getActiveWidgetId()
        if (widgetId != INVALID_APPWIDGET_ID) {
            sizeHandler.updateSizeRangesAsync(widgetId, idp.numColumns, 1, executor)
        }
    }

    private fun dispatchNullValues() {
        if (mutableProviderInfo.value != null) mutableProviderInfo.dispatchValue(null)
        if (mutableViews.value != null) mutableViews.dispatchValue(null)
    }

    /** Launches the system bind permission flow when silent bind was denied. */
    fun tryStartPendingBindActivity(activity: BaseActivity): Boolean {
        if (!pendingBindRequest || !DockSearchWidgetHelper.isCustomWidgetEnabled(context)) {
            return false
        }
        val provider = DockSearchWidgetHelper.getSelectedProvider(context) ?: return false
        pendingBindRequest = false
        val widgetId = widgetHost.allocateAppWidgetId()
        return try {
            activity.startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider),
                REQUEST_BIND_DOCK_SEARCH_WIDGET,
            )
            true
        } catch (e: ActivityNotFoundException) {
            widgetHost.deleteAppWidgetId(widgetId)
            Toast.makeText(activity, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun handleBindActivityResult(resultCode: Int, data: Intent?, activity: BaseActivity? = null) {
        val widgetId =
            data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_APPWIDGET_ID)
                ?: INVALID_APPWIDGET_ID
        if (resultCode != RESULT_OK || widgetId == INVALID_APPWIDGET_ID) {
            if (widgetId != INVALID_APPWIDGET_ID) {
                widgetHost.deleteAppWidgetId(widgetId)
            }
            pendingBindRequest = true
            return
        }
        executor.execute {
            val provider = DockSearchWidgetHelper.getSelectedProvider(context) ?: return@execute
            val searchWidget =
                DockSearchWidgetHelper.getProviderInfo(context, provider)
                    ?: findSearchWidgetForPackage(context, provider.packageName)
                    ?: return@execute
            widgetHost.setActiveWidget(widgetId, searchWidget)
            updateWidgetSizeAsync()
            val needsConfig = DockSearchWidgetHelper.consumePendingConfiguration(context)
            if (needsConfig && activity != null) {
                MAIN_EXECUTOR.execute { startConfigActivity(activity) }
            } else if (needsConfig) {
                pendingConfigActivity = true
            }
        }
    }

    fun tryStartPendingConfigActivity(activity: BaseActivity): Boolean {
        if (!pendingConfigActivity) {
            return false
        }
        pendingConfigActivity = false
        return startConfigActivity(activity)
    }

    fun startConfigActivity(activity: BaseActivity): Boolean {
        val widgetId = widgetHost.getActiveWidgetId()
        if (widgetId == INVALID_APPWIDGET_ID) {
            Log.e(TAG, "Couldn't find a valid widgetId")
            return false
        }
        try {
            widgetHost.startAppWidgetConfigureActivityForResult(
                activity,
                widgetId,
                0,
                REQUEST_CONFIGURE_DOCK_SEARCH_WIDGET,
                activity
                    .makeDefaultActivityOptions(-1 /* SPLASH_SCREEN_STYLE_UNDEFINED */)
                    .toBundle(),
            )
            return true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security Exception $e")
        }
        return false
    }

    companion object {
        private const val TAG = "OseWidgetManager"

        @VisibleForTesting
        fun findSearchWidgetForPackage(context: Context, pkg: String): AppWidgetProviderInfo? {
            val eligible =
                AppWidgetManager.getInstance(context)
                    .installedProviders
                    .filter { it.provider.packageName == pkg }
                    .filter { DockSearchWidgetHelper.isEligibleDockSearchWidget(context, it) }
            return eligible.firstOrNull {
                (it.widgetCategory and WIDGET_CATEGORY_SEARCHBOX) != 0
            } ?: eligible.firstOrNull()
        }
    }
}
