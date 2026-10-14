package com.carrot.hud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityTest {
    private val a = Device("lx3", "LX3", "192.168.0.10", dongle="device-a")
    private val endpoint = OpEndpoint("http://100.71.19.14:7000", "x".repeat(43))

    @Test fun manualAddressChangeBlocksOldUpdateTargetWithoutLosingKnownIdentity() {
        val edited=a.edited("Other LX3", "192.168.0.20", 7000)
        assertTrue(edited.identityNeedsCheck)
        assertEquals("device-a",edited.dongle)
        assertFalse(OpPairingBinding.allows(edited,"lx3","device-a",endpoint))
        assertFalse(OpPairingBinding.canBind(edited,endpoint,"device-a"))
    }
    @Test fun nameOnlyAndVerifiedDiscoveryPreserveIdentity() {
        assertFalse(a.edited("new name",a.ip,a.port).identityNeedsCheck)
        assertTrue(OpPairingBinding.allows(a.copy(ip="10.49.231.25"),"lx3","device-a",endpoint))
        assertTrue(a.edited("name",a.ip,7001).identityNeedsCheck)
    }
    @Test fun observedSamePhysicalDeviceRestoresItsExistingPairing() {
        val pending=a.edited(a.name,"192.168.0.20",7000)
        val verified=pending.observedIdentity(pending,"device-a")!!
        assertFalse(verified.identityNeedsCheck)
        assertTrue(OpPairingBinding.allows(verified,"lx3","device-a",endpoint))
    }
    @Test fun observedOtherLX3NeverInheritsOriginalDevicePairing() {
        val pending=a.edited(a.name,"192.168.0.20",7000)
        val verified=pending.observedIdentity(pending,"device-b")!!
        assertEquals("device-b",verified.dongle)
        assertFalse(OpPairingBinding.allows(verified,"lx3","device-a",endpoint))
        assertFalse(OpPairingBinding.canBind(verified,endpoint,"device-a"))
    }
    @Test fun unknownObservationNeverQualifiesAnEndpoint() {
        val pending=a.edited(a.name,"192.168.0.20",7000)
        for(value in listOf(null,"","UnregisteredDevice")) assertNull(pending.observedIdentity(pending,value))
        assertTrue(pending.identityNeedsCheck)
    }
    @Test fun observationCannotOverwriteAnEditDuringTheNetworkRequest() {
        val pending=a.edited(a.name,"192.168.0.20",7000)
        assertNull(pending.edited(a.name,"192.168.0.30",7000).observedIdentity(pending,"device-a"))
        assertNull(pending.copy(id="other").observedIdentity(pending,"device-a"))
        assertNull(pending.copy(port=7001).observedIdentity(pending,"device-a"))
        assertNull(pending.copy(identityNeedsCheck=false).observedIdentity(pending,"device-a"))
    }
    @Test fun oldStoredJsonNeedsNoMigrationAndPendingStateSurvivesRestart() {
        val legacy=JSONObject("""{"id":"lx3","ip":"192.168.0.10","dongle":"device-a"}""")
        assertFalse(Device.fromJson(legacy).identityNeedsCheck)
        val pending=a.edited(a.name,"192.168.0.20",7000)
        assertEquals(pending,Device.fromJson(JSONObject(pending.toJson().toString())))
        assertTrue(pending.edited("name only",pending.ip,pending.port).identityNeedsCheck)
    }
}
