package com.github.lxyan2333.bedrockminer.client.area

import com.github.lxyan2333.bedrockminer.client.config.Configs
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

object AreaRestriction {
    data class Area(val pos1: BlockPos, val pos2: BlockPos) {
        val minX: Int get() = minOf(pos1.x, pos2.x)
        val minY: Int get() = minOf(pos1.y, pos2.y)
        val minZ: Int get() = minOf(pos1.z, pos2.z)
        val maxX: Int get() = maxOf(pos1.x, pos2.x)
        val maxY: Int get() = maxOf(pos1.y, pos2.y)
        val maxZ: Int get() = maxOf(pos1.z, pos2.z)

        fun contains(pos: BlockPos): Boolean {
            return pos.x in minX..maxX && pos.y in minY..maxY && pos.z in minZ..maxZ
        }

        fun distanceToSqr(pos: Vec3): Double {
            val dx = distanceToRange(pos.x, minX.toDouble(), maxX + 1.0)
            val dy = distanceToRange(pos.y, minY.toDouble(), maxY + 1.0)
            val dz = distanceToRange(pos.z, minZ.toDouble(), maxZ + 1.0)

            return dx * dx + dy * dy + dz * dz
        }
    }

    fun isValidArea(area: String): Boolean = parseArea(area) != null

    fun parseArea(area: String): Area? {
        return try {
            val parts = area.split(";")
            if (parts.size != 2) return null

            val coords1 = parseCoordinates(parts[0]) ?: return null
            val coords2 = parseCoordinates(parts[1]) ?: return null

            Area(
                BlockPos(coords1[0], coords1[1], coords1[2]),
                BlockPos(coords2[0], coords2[1], coords2[2]),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun configuredAreas(): Sequence<Area> {
        return Configs.Area.RESTRICT_MINING_AREA.strings.asSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseArea)
    }

    fun isPositionAllowed(pos: BlockPos): Boolean {
        return !Configs.Area.AREA_RESTRICTION_ENABLED.booleanValue || configuredAreas().any { it.contains(pos) }
    }

    private fun parseCoordinates(value: String): List<Int>? {
        val coords = value.split(",").map { it.trim().toInt() }
        return coords.takeIf { it.size == 3 }
    }

    private fun distanceToRange(value: Double, min: Double, max: Double): Double {
        return when {
            value < min -> min - value
            value > max -> value - max
            else -> 0.0
        }
    }
}
