package com.github.lxyan2333.bedrockminer.client.automate

import com.github.lxyan2333.bedrockminer.client.compat.MinecraftClientCompat
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraft.util.Mth
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walks the player along [PathFinder] routes, one waypoint at a time.
 *
 * Movement never waits for planning. Every tick the player walks toward the
 * next route cell — or, when no route exists, straight toward the goal, the
 * way a player closes on anything they can see. Planning only refines the
 * route around obstacles: a healthy plan is never second-guessed, a failed
 * plan never stops the legs, and the last stretch to any goal is always
 * walked directly with no plan at all. A progress watchdog reports
 * [Result.STUCK] so the caller picks a different goal instead of pushing
 * forever.
 */
object PlayerMover {
    /** Blocks per tick; vanilla walking is ~0.216, stay just below it. */
    private const val SPEED = 0.215
    private const val JUMP_VELOCITY = 0.42
    private const val NO_PROGRESS_TICKS = 40
    private const val PROGRESS_EPSILON = 0.02
    private const val JUMP_COOLDOWN_TICKS = 4
    /** Healthy plans are left alone at least this long. */
    private const val REPLAN_INTERVAL = 60
    /** After a failed or partial plan, walk greedily this long before planning again. */
    private const val REPLAN_RETRY = 20
    /** Within this range and planless, walk straight at the goal — no planning at all. */
    private const val GLIDE_RANGE = 4.0
    private const val WAYPOINT_REACH = 0.7

    enum class Result { IDLE, MOVING, ARRIVED, STUCK }

    private var target: Vec3? = null
    private var acceptRadius = 1.0
    private var maxDrop = 3
    private var verticalTolerance = 3.0
    private var lastRemaining = Double.MAX_VALUE
    private var noProgressTicks = 0
    private var tickCounter = 0L
    private var lastJumpTick = -100L

    private val path = ArrayList<BlockPos>()
    private var replanCooldown = 0

    /** The point currently walked to, for the action overlay. */
    val currentTarget: Vec3?
        get() = target

    /** Remaining waypoints of the active route, for the action overlay. */
    val currentPath: List<BlockPos>
        get() = if (target != null) path.toList() else emptyList()

    fun setTarget(pos: Vec3, radius: Double, maxDrop: Int = 3, verticalTolerance: Double = 3.0) {
        val current = target
        if (current == null || current.distanceToSqr(pos) > 1.0) {
            lastRemaining = Double.MAX_VALUE
            noProgressTicks = 0
            path.clear()
            replanCooldown = 0
        }
        target = pos
        acceptRadius = radius
        this.maxDrop = maxDrop
        this.verticalTolerance = verticalTolerance
    }

    fun clear() {
        target = null
        path.clear()
    }

    fun tick(level: Level, player: LocalPlayer): Result {
        tickCounter++
        val goal = target ?: return Result.IDLE
        val feet = player.position()
        val dx = goal.x - feet.x
        val dz = goal.z - feet.z
        val horizontal = sqrt(dx * dx + dz * dz)

        if (horizontal <= acceptRadius && abs(goal.y - feet.y) <= verticalTolerance) {
            player.setDeltaMovement(0.0, player.deltaMovement.y, 0.0)
            return Result.ARRIVED
        }

        replanCooldown--
        dropPassedWaypoints(feet)

        // Never walk blind over a dangerous edge: when planless, probe the
        // cell ahead — a 2+ drop into an inescapable pit (or past maxDrop)
        // demands a real plan, which routes around holes like the search
        // always does. If no route exists, stand at the rim; falling in is
        // never an option.
        val glideHazard = path.isEmpty() && horizontal > 1.0e-3 &&
            hazardAhead(level, feet, dx / horizontal, dz / horizontal)

        // Plan ONLY when something is genuinely wrong: the next step got
        // blocked by world changes, progress has truly stalled, the goal is
        // too far to walk at blindly, or a hole blocks the blind walk. A
        // healthy plan is never second-guessed, and planning never stops
        // the walking below.
        val planBroken = path.isNotEmpty() && !nextWaypointValid(level)
        val stalled = noProgressTicks >= 10 && replanCooldown <= 0
        val farWithoutPlan = path.isEmpty() && horizontal > GLIDE_RANGE && replanCooldown <= 0
        if (planBroken || stalled || farWithoutPlan || (glideHazard && replanCooldown <= 0)) {
            replan(level, player, goal)
            dropPassedWaypoints(feet)
        }
        if (glideHazard && path.isEmpty()) {
            // Hole ahead and no route around it yet: hold at the edge and
            // let the watchdog hand the goal back if this never resolves.
            player.setDeltaMovement(0.0, player.deltaMovement.y, 0.0)
            noProgressTicks++
            if (noProgressTicks > NO_PROGRESS_TICKS) {
                clear()
                return Result.STUCK
            }
            return Result.MOVING
        }

        // Steer at the next route cell — or straight at the goal when no
        // route exists. The last meter is ALWAYS walked directly: no plan,
        // no pause, exactly like a player closing on something they see.
        val cell = path.firstOrNull()
        val waypointX = if (cell != null) cell.x + 0.5 else goal.x
        val waypointY = if (cell != null) cell.y.toDouble() else goal.y
        val waypointZ = if (cell != null) cell.z + 0.5 else goal.z

        // Progress = remaining distance along the route (straight-line while
        // gliding). A curve around an obstacle still counts as progress;
        // only true wedging trips the watchdog.
        var remaining = run {
            val rx = waypointX - feet.x
            val rz = waypointZ - feet.z
            sqrt(rx * rx + rz * rz)
        }
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val sx = (b.x - a.x).toDouble()
            val sz = (b.z - a.z).toDouble()
            remaining += sqrt(sx * sx + sz * sz)
        }
        if (remaining < lastRemaining - PROGRESS_EPSILON) {
            noProgressTicks = 0
        } else {
            noProgressTicks++
            if (noProgressTicks > NO_PROGRESS_TICKS) {
                clear()
                return Result.STUCK
            }
        }
        lastRemaining = remaining

        val wx = waypointX - feet.x
        val wz = waypointZ - feet.z
        val wHorizontal = sqrt(wx * wx + wz * wz)
        if (wHorizontal > 1.0e-3) {
            var dirX = wx / wHorizontal
            var dirZ = wz / wHorizontal
            // When wedged on geometry, weave sideways a little to slip free.
            if (noProgressTicks in 8..NO_PROGRESS_TICKS) {
                val baseX = dirX
                val baseZ = dirZ
                val wiggle = sin(tickCounter * 0.9) * 0.35
                dirX = baseX - baseZ * wiggle
                dirZ = baseZ + baseX * wiggle
                val norm = sqrt(dirX * dirX + dirZ * dirZ)
                dirX /= norm
                dirZ /= norm
            }
            player.setDeltaMovement(dirX * SPEED, player.deltaMovement.y, dirZ * SPEED)

            // Human-looking head motion: ease toward the walking direction
            // with a slow wander, and gaze at a point well ahead near eye
            // level instead of staring at the ground. Cosmetic only.
            val wander = tickCounter.toDouble()
            val wanderYaw = (sin(wander * 0.11) * 5.0 + sin(wander * 0.031) * 3.5).toFloat()
            val targetYaw = Math.toDegrees(atan2(-dirX, dirZ)).toFloat() + wanderYaw
            val yawDelta = Mth.wrapDegrees(targetYaw - player.yRot)
            player.yRot = player.yRot + (yawDelta * 0.15f).coerceIn(-8f, 8f)

            val gazeCell = path.getOrNull(minOf(3, path.size - 1))
            val gazeX = if (gazeCell != null) gazeCell.x + 0.5 else waypointX
            val gazeY = (if (gazeCell != null) gazeCell.y.toDouble() else waypointY) + 1.4
            val gazeZ = if (gazeCell != null) gazeCell.z + 0.5 else waypointZ
            val gazeDx = gazeX - feet.x
            val gazeDz = gazeZ - feet.z
            val gazeDist = maxOf(2.0, sqrt(gazeDx * gazeDx + gazeDz * gazeDz))
            val gazeDy = gazeY - (feet.y + 1.62)
            val wanderPitch = (sin(wander * 0.17) * 2.0).toFloat()
            val targetPitch = Math.toDegrees(atan2(-gazeDy, gazeDist)).toFloat()
                .coerceIn(-18f, 22f) + wanderPitch
            player.xRot = player.xRot + ((targetPitch - player.xRot) * 0.10f).coerceIn(-4f, 4f)
        }

        // Jump when the route says "one up", or when we are pressed against
        // a step while the route does not lead downward — but ONLY if every
        // cell being pressed into is genuinely jumpable: at most one block
        // up with open headroom. A player cannot jump a 2-high wall, ever;
        // spamming jumps at one just burns time. On a diagonal walk BOTH
        // cardinal components are checked, since either face can be the one
        // actually blocking.
        val rise = waypointY - feet.y
        val stepJumpable = rise < 1.3 && wHorizontal > 1.0e-3 && run {
            val feetY = Mth.floor(feet.y + 0.001)
            val dirX = wx / wHorizontal
            val dirZ = wz / wHorizontal
            val ahead = ArrayList<BlockPos>(3)
            ahead.add(BlockPos(Mth.floor(feet.x + dirX * 0.8), feetY, Mth.floor(feet.z + dirZ * 0.8)))
            if (abs(dirX) > 0.25) {
                ahead.add(BlockPos(Mth.floor(feet.x + (if (dirX > 0) 0.8 else -0.8)), feetY, Mth.floor(feet.z)))
            }
            if (abs(dirZ) > 0.25) {
                ahead.add(BlockPos(Mth.floor(feet.x), feetY, Mth.floor(feet.z + (if (dirZ > 0) 0.8 else -0.8))))
            }
            ahead.all { cell ->
                // Open cells are no obstacle; a solid one must be a single
                // step with air for the body above its top to be jumpable.
                isPassable(level, cell) ||
                    (isPassable(level, cell.above()) && isPassable(level, cell.above(2)))
            }
        }
        if ((rise > 0.5 || (player.horizontalCollision && rise >= -0.4)) &&
            stepJumpable &&
            MinecraftClientCompat.isOnGround(player) &&
            tickCounter - lastJumpTick >= JUMP_COOLDOWN_TICKS
        ) {
            lastJumpTick = tickCounter
            val motion = player.deltaMovement
            player.setDeltaMovement(motion.x, JUMP_VELOCITY, motion.z)
        }
        return Result.MOVING
    }

    private fun dropPassedWaypoints(feet: Vec3) {
        // Advance past waypoints we are standing on — and past any corner
        // whose plane we already crossed: if the route continues beyond it,
        // never turn back.
        while (path.isNotEmpty() && reachedWaypoint(feet, path[0])) {
            path.removeAt(0)
        }
        while (path.size >= 2) {
            // Drop a corner ONLY when the player has moved past its plane
            // along the outgoing segment. Distance comparisons are wrong on
            // switchback routes and would cut corners through obstacles.
            val p0 = path[0]
            val p1 = path[1]
            val segX = (p1.x - p0.x).toDouble()
            val segZ = (p1.z - p0.z).toDouble()
            val relX = feet.x - (p0.x + 0.5)
            val relZ = feet.z - (p0.z + 0.5)
            if (relX * segX + relZ * segZ > 0.0 && abs(p0.y - feet.y) <= 1.2) {
                path.removeAt(0)
            } else {
                break
            }
        }
    }

    private fun replan(level: Level, player: LocalPlayer, goal: Vec3) {
        path.clear()
        // Straight line first: on open ground this is the whole plan,
        // computed instantly. An empty result means "close enough to walk
        // straight" — the glide covers it with no route at all.
        val direct = PathFinder.directWalk(
            level, player.blockPosition(), goal, acceptRadius, maxDrop, verticalTolerance,
        )
        if (direct != null) {
            path.addAll(direct)
            replanCooldown = REPLAN_INTERVAL
            return
        }
        val result = PathFinder.find(level, player.blockPosition(), goal, acceptRadius, maxDrop, verticalTolerance = verticalTolerance)
        if (result != null) {
            path.addAll(result.waypoints)
        }
        // Partial routes end early on purpose, and after a failed plan the
        // glide keeps walking toward the goal: retry sooner in both cases.
        replanCooldown = if (result?.reachedGoal == true) REPLAN_INTERVAL else REPLAN_RETRY
    }

    /**
     * True when the next cell in the walk direction is an open drop that
     * ends 2+ deep in an inescapable pit, or deeper than [maxDrop]. Used
     * only while gliding without a route — planned routes already refuse
     * such cells.
     */
    private fun hazardAhead(level: Level, feet: Vec3, dirX: Double, dirZ: Double): Boolean {
        val aheadX = Mth.floor(feet.x + dirX * 0.9)
        val aheadY = Mth.floor(feet.y + 0.001)
        val aheadZ = Mth.floor(feet.z + dirZ * 0.9)
        if (aheadX == Mth.floor(feet.x) && aheadZ == Mth.floor(feet.z)) return false
        val ahead = BlockPos(aheadX, aheadY, aheadZ)
        if (!isPassable(level, ahead)) return false // a wall, not a hole
        if (!isPassable(level, ahead.below())) return false // solid ground
        var probe = ahead.below()
        var depth = 1
        while (depth <= maxDrop) {
            if (!isPassable(level, probe.below())) {
                return depth >= 2 && PathFinder.isTrapCell(level, probe)
            }
            probe = probe.below()
            depth++
        }
        return true // bottomless within maxDrop: never step off blind
    }

    private fun nextWaypointValid(level: Level): Boolean {
        val waypoint = path.firstOrNull() ?: return false
        return isPassable(level, waypoint) && isPassable(level, waypoint.above())
    }

    private fun reachedWaypoint(feet: Vec3, waypoint: BlockPos): Boolean {
        val dx = waypoint.x + 0.5 - feet.x
        val dz = waypoint.z + 0.5 - feet.z
        if (dx * dx + dz * dz > WAYPOINT_REACH * WAYPOINT_REACH) return false
        val dy = feet.y - waypoint.y
        return dy > -0.35 && dy < 1.45
    }

    internal fun isPassable(level: Level, pos: BlockPos): Boolean {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
    }
}
