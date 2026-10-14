package com.carrot.hud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpPairingBindingTest {
    private val endpoint = OpEndpoint("http://100.71.19.14:7000", "x".repeat(43))
    private val device = Device("lx3", "LX3", "192.168.0.146", 7000, "fb6d3f3f")
    @Test fun tailscalePairingSurvivesHudWifiAddressChanges() {
        assertTrue(OpPairingBinding.canBind(device, endpoint, "fb6d3f3f"))
        assertTrue(OpPairingBinding.allows(device.copy(ip="10.49.231.25"), "lx3", "fb6d3f3f", endpoint))
    }
    @Test fun otherVehicleAndUnidentifiedDeviceCannotInheritPairing() {
        assertFalse(OpPairingBinding.canBind(device, endpoint, "other"))
        assertFalse(OpPairingBinding.canBind(device, endpoint, null))
        assertFalse(OpPairingBinding.canBind(device.copy(dongle="UnregisteredDevice"), endpoint, "fb6d3f3f"))
        assertFalse(OpPairingBinding.allows(device.copy(id="ev9"), "lx3", "fb6d3f3f", endpoint))
        assertFalse(OpPairingBinding.allows(device.copy(dongle="other"), "lx3", "fb6d3f3f", endpoint))
    }
    @Test fun directTailscaleRegistrationCanBindKnownTarget() {
        assertTrue(OpPairingBinding.canBind(device.copy(ip="100.71.19.14",dongle=""),endpoint,"fb6d3f3f"))
        assertFalse(OpPairingBinding.canBind(device.copy(ip="100.71.19.14",dongle=""),endpoint,"UnregisteredDevice"))
    }
    @Test fun diagnosticWidgetDoesNotInferRecordingFromReachability() {
        assertEquals("진단 작동 중",DiagnosticStatusPolicy.label(JSONObject("{\"state\":\"recording\"}")))
        assertEquals("진단 기록 오류",DiagnosticStatusPolicy.label(JSONObject("{\"state\":\"recording\",\"error\":\"disk full\"}")))
        assertNotEquals("진단 작동 중",DiagnosticStatusPolicy.label(JSONObject("{\"state\":\"starting\"}")))
        assertNotEquals("진단 작동 중",DiagnosticStatusPolicy.label(JSONObject("{}")))
    }
}
