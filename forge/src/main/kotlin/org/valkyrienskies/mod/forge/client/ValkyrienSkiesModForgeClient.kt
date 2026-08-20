package org.valkyrienskies.mod.forge.client

import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import org.valkyrienskies.mod.common.ValkyrienSkiesMod

class ValkyrienSkiesModForgeClient {
    companion object {
        @JvmStatic
        fun clientInit(event: FMLClientSetupEvent) {
            // Put anything initialized on forge-side client here.
            ValkyrienSkiesMod.initClient()
            // Inert unless -Dvs.autotest names a script; see autotest/run.sh.
            org.valkyrienskies.mod.forge.autotest.VsAutoTestHarness.install()
        }
    }
}
