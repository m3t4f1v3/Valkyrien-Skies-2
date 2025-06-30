package org.valkyrienskies.mod.compat

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

object LoadedMods {

    @JvmStatic
    val iris by CompatInfo("net.coderbot.iris.Iris")

    @JvmStatic
    val weather2 by CompatInfo("weather2.Weather")

    @JvmStatic
    val immersivePortals by CompatInfo("qouteall.imm_ptl.core.IPModMain")

    @JvmStatic
    val create by CompatInfo("com.simibubi.create.foundation.render.AllInstanceTypes")

    @JvmStatic
    val oldCreate by CompatInfo("com.simibubi.create.foundation.render.AllInstanceFormats")

    class CompatInfo(private val className: String) : ReadOnlyProperty<Any?, Boolean> {
        private var isLoaded: Boolean? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
            if (isLoaded == null) {
                isLoaded = try {
                    Class.forName(className, false, LoadedMods::class.java.classLoader)
                    true
                } catch (ex: ClassNotFoundException) {
                    false
                }
            }
            return isLoaded!!
        }
    }
}
