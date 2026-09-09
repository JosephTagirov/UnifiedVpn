package org.olcbox.app.provisioning

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SelfHostedProvisionerSecurityTest {
    @Test
    fun provisioningUsesImmutableReducedPrivilegeContainer() {
        val script = SelfHostedProvisioner().provisioningScriptForSecurityTest()

        assertContains(script, "IMAGE='$PINNED_AMNEZIA_WG_IMAGE'")
        assertContains(script, "--cap-drop=ALL")
        assertContains(script, "--pull=never")
        assertContains(script, "--cap-add=NET_ADMIN")
        assertContains(script, "--cap-add=SYS_MODULE")
        assertContains(script, "--security-opt=no-new-privileges")
        assertContains(script, "-v /lib/modules:/lib/modules:ro")
        assertFalse(script.contains(":latest"))
        assertFalse(script.contains("--privileged"))
    }
}
