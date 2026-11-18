package dev.app8.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A localAsset name not in the app's asset map falls back to the host's drawable of that name. */
class LocalAssetNameTest {
    @Test fun assetNamesMapToDrawableNames() {
        assertEquals("logo_black", drawableName("logo-black"))
        assertEquals("logo_black", drawableName("logo-black.png"))
        assertEquals("stripe", drawableName("images/Stripe.jpg"))
        assertNull(drawableName(""))
    }

    @Test fun aLocalAssetRefIsMarkedLocal() {
        assertEquals(AssetRef(null, "stripe", null, local = true).local, true)
        assertEquals(AssetRef(null, "stripe", null).local, false)
    }
}
