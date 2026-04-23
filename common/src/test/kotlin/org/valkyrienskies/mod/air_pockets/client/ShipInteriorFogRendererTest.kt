package org.valkyrienskies.mod.air_pockets.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShipInteriorFogRendererTest {
    @Test
    fun `interior fog only renders when air pocket also suppresses world fluid`() {
        assertTrue(ShipInteriorFogRenderer.shouldRenderInteriorWaterFog(true, true))
        assertFalse(ShipInteriorFogRenderer.shouldRenderInteriorWaterFog(true, false))
        assertFalse(ShipInteriorFogRenderer.shouldRenderInteriorWaterFog(false, true))
        assertFalse(ShipInteriorFogRenderer.shouldRenderInteriorWaterFog(false, false))
    }
}
