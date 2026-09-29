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
 * The path already encodes what is physically possible (steps, drops, no
 * corner cutting), so steering is trivial: head for the next waypoint's
 * center, jump only when the waypoint is one block up, fall when it is
 * below. Plans are refreshed when the world changes under them, and a
 * no-progress watchdog still reports [Result.STUCK] so the caller can pick
 * a different goal instead of pushing forever.
 */
object PlayerMover {
    /** Blocks per tick; vanilla walking is ~0.216, stay just below it. */
    private const val SPEED = 0.21
    private const val JUMP_VELOCITY = 0.42
    private const val NO_PROGRESS_TICKS = 40
    private const val PROGRESS_EPSILON = 0.02
    private const val JUMP_COOLDOWN_TICKS = 6
    /** Re-plan at least this often, in case the terrain changed. */
    private const val REPLAN_INTERVAL = 40
    private const val WAYPOINT_REACH = 0.4
    /** Max body/head turn per tick — smooth, human-looking rotation. */
    private const val TURN_RATE = 14.0f

    enum class Result { IDLE, MOVING, ARRIVED, STUCK }

    private var target: Vec3? = null
    private var acceptRadius = 1.0
    private var maxDrop = 3
    private var bestDistance = Double.MAX_VALUE
    private var noProgressTicks = 0
    private var tickCounter = 0L
    private var lastJumpTick = -100L

    private val path = ArrayList<BlockPos>()
    private var replanCooldown = 0
    private var failedPlans = 0

    /** The point currently walked to, for the action overlay. */
    val currentTarget: Vec3?
        get() = target

    /** Remaining waypoints of the active route, for the action overlay. */
    val currentPath: List<BlockPos>
        get() = if (target != null) path.toList() else emptyList()

    fun setTarget(pos: Vec3, radius: Double, maxDrop: Int = 3) {
        val current = target
        if (current == null || current.distanceToSqr(pos) > 1.0) {
            bestDistance = Double.MAX_VALUE
            noProgressTicks = 0
            path.clear()
            replanCooldown = 0
            failedPlans = 0
        }
        target = pos
        acceptRadius = radius
        this.maxDrop = maxDrop
    }

    fun clear() {
        target = null
        path.clear()
    }

    fun tick(level: Level, player: LocalPlayer): Result {
        tickCounter++
        val t = target ?: return Result.IDLE
        val feet = player.position()
        val dx = t.x - feet.x
        val dz = t.z - feet.z
        val horizontal = sqrt(dx * dx + dz * dz)

        if (horizontal <= acceptRadius && abs(t.y - feet.y) <= 3.0) {
            player.setDeltaMovement(0.0, player.deltaMovement.y, 0.0)
            return Result.ARRIVED
        }

        if (horizontal < bestDistance - PROGRESS_EPSILON) {
            bestDistance = horizontal
            noProgressTicks = 0
        } else {
            noProgressTicks++
            // Halfway to giving up: throw the plan away and route fresh from
            // the exact spot we are wedged at.
            if (noProgressTicks == NO_PROGRESS_TICKS / 2) {
                path.clear()
                replanCooldown = 0
            }
            if (noProgressTicks > NO_PROGRESS_TICKS) {
                clear()
                return Result.STUCK
            }
        }

        replanCooldown--
        if (path.isEmpty() || replanCooldown <= 0 || !nextWaypointValid(level)) {
            replan(level, player, t)
            if (path.isEmpty()) {
                // No route at all: stand still and fail fast via the watchdog.
                player.setDeltaMovement(0.0, player.deltaMovement.y, 0.0)
                failedPlans++
                if (failedPlans >= 3) {
                    clear()
                    return Result.STUCK
                }
                noProgressTicks += 10
                return Result.MOVING
            }
            failedPlans = 0
        }

        // Advance past waypoints we are standing on.
        while (path.isNotEmpty() && reachedWaypoint(feet, path[0])) {
            path.removeAt(0)
        }
        val waypoint = path.firstOrNull()
        if (waypoint == null) {
            // Route exhausted but the target check above did not accept —
            // force a fresh plan next tick.
            replanCooldown = 0
            player.setDeltaMovement(0.0, player.deltaMovement.y, 0.0)
            return Result.MOVING
        }

        val wx = waypoint.x + 0.5 - feet.x
        val wz = waypoint.z + 0.5 - feet.z
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

            // Turn body and head smoothly toward the walking direction —
            // pure cosmetics on the local player; no packets, no process
            // logic depends on it (flows set their own rotations).
            val targetYaw = Math.toDegrees(atan2(-dirX, dirZ)).toFloat()
            val yawDelta = Mth.wrapDegrees(targetYaw - player.yRot)
            player.yRot = player.yRot + yawDelta.coerceIn(-TURN_RATE, TURN_RATE)
            val dy = (waypoint.y + 0.5) - (feet.y + 1.62)
            val targetPitch = Math.toDegrees(atan2(-dy, wHorizontal)).toFloat().coerceIn(-35f, 45f)
            val pitchDelta = targetPitch - player.xRot
            player.xRot = player.xRot + pitchDelta.coerceIn(-TURN_RATE / 2f, TURN_RATE / 2f)
        }

        // Jump when the route says "one up", or when we are pressed against
        // a step while the route does not lead downward.
        if ((waypoint.y > feet.y + 0.5 ||
                (player.horizontalCollision && waypoint.y >= feet.y - 0.4)) &&
            MinecraftClientCompat.isOnGround(player) &&
            tickCounter - lastJumpTick >= JUMP_COOLDOWN_TICKS
        ) {
            lastJumpTick = tickCounter
            val motion = player.deltaMovement
            player.setDeltaMovement(motion.x, JUMP_VELOCITY, motion.z)
        }
        return Result.MOVING
    }

    private fun replan(level: Level, player: LocalPlayer, goal: Vec3) {
        path.clear()
        val result = PathFinder.find(level, player.blockPosition(), goal, acceptRadius, maxDrop)
        if (result != null) {
            path.addAll(result.waypoints)
        }
        // Partial routes end early on purpose; re-plan sooner in that case.
        replanCooldown = if (result?.reachedGoal == true) REPLAN_INTERVAL else 15
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
