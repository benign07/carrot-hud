package com.carrot.hud
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViews
import android.widget.TextView
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TuningQuickUiTest {
    @Test fun widgetTapOpensChooserWithoutStartingAnApply(){
        val c=RuntimeEnvironment.getApplication();val d=DeviceStore.addDevice(c,"LX3","100.98.217.122");DeviceStore.setDongle(c,d.id,"device-a")
        for((layout,field,profile) in listOf(Triple(R.layout.widget_hud,R.id.wTransfer,R.id.wProfile),Triple(R.layout.widget_mode,R.id.modeTransfer,R.id.modeProfile))){
            val views=RemoteViews(c.packageName,layout);ArchiveWidgetStatus.fill(c,views,field)
            val root=views.apply(c,null);assertTrue(root.findViewById<TextView>(profile).text.contains("프로필 선택"));root.findViewById<TextView>(profile).performClick()
            val intent=Shadows.shadowOf(c).nextStartedActivity;assertEquals(TuningQuickActivity::class.java.name,intent.component!!.className);assertEquals(d.id,intent.getStringExtra("device_id"));assertFalse(TuningOperationGate.isBusy())
        }
    }
    @Test fun merelyOpeningChooserDoesNotPersistOrApply(){
        val c=RuntimeEnvironment.getApplication();val d=DeviceStore.addDevice(c,"LX3","100.98.217.122")
        val activity=Robolectric.buildActivity(TuningQuickActivity::class.java,Intent(c,TuningQuickActivity::class.java).putExtra("device_id",d.id)).setup()
        assertFalse(TuningOperationGate.isBusy());assertFalse(TuningWidgetStatus.pending(c,d.id).contains("request_id"));activity.pause().stop().destroy()
    }
    @Test fun incompatibleUserProfileDoesNotHideBuiltinsOrGetDeleted(){
        val c=RuntimeEnvironment.getApplication();val dir=java.io.File(c.filesDir,"tuning-profiles-v1");dir.mkdirs()
        val file=java.io.File(dir,"future.json");file.writeText("{broken}")
        val catalog=TuningProfileStore.load(c);assertEquals(5,catalog.profiles.size);assertTrue(catalog.problems.isNotEmpty());assertEquals("{broken}",file.readText())
    }
    @Test fun activeDeviceChangeUpdatesWidgetTargetEvenWithEqualStatusText(){
        val c=RuntimeEnvironment.getApplication();val a=DeviceStore.addDevice(c,"A","192.168.0.1");ArchiveWidgetStatus.publish(c)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        val b=DeviceStore.addDevice(c,"B","192.168.0.2");assertNotEquals(a.id,b.id);ArchiveWidgetStatus.publish(c)
        assertEquals(b.id,c.getSharedPreferences("archive_widget",0).getString("target",null))
    }

}
