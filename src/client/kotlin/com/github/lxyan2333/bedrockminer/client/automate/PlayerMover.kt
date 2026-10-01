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
    /** Blocks per tick while sprinting; vanilla sprint is ~0.28. */
    private const val SPRINT_SPEED = 0.27
    /** Sprint on any leg longer than this — players sprint everywhere. */
    private const val SPRINT_DISTANCE = 4.0
    /** Horizontal speed while airborne in a jump: clears the step lip,
     *  lands ON the aimed cell instead of flying past it. */
    private const val AIR_SPEED = 0.19
    /** Horizontal speed while falling a drop: nearly straight down, so
     *  the landing is exactly the cell the route chose. */
    private const val AIR_DROP_SPEED = 0.11
    private const val JUMP_VELOCITY = 0.42
    private const val NO_PROGRESS_TICKS = 40
    private const val PROGRESS_EPSILON = 0.02
    private const val JUMP_COOLDOWN_TICKS = 4
    private const val WAYPOINT_REACH = 0.7
    /** Consecutive failed plans toward a far goal before giving it up. */
    private const val MAX_PLAN_FAILURES = 2

    enum class Result { IDLE, MOVING, ARRIVED, STUCK }

    private var target: Vec3? = null
    private var acceptRadius = 1.0
    private var maxDrop = 3
    private var verticalTolerance = 3.0
    private var lastRemaining = Double.MAX_VALUE
    private var noProgressTicks = 0
    private var tickCounter = 0L
    private var lastJumpTick = -100L
    private var lastPassageReplanTick = -100L

    /** Cells that recently defeated the walk in PRACTICE (stalls), with an
     *  expiry tick: plans route around them, picking the second-best way. */
    private val avoidCells = HashMap<BlockPos, Long>()
    private const val AVOID_TTL = 300L

    private val path = ArrayList<BlockPos>()
    private var planFailures = 0

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
            planFailures = 0
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
            player.isSprinting = false
            return Result.ARRIVED
        }

        dropPassedWaypoints(feet)

        // The last stretch to the goal is walked directly with no route —
        // routes are made of cell centers, the goal is an exact point.
        val glideRange = maxOf(1.5, acceptRadius + 1.2)

        // ROUTE FIRST, ALWAYS. Beyond the last stretch the player never
        // walks blind: any missing route is computed on the spot (it costs
        // microseconds), a route broken by world changes is rebuilt, and a
        // route that stops making progress is re-solved at fixed marks
        // while the sideways wiggle tries to slip free between them.
        // A goal up or down a ledge is NEVER walked at blind — only the
        // router knows where the stairs are. Blind walking is for flat,
        // close, open ground and nothing else.
        val goalOffLevel = abs(goal.y - feet.y) > 1.2
        val needPlan = path.isEmpty() && (horizontal > glideRange || goalOffLevel)
        val planBroken = path.isNotEmpty() && !nextWaypointValid(level, feet)
        val stalled = noProgressTicks == 4 || noProgressTicks == 16 || noProgressTicks == 28
        if (needPlan || planBroken || stalled) {
            // A stall means the simple route did not survive contact with
            // the terrain: remember the exact step that defeated the walk,
            // then go straight to the full search — which now prices that
            // step out and picks the SECOND way around, never handing back
            // the same route that just failed.
            if (stalled) {
                path.firstOrNull()?.let { avoidCells[it] = tickCounter + AVOID_TTL }
            }
            replan(level, player, goal, allowDirect = noProgressTicks < 4)
            dropPassedWaypoints(feet)
            if (path.isEmpty() && (horizontal > glideRange || goalOffLevel)) {
                // No route exists from here. Give the goal back fast so the
                // caller picks a different one — no standing, no wishing.
                planFailures++
                if (planFailures >= MAX_PLAN_FAILURES) {
                    clear()
                    return Result.STUCK
                }
            } else {
                planFailures = 0
            }
        }

        // Even on the short final stretch, never walk blind over a
        // dangerous edge: probe the ground ahead and route around any
        // inescapable pit instead of falling in.
        if (path.isEmpty() && horizontal > 1.0e-3 &&
            hazardAhead(level, feet, dx / horizontal, dz / horizontal)
        ) {
            replan(level, player, goal, allowDirect = false)
            dropPassedWaypoints(feet)
            if (path.isEmpty()) {
                // Hole ahead and no route around it: hold at the edge and
                // give the goal up quickly rather than ever stepping in.
                player.setDeltaMovement(0.0, player.deltaMovement.y, 0.0)
                noProgressTicks += 4
                if (noProgressTicks > NO_PROGRESS_TICKS) {
                    clear()
                    return Result.STUCK
                }
                return Result.MOVING
            }
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

        // Look at what the body is about to enter BEFORE moving: solid
        // steps (jump candidates), 2-high walls (never jumpable), and the
        // sneaky one-block gap — feet fit, head does not, body never
        // passes. On a diagonal walk BOTH cardinal faces are checked.
        val rise = waypointY - feet.y
        val wx = waypointX - feet.x
        val wz = waypointZ - feet.z
        val wHorizontal = sqrt(wx * wx + wz * wz)
        var blockedAhead = false
        var stepJumpable = true
        var headBlockedAhead = false
        if (wHorizontal > 1.0e-3) {
            val feetY = Mth.floor(feet.y + 0.001)
            val aheadDirX = wx / wHorizontal
            val aheadDirZ = wz / wHorizontal
            val ahead = ArrayList<BlockPos>(3)
            ahead.add(BlockPos(Mth.floor(feet.x + aheadDirX * 0.8), feetY, Mth.floor(feet.z + aheadDirZ * 0.8)))
            if (abs(aheadDirX) > 0.25) {
                ahead.add(BlockPos(Mth.floor(feet.x + (if (aheadDirX > 0) 0.8 else -0.8)), feetY, Mth.floor(feet.z)))
            }
            if (abs(aheadDirZ) > 0.25) {
                ahead.add(BlockPos(Mth.floor(feet.x), feetY, Mth.floor(feet.z + (if (aheadDirZ > 0) 0.8 else -0.8))))
            }
            for (cell in ahead) {
                val feetOpen = isPassable(level, cell)
                val headOpen = isPassable(level, cell.above())
                if (!feetOpen) {
                    blockedAhead = true
                    if (!headOpen || !isPassable(level, cell.above(2))) {
                        stepJumpable = false
                    }
                } else if (!headOpen) {
                    headBlockedAhead = true
                }
            }
        }

        // The route wants UP but the face ahead is no longer one jumpable
        // step: the plan is stale. Mark the spot as failed and rebuild
        // around it RIGHT NOW — the forbidden-cell cost makes the next
        // plan take the second path instead of re-picking this climb.
        if (path.isNotEmpty() && rise > 0.5 && blockedAhead && !stepJumpable) {
            path.firstOrNull()?.let { avoidCells[it] = tickCounter + AVOID_TTL }
            if (tickCounter - lastPassageReplanTick >= 8) {
                lastPassageReplanTick = tickCounter
                replan(level, player, goal, allowDirect = false)
                dropPassedWaypoints(feet)
            }
        }

        // A face the body cannot pass — 2-high wall, or the one-block gap
        // with a blocked head — is NEVER pushed against. Route around it;
        // and if no route exists, hold still and give the goal back fast
        // instead of grinding the wall.
        val faceBlocked = headBlockedAhead || (blockedAhead && !stepJumpable)
        if (faceBlocked && path.isEmpty()) {
            if (tickCounter - lastPassageReplanTick >= 8) {
                lastPassageReplanTick = tickCounter
                replan(level, player, goal, allowDirect = false)
                dropPassedWaypoints(feet)
            }
            if (path.isEmpty()) {
                player.setDeltaMovement(0.0, player.deltaMovement.y, 0.0)
                player.isSprinting = false
                noProgressTicks += 4
                if (noProgressTicks > NO_PROGRESS_TICKS) {
                    clear()
                    return Result.STUCK
                }
                return Result.MOVING
            }
        }

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
            // Speed discipline is what makes parkour land: sprint only on
            // flat clear ground, approach steps at a walk so the jump arc
            // is short and controlled, and while airborne steer gently —
            // drops fall nearly straight onto the cell the route chose.
            val onGround = MinecraftClientCompat.isOnGround(player)
            val sprinting = onGround && remaining > SPRINT_DISTANCE &&
                noProgressTicks < 8 && rise <= 0.5 && !blockedAhead && !headBlockedAhead
            player.isSprinting = sprinting
            val speed = when {
                !onGround && rise < -0.5 -> AIR_DROP_SPEED
                !onGround -> AIR_SPEED
                sprinting -> SPRINT_SPEED
                else -> SPEED
            }
            player.setDeltaMovement(dirX * speed, player.deltaMovement.y, dirZ * speed)

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

        // Jump ONLY when a genuine single step is physically in front of
        // the feet — one block up with open headroom — and never early:
        // jumping before the base wastes the arc and looks like failing
        // the climb. A 2-high wall or a head-blocked gap is never jumped
        // at from any angle.
        if (blockedAhead && stepJumpable && !headBlockedAhead && rise < 1.3 &&
            (rise > 0.5 || (player.horizontalCollision && rise >= -0.4)) &&
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
            // Passing the plane counts only up close: dropping a corner
            // from afar cuts the corner cell short and snags the hitbox.
            val nearCorner = relX * relX + relZ * relZ < 1.25 * 1.25
            if (nearCorner && relX * segX + relZ * segZ > 0.0 && abs(p0.y - feet.y) <= 1.2) {
                path.removeAt(0)
            } else {
                break
            }
        }
    }

    private fun replan(level: Level, player: LocalPlayer, goal: Vec3, allowDirect: Boolean = true) {
        path.clear()
        avoidCells.values.removeIf { it < tickCounter }
        val avoid = if (avoidCells.isEmpty()) emptySet() else avoidCells.keys.toSet()
        // A verified-clear straight line is the ideal route and costs
        // almost nothing to check; everything else goes to the search,
        // which knows every legal move and routes AROUND obstacles —
        // walls are walked past, never walked into.
        if (allowDirect) {
            val direct = PathFinder.directWalk(
                level, player.blockPosition(), goal, acceptRadius, maxDrop, verticalTolerance, avoid,
            )
            if (direct != null) {
                path.addAll(direct)
                return
            }
        }
        val result = PathFinder.find(
            level, player.blockPosition(), goal, acceptRadius, maxDrop,
            verticalTolerance = verticalTolerance, avoid = avoid,
        )
        if (result != null) {
            path.addAll(result.waypoints)
        }
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

    private fun nextWaypointValid(level: Level, feet: Vec3): Boolean {
        val waypoint = path.firstOrNull() ?: return false
        if (!isPassable(level, waypoint) || !isPassable(level, waypoint.above())) return false
        // A step UP must still be climbable from here: the world changes
        // constantly while mining, and a jump with no headroom over our
        // own head is just a shove into the wall.
        if (waypoint.y > Mth.floor(feet.y + 0.001)) {
            val headroom = BlockPos(Mth.floor(feet.x), Mth.floor(feet.y + 0.001) + 2, Mth.floor(feet.z))
            if (!isPassable(level, headroom)) return false
        }
        return true
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
