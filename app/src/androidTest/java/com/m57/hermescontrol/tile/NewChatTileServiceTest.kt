package com.m57.hermescontrol.tile

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatTileServiceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tileIsDiscoverablePermissionProtectedAndLocalized() {
        val component = ComponentName(context, NewChatTileService::class.java)
        val info = context.packageManager.getServiceInfo(component, 0)
        assertTrue(info.exported)
        assertEquals(Manifest.permission.BIND_QUICK_SETTINGS_TILE, info.permission)
        assertEquals(context.getString(R.string.content_desc_new_chat), info.loadLabel(context.packageManager))
        assertNotNull(context.getDrawable(info.icon))
        val services =
            context.packageManager.queryIntentServices(
                Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName),
                PackageManager.GET_META_DATA,
            )
        assertEquals(1, services.count { it.serviceInfo.name == NewChatTileService::class.java.name })
    }

    @Test
    fun launchIntentTargetsThisVariantAndReusesTheActivityWithoutPayload() {
        val intent = newChatTileIntent(context)
        assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
        assertEquals(MainActivity.ACTION_NEW_CHAT_FROM_TILE, intent.action)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            intent.flags,
        )
        assertTrue(intent.extras == null || intent.extras!!.isEmpty)
        assertNotNull(intent.resolveActivity(context.packageManager))
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        assertTrue(pending.isImmutable)
        pending.cancel()
    }
}
