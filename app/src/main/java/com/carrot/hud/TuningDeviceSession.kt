package com.carrot.hud

import android.content.Context
import org.json.JSONObject

/** Profiles use the physical serial, not an account-registration placeholder. */
class TuningDeviceSession internal constructor(
    val device:Device,
    private val current:()->Device?,
    private val remoteDongle:()->String?,
    private val rawApi:TuningApi
) {
    val serial:String
    val api:TuningApi
    init {
        registry();dongle()
        serial=readSerial()
        registry()
        api=rawApi.guarded({check()},{reply ->
            reply.optJSONObject("context")?.let {require(it.optString("serial")==serial){"실제 기기가 바뀌었습니다. 다시 연결하세요."}}
        })
    }
    private fun registry() {
        val saved=current()
        require(saved!=null&&saved.id==device.id&&saved.ip==device.ip&&saved.port==device.port&&
            saved.dongle==device.dongle&&!saved.identityNeedsCheck&&!device.identityNeedsCheck) {"대상 기기·주소가 바뀌었습니다. 다시 연결하세요."}
    }
    private fun dongle() {
        if(OpPairingBinding.known(device.dongle))require(remoteDongle()==device.dongle){"등록 기기 식별자가 다릅니다."}
    }
    private fun readSerial():String {
        val state=rawApi.receipt()
        require(state.optString("family")=="LX3"){"LX3 프로필 기기가 아닙니다."}
        val value=state.optJSONObject("context")?.optString("serial").orEmpty()
        require(value.matches(Regex("[A-Za-z0-9_-]{4,128}"))&&value!="UnregisteredDevice"){"실제 기기 일련번호를 확인하지 못했습니다."}
        return value
    }
    fun check(network:Boolean=true) {
        registry()
        if(network){dongle();require(readSerial()==serial){"실제 기기가 바뀌었습니다. 다시 연결하세요."}}
        registry()
    }
    companion object {
        /** Unknown account IDs stay on the user's exact endpoint; never scan for another clone. */
        internal fun resolve(original:Device,resolver:(Device)->Device?):Device =
            if(OpPairingBinding.known(original.dongle))resolver(original) ?: error("오파 연결을 확인하세요.") else original
        fun open(c:Context,original:Device):TuningDeviceSession {
            require(DeviceStore.getActive(c)==original){"대상 기기가 바뀌었습니다. 다시 여세요."}
            val resolved=resolve(original){Net.resolve(c,it)}
            val saved=DeviceStore.getActive(c) ?: error("기기 등록 필요")
            require(saved.id==original.id){"대상 기기가 바뀌었습니다."}
            val bound=resolved.copy(dongle=saved.dongle,identityNeedsCheck=saved.identityNeedsCheck)
            return TuningDeviceSession(bound,{DeviceStore.getActive(c)},
                {Net.fetchDongle(bound.ip,bound.port,1500)},TuningApi.forDevice(bound))
        }
    }
}
