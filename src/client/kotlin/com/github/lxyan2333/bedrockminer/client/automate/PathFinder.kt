package com.github.lxyan2333.bedrockminer.client.automate

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Small-scale A* over standing cells. Legal moves are exactly what a player
 * can do: walk, step one block up (with headroom), drop a few blocks, and
 * diagonals only when both adjacent cardinal cells are open (no corner
 * cutting). Because the search knows the real moves, it finds routes greedy
 * steering never sees — like stepping down off a lone bedrock block — and it
 * never proposes impossible jumps or paths through blocks.
 */
object PathFinder {
    private const val MAX_NODES = 1200
    private const val HORIZONTAL_LIMIT = 20
    private const val UP_LIMIT = 8
    private const val DOWN_LIMIT = 10

    class Path(val waypoints: List<BlockPos>, val reachedGoal: Boolean)

    private data class OpenEntry(val pos: BlockPos, val f: Double)

    /**
     * Route from [start] toward [goal]. Success = a cell whose center is
     * within [acceptRadius] horizontally and ±3 vertically of the goal.
     * When the goal is out of reach, the path to the closest explored cell is
     * returned (marked `reachedGoal = false`) so the caller still makes
     * progress before re-planning; null only when not even that exists.
     */
    fun find(
        level: Level,
        start: BlockPos,
        goal: Vec3,
        acceptRadius: Double,
        maxDrop: Int,
        maxNodes: Int = MAX_NODES,
        verticalTolerance: Double = 3.5,
    ): Path? {
        val open = PriorityQueue<OpenEntry>(compareBy { it.f })
        val gScore = HashMap<BlockPos, Double>()
        val cameFrom = HashMap<BlockPos, BlockPos>()

        // Snap a floating/edge start (player standing on a block corner) to
        // the nearest real standing cell, so the search never begins dead.
        @Suppress("NAME_SHADOWING")
        val start = resolveStart(level, start)

        val startH = heuristic(start, goal)
        gScore[start] = 0.0
        open.add(OpenEntry(start, startH))

        var best = start
        var bestH = startH
        var expanded = 0

        while (open.isNotEmpty() && expanded < maxNodes) {
            val current = open.poll().pos
            val currentG = gScore[current] ?: continue
            expanded++

            if (isGoal(current, goal, acceptRadius, verticalTolerance)) {
                return Path(reconstruct(cameFrom, current), true)
            }
            val h = heuristic(current, goal)
            if (h < bestH) {
                bestH = h
                best = current
            }

            forEachNeighbor(level, start, current, maxDrop) { neighbor, cost ->
                val tentative = currentG + cost
                if (tentative < (gScore[neighbor] ?: Double.MAX_VALUE)) {
                    gScore[neighbor] = tentative
                    cameFrom[neighbor] = current
                    open.add(OpenEntry(neighbor, tentative + heuristic(neighbor, goal)))
                }
            }
        }

        // Partial result: only worth it when it actually gets closer.
        if (best != start && bestH < startH - 0.6) {
            return Path(reconstruct(cameFrom, best), false)
        }
        return null
    }

    /**
     * Instant straight-line plan: samples the direct line to the goal and
     * follows the terrain (steps up one, drops safely). On open ground this
     * replaces the whole search with a microsecond check, so the mover just
     * GOES. Returns null only when the straight walk genuinely does not work.
     */
    fun directWalk(
        level: Level,
        start: BlockPos,
        goal: Vec3,
        acceptRadius: Double,
        maxDrop: Int,
        verticalTolerance: Double,
    ): List<BlockPos>? {
        val origin = resolveStart(level, start)
        val sx = origin.x + 0.5
        val sz = origin.z + 0.5
        val dx = goal.x - sx
        val dz = goal.z - sz
        val distance = sqrt(dx * dx + dz * dz)
        if (distance > 24.0) return null
        if (distance < 0.2) return emptyList()

        val steps = maxOf(1, kotlin.math.ceil(distance / 0.35).toInt())
        val cells = ArrayList<BlockPos>()
        var last = origin
        var curY = origin.y
        for (i in 1..steps) {
            val px = sx + dx * i / steps
            val pz = sz + dz * i / steps
            val cx = kotlin.math.floor(px).toInt()
            val cz = kotlin.math.floor(pz).toInt()
            if (cx == last.x && cz == last.z) continue
            // Diagonal cell change: both cardinal corners must be open.
            if (cx != last.x && cz != last.z) {
                val cornerA = BlockPos(cx, curY, last.z)
                val cornerB = BlockPos(last.x, curY, cz)
                if (!PlayerMover.isPassable(level, cornerA) || !PlayerMover.isPassable(level, cornerA.above())) return null
                if (!PlayerMover.isPassable(level, cornerB) || !PlayerMover.isPassable(level, cornerB.above())) return null
            }
            var cell = BlockPos(cx, curY, cz)
            if (!isStandable(level, cell)) {
                val up = cell.above()
                if (isStandable(level, up) && PlayerMover.isPassable(level, BlockPos(last.x, curY + 2, last.z))) {
                    cell = up
                } else {
                    // Not a step: must be an open column we can drop through.
                    if (!PlayerMover.isPassable(level, cell) || !PlayerMover.isPassable(level, cell.above())) return null
                    var probe = cell
                    var depth = 0
                    var landing: BlockPos? = null
                    while (depth < maxDrop) {
                        val below = probe.below()
                        if (!PlayerMover.isPassable(level, below)) {
                            landing = probe
                            break
                        }
                        probe = below
                        depth++
                        if (!PlayerMover.isPassable(level, probe.above())) return null
                    }
                    if (landing == null) return null
                    if (depth >= 2 && isTrapCell(level, landing)) return null
                    cell = landing
                }
            }
            curY = cell.y
            last = cell
            cells.add(cell)
        }
        if (!isGoal(last, goal, acceptRadius, verticalTolerance)) return null
        return simplify(cells)
    }

    /** Whether a full route to the goal exists (used to pre-validate item trips). */
    fun canReach(
        level: Level, start: BlockPos, goal: Vec3, acceptRadius: Double, maxDrop: Int,
        verticalTolerance: Double = 3.5,
    ): Boolean {
        return find(level, start, goal, acceptRadius, maxDrop, maxNodes = 600, verticalTolerance = verticalTolerance)
            ?.reachedGoal == true
    }

    private fun isGoal(pos: BlockPos, goal: Vec3, acceptRadius: Double, verticalTolerance: Double): Boolean {
        val dx = pos.x + 0.5 - goal.x
        val dz = pos.z + 0.5 - goal.z
        if (dx * dx + dz * dz > (acceptRadius + 0.25) * (acceptRadius + 0.25)) return false
        val dy = goal.y - pos.y
        return dy > -verticalTolerance && dy < verticalTolerance
    }

    private fun heuristic(pos: BlockPos, goal: Vec3): Double {
        val dx = pos.x + 0.5 - goal.x
        val dz = pos.z + 0.5 - goal.z
        return sqrt(dx * dx + dz * dz) + 0.5 * abs(pos.y - goal.y)
    }

    private fun reconstruct(cameFrom: Map<BlockPos, BlockPos>, end: BlockPos): List<BlockPos> {
        val result = ArrayList<BlockPos>()
        var cursor: BlockPos? = end
        while (cursor != null) {
            result.add(cursor)
            cursor = cameFrom[cursor]
        }
        result.reverse()
        // Drop the start cell — the player already stands there.
        if (result.isNotEmpty()) result.removeAt(0)
        return simplify(result)
    }

    /**
     * Merge straight same-height runs into single long segments: one clear
     * line to walk, nothing to dither over. Turning points and every height
     * change are kept, so jumps and drops still happen exactly on cue.
     */
    private fun simplify(points: List<BlockPos>): List<BlockPos> {
        if (points.size <= 2) return points
        val result = ArrayList<BlockPos>(points.size)
        for (i in points.indices) {
            if (i == 0 || i == points.size - 1) {
                result.add(points[i])
                continue
            }
            val previous = points[i - 1]
            val current = points[i]
            val next = points[i + 1]
            val sameDirection =
                next.x - current.x == current.x - previous.x &&
                    next.z - current.z == current.z - previous.z &&
                    next.y == current.y && current.y == previous.y
            if (!sameDirection) result.add(current)
        }
        return result
    }

    /**
     * A cell with no exit on any side: every neighbor is a 2-high wall, so
     * neither walking out nor a one-block jump can leave it. The pathfinder
     * never drops into such pits and the autopilot pillars out of them.
     */
    fun isTrapCell(level: Level, cell: BlockPos): Boolean {
        for ((dx, dz) in CARDINALS) {
            val side = BlockPos(cell.x + dx, cell.y, cell.z + dz)
            // Walk out on the same level.
            if (PlayerMover.isPassable(level, side) && PlayerMover.isPassable(level, side.above())) return false
            // Jump out over a one-block step (needs headroom above the cell).
            if (!PlayerMover.isPassable(level, side) &&
                PlayerMover.isPassable(level, side.above()) &&
                PlayerMover.isPassable(level, side.above(2)) &&
                PlayerMover.isPassable(level, cell.above(2))
            ) {
                return false
            }
        }
        return true
    }

    private fun resolveStart(level: Level, start: BlockPos): BlockPos {
        if (isStandable(level, start)) return start
        // Below (falling / standing on an edge with the cell itself floating).
        for (i in 1..2) {
            val below = start.below(i)
            if (isStandable(level, below)) return below
        }
        for ((dx, dz) in CARDINALS) {
            val side = start.offset(dx, 0, dz)
            if (isStandable(level, side)) return side
            if (isStandable(level, side.below())) return side.below()
        }
        return start
    }

    /** A cell the player can stand in: room for the body, solid ground below. */
    private fun isStandable(level: Level, pos: BlockPos): Boolean {
        return PlayerMover.isPassable(level, pos) &&
            PlayerMover.isPassable(level, pos.above()) &&
            !PlayerMover.isPassable(level, pos.below())
    }

    private inline fun forEachNeighbor(
        level: Level,
        origin: BlockPos,
        current: BlockPos,
        maxDrop: Int,
        visit: (BlockPos, Double) -> Unit,
    ) {
        // Cardinal moves: level, one step up (needs headroom), or a drop.
        for ((dx, dz) in CARDINALS) {
            val nx = current.x + dx
            val nz = current.z + dz
            if (abs(nx - origin.x) > HORIZONTAL_LIMIT || abs(nz - origin.z) > HORIZONTAL_LIMIT) continue

            val flat = BlockPos(nx, current.y, nz)
            if (isStandable(level, flat)) {
                visit(flat, 1.0)
                continue
            }

            // Step up: target cell one higher, and headroom above the head.
            val up = flat.above()
            if (current.y - origin.y < UP_LIMIT &&
                isStandable(level, up) &&
                PlayerMover.isPassable(level, current.above(2))
            ) {
                visit(up, 1.8)
                continue
            }

            // Drop: walk over the edge and fall onto the first solid ground.
            if (PlayerMover.isPassable(level, flat) && PlayerMover.isPassable(level, flat.above())) {
                var depth = 1
                var cell = flat.below()
                while (depth <= maxDrop && origin.y - cell.y <= DOWN_LIMIT) {
                    if (!PlayerMover.isPassable(level, cell)) break // fell into a wall: invalid
                    if (!PlayerMover.isPassable(level, cell.below())) {
                        // Never drop 2+ into a pit that has no way back out.
                        if (depth < 2 || !isTrapCell(level, cell)) {
                            visit(cell, 1.0 + 0.3 * depth)
                        }
                        break
                    }
                    cell = cell.below()
                    depth++
                }
            }
        }

        // Diagonals: same level only, and only when both cardinal corners are
        // open at body height — corner cutting is what snags the hitbox.
        for ((dx, dz) in DIAGONALS) {
            val nx = current.x + dx
            val nz = current.z + dz
            if (abs(nx - origin.x) > HORIZONTAL_LIMIT || abs(nz - origin.z) > HORIZONTAL_LIMIT) continue
            val diagonal = BlockPos(nx, current.y, nz)
            if (!isStandable(level, diagonal)) continue
            val sideA = BlockPos(current.x + dx, current.y, current.z)
            val sideB = BlockPos(current.x, current.y, current.z + dz)
            if (!PlayerMover.isPassable(level, sideA) || !PlayerMover.isPassable(level, sideA.above())) continue
            if (!PlayerMover.isPassable(level, sideB) || !PlayerMover.isPassable(level, sideB.above())) continue
            visit(diagonal, 1.45)
        }
    }

    private val CARDINALS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val DIAGONALS = arrayOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
}
