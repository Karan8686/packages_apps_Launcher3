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

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL
import android.content.ComponentName
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.qsb.OSEManager.Companion.OSE_LOOPER
import com.android.launcher3.qsb.OSEManager.OSEInfo
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.MutableListenableRef
import com.android.launcher3.util.SandboxApplication
import com.android.launcher3.util.TestUtil
import com.android.launcher3.util.rule.ShellCommandRule
import com.android.launcher3.util.ui.TestViewHelpers
import com.android.launcher3.widget.util.WidgetSizeHandler
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class OseWidgetManagerTest {

    @get:Rule val context = SandboxApplication()
    @get:Rule val widgetsPermission = ShellCommandRule.grantWidgetBind()
    @get:Rule val mockitoRule = MockitoJUnit.rule()

    @Mock lateinit var oseManager: OSEManager
    @Mock lateinit var widgetHost: QsbAppWidgetHost
    @Mock lateinit var sizeHandler: WidgetSizeHandler
    @Mock lateinit var tracker: DaggerSingletonTracker
    private lateinit var widgetManager: AppWidgetManager

    private val mockOseInfo = MutableListenableRef(OSEInfo(TEST_PKG))
    private val executor = OSE_LOOPER

    @Before
    fun setup() {
        widgetManager = context.spyService(AppWidgetManager::class.java)
        doReturn(mockOseInfo).whenever(oseManager).oseInfo
        doReturn(true)
            .whenever(widgetManager)
            .bindAppWidgetIdIfAllowed(any(), any(), any(), any())
        doReturn(Bundle()).whenever(sizeHandler).getWidgetSizeOptions(any(), any())
    }

    private fun enableCustomWidget(widgetInfo: AppWidgetProviderInfo) {
        LauncherPrefs.get(context)
            .put(LauncherPrefs.DOCK_SEARCH_WIDGET, widgetInfo.provider.flattenToString())
        doReturn(listOf(widgetInfo)).whenever(widgetManager).installedProviders
    }

    @Test
    fun findSearchWidgetForPackage_returns_non_config_widget() {
        val infoWithoutConfig = TestViewHelpers.findWidgetProvider(false)
        val infoWithConfig = TestViewHelpers.findWidgetProvider(true)

        doReturn(listOf(infoWithConfig, infoWithoutConfig))
            .whenever(widgetManager)
            .installedProviders

        assertEquals(
            infoWithoutConfig.provider,
            OseWidgetManager.findSearchWidgetForPackage(context, "test")?.provider,
        )
    }

    @Test
    fun findSearchWidgetForPackage_prefers_search_widget() {
        val infoWithoutConfig = TestViewHelpers.findWidgetProvider(false)
        val infoWithConfig = TestViewHelpers.findWidgetProvider(true)
        val infoSearch =
            TestViewHelpers.findWidgetProvider(false).apply {
                provider = ComponentName("s", "d")
                widgetCategory = WIDGET_CATEGORY_SEARCHBOX
            }

        doReturn(listOf(infoWithConfig, infoWithoutConfig, infoSearch))
            .whenever(widgetManager)
            .installedProviders
        assertEquals(
            infoSearch.provider,
            OseWidgetManager.findSearchWidgetForPackage(context, "test")?.provider,
        )
    }

    @Test
    fun no_update_when_current_widget_is_same() {
        val widgetInfo = TestViewHelpers.findWidgetProvider(false)
        val currentWidgetId = 1

        enableCustomWidget(widgetInfo)
        doReturn(widgetInfo).whenever(widgetManager).getAppWidgetInfo(eq(currentWidgetId))
        doReturn(currentWidgetId).whenever(widgetHost).getBoundWidgetId()

        createOseWidgetManager()
        TestUtil.runOnExecutorSync(executor) {}
        verify(widgetHost).setActiveWidget(eq(currentWidgetId), eq(widgetInfo))
    }

    @Test
    fun binds_widget_when_nothing_bound() {
        val widgetInfo = TestViewHelpers.findWidgetProvider(false)
        enableCustomWidget(widgetInfo)

        val newWidgetId = 1

        doReturn(INVALID_APPWIDGET_ID).whenever(widgetHost).getBoundWidgetId()
        doReturn(newWidgetId).whenever(widgetHost).allocateAppWidgetId()

        createOseWidgetManager()
        TestUtil.runOnExecutorSync(executor) {}
        verify(widgetHost).setActiveWidget(eq(newWidgetId), eq(widgetInfo))
    }

    @Test
    fun nothing_active_when_no_search_widget() {
        val widgetInfo = TestViewHelpers.findWidgetProvider(false)
        LauncherPrefs.get(context)
            .put(LauncherPrefs.DOCK_SEARCH_WIDGET, widgetInfo.provider.flattenToString())
        doReturn(emptyList<AppWidgetProviderInfo>()).whenever(widgetManager).installedProviders
        doReturn(INVALID_APPWIDGET_ID).whenever(widgetHost).getBoundWidgetId()

        createOseWidgetManager()
        TestUtil.runOnExecutorSync(executor) {}
        verify(widgetHost).setActiveWidget(eq(INVALID_APPWIDGET_ID), isNull())
    }

    @Test
    fun recreates_widget_when_selection_changes() {
        val widgetInfo = TestViewHelpers.findWidgetProvider(false)
        val currentWidgetId = 1

        enableCustomWidget(widgetInfo)
        doReturn(widgetInfo).whenever(widgetManager).getAppWidgetInfo(eq(currentWidgetId))
        doReturn(currentWidgetId).whenever(widgetHost).getBoundWidgetId()

        createOseWidgetManager()
        TestUtil.runOnExecutorSync(executor) {}
        verify(widgetHost).setActiveWidget(eq(currentWidgetId), eq(widgetInfo))

        val newWidgetId = 2
        val newWidgetInfo =
            TestViewHelpers.findWidgetProvider(true).apply {
                provider = ComponentName("something_new", "SearchWidget")
                widgetFeatures = WIDGET_FEATURE_CONFIGURATION_OPTIONAL
                widgetCategory = WIDGET_CATEGORY_SEARCHBOX
            }
        assertNotEquals(newWidgetInfo.provider, widgetInfo.provider)
        doReturn(newWidgetId).whenever(widgetHost).allocateAppWidgetId()
        doReturn(listOf(widgetInfo, newWidgetInfo)).whenever(widgetManager).installedProviders
        LauncherPrefs.get(context)
            .put(LauncherPrefs.DOCK_SEARCH_WIDGET, newWidgetInfo.provider.flattenToString())

        createOseWidgetManager()
        TestUtil.runOnExecutorSync(executor) {}
        verify(widgetHost).setActiveWidget(eq(newWidgetId), eq(newWidgetInfo))
    }

    @Test
    fun nothing_active_when_binding_fails() {
        val widgetInfo = TestViewHelpers.findWidgetProvider(false)
        val currentWidgetId = 1

        enableCustomWidget(widgetInfo)
        doReturn(widgetInfo).whenever(widgetManager).getAppWidgetInfo(eq(currentWidgetId))
        doReturn(currentWidgetId).whenever(widgetHost).getBoundWidgetId()

        createOseWidgetManager()
        TestUtil.runOnExecutorSync(executor) {}
        verify(widgetHost).setActiveWidget(eq(currentWidgetId), eq(widgetInfo))

        val newWidgetId = 2
        val newWidgetInfo =
            TestViewHelpers.findWidgetProvider(true).apply {
                provider = ComponentName("something_new", "SearchWidget")
                widgetFeatures = WIDGET_FEATURE_CONFIGURATION_OPTIONAL
                widgetCategory = WIDGET_CATEGORY_SEARCHBOX
            }
        doReturn(newWidgetId).whenever(widgetHost).allocateAppWidgetId()
        doReturn(listOf(widgetInfo, newWidgetInfo)).whenever(widgetManager).installedProviders
        LauncherPrefs.get(context)
            .put(LauncherPrefs.DOCK_SEARCH_WIDGET, newWidgetInfo.provider.flattenToString())
        doReturn(false)
            .whenever(widgetManager)
            .bindAppWidgetIdIfAllowed(any(), any(), any(), any())

        createOseWidgetManager()
        TestUtil.runOnExecutorSync(executor) {}
        verify(widgetHost).deleteAppWidgetId(newWidgetId)
        verify(widgetHost).setActiveWidget(eq(INVALID_APPWIDGET_ID), isNull())
    }

    private fun createOseWidgetManager() =
        OseWidgetManager(
            context,
            oseManager,
            widgetHost,
            sizeHandler,
            context.appComponent.idp,
            tracker,
        )

    companion object {
        private const val TEST_PKG = "test"
    }
}
