package com.carrot.hud

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SettingsTilesUiTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList())
    @Test fun openingTileSettingsOffersProfilesWithoutStartingWrites(){
        val controller=Robolectric.buildActivity(SettingsTilesActivity::class.java).create()
        val a=controller.get();val views=all(a.findViewById(android.R.id.content))
        assertEquals(1,views.filterIsInstance<Button>().count {it.text=="프로필5개"})
        assertTrue(views.filterIsInstance<TextView>().any {it.text.toString().contains("튜닝 프로필5개")})
        assertFalse(TuningOperationGate.isBusy());controller.destroy()
    }
    @Test fun fiveGoldenProfileCardsRouteSelectionToExistingApplicationFlow(){
        val controller=Robolectric.buildActivity(SettingsTilesActivity::class.java).create();val a=controller.get()
        all(a.findViewById(android.R.id.content)).filterIsInstance<Button>().single {it.text=="프로필5개"}.performClick()
        val cards=all(a.findViewById(android.R.id.content)).filterIsInstance<Button>().filter {it.text=="변경값 확인·적용 화면"}
        assertEquals(5,cards.size);cards[3].performClick()
        val intent=Shadows.shadowOf(a).nextStartedActivity
        assertEquals(TuningProfilesActivity::class.java.name,intent.component!!.className)
        assertEquals("lx3-combined-04-r4",intent.getStringExtra(TuningProfilesActivity.EXTRA_PROFILE_ID))
        assertFalse(TuningOperationGate.isBusy());controller.destroy()
    }
    @Test fun anUnverifiedProfileWarningRemainsVisibleInTheNewSettingsScreen(){
        val c=org.robolectric.RuntimeEnvironment.getApplication()
        val d=DeviceStore.addDevice(c,"LX3","100.98.217.122")
        TuningProfileHealth.observe(c,d,org.json.JSONObject().put("unverified_previous",true))
        val controller=Robolectric.buildActivity(SettingsTilesActivity::class.java).create()
        assertTrue(all(controller.get().findViewById(android.R.id.content)).filterIsInstance<TextView>().any {it.text.toString().contains("보존한 이전 튜닝 결과 미확인")})
        assertFalse(TuningOperationGate.isBusy());controller.destroy()
    }
}
