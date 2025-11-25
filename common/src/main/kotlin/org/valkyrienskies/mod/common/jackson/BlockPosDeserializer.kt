package org.valkyrienskies.mod.common.jackson

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.node.DoubleNode
import net.minecraft.core.BlockPos
import java.io.IOException

class BlockPosDeserializer : JsonDeserializer<BlockPos>() {
    @Throws(IOException::class)
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): BlockPos {
        val node = p.getCodec().readTree<TreeNode>(p)

        // Probably a tider way to do this but meh
        val centerX = (node["centerX"] as DoubleNode).numberValue() as Double
        val centerY = (node["centerY"] as DoubleNode).numberValue() as Double
        val centerZ = (node["centerZ"] as DoubleNode).numberValue() as Double

        return BlockPos.containing(centerX, centerY, centerZ)
    }
}
