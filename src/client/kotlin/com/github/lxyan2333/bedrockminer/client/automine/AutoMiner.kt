package com.github.lxyan2333.bedrockminer.client.automine

import com.github.lxyan2333.bedrockminer.client.area.AreaRestriction
import com.github.lxyan2333.bedrockminer.client.breaking.BreakingFlow
import com.github.lxyan2333.bedrockminer.client.breaking.BreakingFlowController
import com.github.lxyan2333.bedrockminer.client.breaking.InventoryManager
import com.github.lxyan2333.bedrockminer.client.breaking.approach.ApproachBase
import com.github.lxyan2333.bedrockminer.client.compat.BlocksCompat
import com.github.lxyan2333.bedrockminer.client.compat.MinecraftClientCompat
import com.github.lxyan2333.bedrockminer.client.config.Configs
import com.github.lxyan2333.bedrockminer.client.message.Messager
import fi.dy.masa.malilib.config.options.ConfigString
import fi.dy.masa.malilib.util.StringUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB

/**
 * Continuously breaks the configured target blocks (bedrock by default) inside
 * the box spanned by `Configs.AutoMine.POS1` / `POS2`, without the player
 * having to click on them.
 *
 * It behaves like a player would in survival:
 * - only blocks inside the box are ever touched, the box is never left;
 * - only blocks within the player's block interaction range are considered;
 * - a block is only enqueued if a valid piston approach exists right now
 *   (adjacent free space, line of sight to the piston/torch positions, ...);
 * - nearest blocks first, one breaking flow at a time by default;
 * - blocks that failed (no approach / breaking failed) are put on a cooldown
 *   instead of being retried every scan;
 * - blocks the player is standing on or inside are skipped by default.
 *
 * All the existing checks still apply: server allow/block lists, the special
 * block list, the "mining area restriction" and the required item check.
 */
object AutoMiner {
    /** Blocks around the player's eyes that are scanned. The real interaction range is applied afterwards. */
    private const val SCAN_RADIUS = 7

    /** Floor for the per-scan budget of (comparatively expensive) approach searches. */
    private const val MIN_APPROACH_CHECKS_PER_SCAN = 4

    private var tick = 0L
    private val cooldowns = HashMap<BlockPos, Long>()
    private val launchedFlows = ArrayList<BreakingFlow>()

    /** Reachable targets found by the last scan, nearest first; consumed between scans. */
    private val pendingTargets = ArrayDeque<BlockPos>()

    /** -1 (not MIN_VALUE): `tick - lastLaunchTick` must not overflow. */
    private var lastLaunchTick = -1L

    /** While true (set by AutoPilot between mining phases), no new flows are launched. */
    var launchesSuppressed = false

    private var scanRequested = false

    /** Scan on the next tick instead of waiting for the interval (e.g. after relocating). */
    fun requestScan() {
        scanRequested = true
    }

    val enabled: Boolean
        get() = Configs.AutoMine.ENABLED.booleanValue

    // -- positions --

    fun parsePos(value: String): BlockPos? {
        val parts = value.split(",").map { it.trim() }
        if (parts.size != 3) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        val z = parts[2].toIntOrNull() ?: return null
        return BlockPos(x, y, z)
    }

    /** Empty is valid (= not set yet). */
    fun isValidPosString(value: String): Boolean = value.isEmpty() || parsePos(value) != null

    fun pos1(): BlockPos? = parsePos(Configs.AutoMine.POS1.stringValue)

    fun pos2(): BlockPos? = parsePos(Configs.AutoMine.POS2.stringValue)

    /** The configured box, or null unless both positions are set. */
    fun area(): AreaRestriction.Area? {
        val p1 = pos1() ?: return null
        val p2 = pos2() ?: return null
        return AreaRestriction.Area(p1, p2)
    }

    /** Like [area], but falls back to a single-block box when only one position is set (for rendering). */
    fun previewArea(): AreaRestriction.Area? {
        val p1 = pos1()
        val p2 = pos2()
        return when {
            p1 != null && p2 != null -> AreaRestriction.Area(p1, p2)
            p1 != null -> AreaRestriction.Area(p1, p1)
            p2 != null -> AreaRestriction.Area(p2, p2)
            else -> null
        }
    }

    fun setPos1Here() = setPosHere(Configs.AutoMine.POS1, 1)

    fun setPos2Here() = setPosHere(Configs.AutoMine.POS2, 2)

    private fun setPosHere(config: ConfigString, index: Int) {
        val player = Minecraft.getInstance().player ?: return
        val pos = player.blockPosition()
        config.setValueFromString("${pos.x},${pos.y},${pos.z}")
        Messager.actionBar(
            StringUtils.translate("bedrockminer.message.automine.pos_set", index, pos.x, pos.y, pos.z)
        )
    }

    fun shouldRenderBox(): Boolean {
        // The box stays visible whenever positions are set (the renderer checks
        // that), even while Auto Mine is off; SHOW_BOX hides it entirely.
        return Configs.AutoMine.SHOW_BOX.booleanValue
    }

    // -- lifecycle --

    fun onToggled(nowEnabled: Boolean) {
        reset()
        if (nowEnabled) {
            // Auto mine needs the main breaking toggle; switch it on for the user.
            if (!BreakingFlowController.enabled) {
                Configs.Generic.BEDROCK_MINER_ENABLED.booleanValue = true
            }
            if (area() == null) {
                Messager.actionBar(StringUtils.translate("bedrockminer.message.automine.no_area"))
            } else {
                Messager.actionBar(StringUtils.translate("bedrockminer.message.automine.started"))
            }
        } else {
            Messager.actionBar(StringUtils.translate("bedrockminer.message.automine.stopped"))
        }
    }

    /** Called when the main Bedrock Miner toggle is switched off. */
    fun onMainDisabled() {
        if (Configs.AutoMine.ENABLED.booleanValue) {
            Configs.AutoMine.ENABLED.booleanValue = false
        }
    }

    /** Forget cooldowns and tracked flows (level change, disconnect, toggle). */
    fun reset() {
        cooldowns.clear()
        launchedFlows.clear()
        pendingTargets.clear()
        lastLaunchTick = -1L
    }

    // -- per tick --

    fun onTick() {
        tick++
        if (!enabled) return
        if (!BreakingFlowController.enabled) return

        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val player = client.player ?: return
        if (launchesSuppressed) return

        val interval = Configs.AutoMine.SCAN_INTERVAL.integerValue
        if (tick % interval == 0L || scanRequested) {
            scanRequested = false
            retireFinishedFlows(level)
            cooldowns.entries.removeIf { it.value <= tick }

            pendingTargets.clear()
            val area = area()
            if (area != null) {
                pendingTargets.addAll(findCandidates(level, player, area))
            }
        }
        if (pendingTargets.isEmpty()) return

        val maxConcurrent = Configs.AutoMine.MAX_CONCURRENT.integerValue
        if (BreakingFlowController.activeFlows.size >= maxConcurrent) return

        // Stagger new piston setups: with a delay of e.g. 2 only one setup is
        // started every 2 ticks instead of all of them in the same tick.
        val launchDelay = Configs.AutoMine.LAUNCH_DELAY.integerValue
        if (launchDelay > 0 && tick - lastLaunchTick < launchDelay) return

        val missing = InventoryManager.checkRequiredItems()
        if (missing != null) {
            // Keep the reason on the action bar while auto mine is waiting for items.
            Messager.actionBar(missing)
            return
        }

        val failCooldown = Configs.AutoMine.FAIL_COOLDOWN.integerValue
        // Scale the search budget with the concurrency limit so a high limit
        // can actually be filled within a few scans.
        val maxApproachChecks = maxOf(MIN_APPROACH_CHECKS_PER_SCAN, maxConcurrent + 2)
        var approachChecks = 0
        // Normal blocks that must wait for the piston flows — set aside so
        // they never block piston candidates queued behind them.
        val waitingNormals = ArrayList<BlockPos>()
        while (pendingTargets.isNotEmpty()) {
            // A hold-to-mine break in progress: nothing else may launch, its
            // server-side progress would be reset by other break packets.
            if (BreakingFlowController.exclusiveMineActive) break
            if (BreakingFlowController.activeFlows.size >= maxConcurrent) break
            if (approachChecks >= maxApproachChecks) break
            val pos = pendingTargets.removeFirst()

            // The queue may be up to a scan interval old; revalidate cheaply.
            if (cooldowns.containsKey(pos)) continue
            val state = level.getBlockState(pos)
            if (state.isAir) continue
            if (BreakingFlowController.isPositionProtected(pos)) continue

            val isNormalBlock = !usesPistonMethod(level, pos, state)
            if (isNormalBlock && BreakingFlowController.activeFlows.isNotEmpty()) {
                // Normal mining must run alone with a still player — set
                // aside, keep launching the piston candidates behind it.
                waitingNormals.add(pos)
                continue
            }
            approachChecks++

            // Only start a piston flow that can actually be carried out right
            // now; normal blocks need no piston approach at all.
            if (!isNormalBlock && ApproachBase.findBest(level, pos) == null) {
                cooldowns[pos] = tick + failCooldown
                continue
            }

            val flow = BreakingFlowController.enqueueAutomatic(pos)
            if (flow == null) {
                cooldowns[pos] = tick + failCooldown
                continue
            }
            launchedFlows.add(flow)
            lastLaunchTick = tick
            if (isNormalBlock || launchDelay > 0) break
        }
        // Put the waiting normal blocks back at the front, in order.
        for (i in waitingNormals.indices.reversed()) {
            pendingTargets.addFirst(waitingNormals[i])
        }
    }

    /** Put targets of finished flows on cooldown if they are still there (breaking failed). */
    private fun retireFinishedFlows(level: Level) {
        val iterator = launchedFlows.iterator()
        while (iterator.hasNext()) {
            val flow = iterator.next()
            if (BreakingFlowController.activeFlows.contains(flow)) continue
            iterator.remove()
            if (level.getBlockState(flow.targetPos) == flow.targetBlockState) {
                cooldowns[flow.targetPos] = tick + Configs.AutoMine.FAIL_COOLDOWN.integerValue
            }
        }
    }

    /**
     * Target blocks inside the box that the player can reach right now,
     * nearest to the eyes first.
     */
    private fun findCandidates(level: Level, player: LocalPlayer, area: AreaRestriction.Area): List<BlockPos> {
        val targets = targetBlocks()
        val clearAll = Configs.AutoMine.CLEAR_ALL_BLOCKS.booleanValue
        if (targets.isEmpty() && !clearAll) return emptyList()

        val eye = MinecraftClientCompat.eyePosition(player)
        val cx = Mth.floor(eye.x)
        val cy = Mth.floor(eye.y)
        val cz = Mth.floor(eye.z)

        val maxRange = Configs.AutoMine.maxRange
        // No point scanning beyond the configured reach.
        val radius = minOf(SCAN_RADIUS, Mth.ceil(maxRange) + 1)

        val minX = maxOf(area.minX - CLEANUP_MARGIN, cx - radius)
        val minY = maxOf(area.minY - CLEANUP_MARGIN, cy - radius)
        val minZ = maxOf(area.minZ - CLEANUP_MARGIN, cz - radius)
        val maxX = minOf(area.maxX + CLEANUP_MARGIN, cx + radius)
        val maxY = minOf(area.maxY + CLEANUP_MARGIN, cy + radius)
        val maxZ = minOf(area.maxZ + CLEANUP_MARGIN, cz + radius)
        if (minX > maxX || minY > maxY || minZ > maxZ) return emptyList()

        val skipTouching = Configs.AutoMine.SKIP_BLOCKS_TOUCHING_PLAYER.booleanValue
        val playerBox = player.boundingBox.inflate(0.0, 0.1, 0.0)
        val maxRangeSqr = maxRange * maxRange

        val result = ArrayList<BlockPos>()
        val cursor = BlockPos.MutableBlockPos()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    cursor.set(x, y, z)
                    val state = level.getBlockState(cursor)
                    if (area.contains(cursor)) {
                        if (!isMineTarget(targets, level, cursor, state)) continue
                    } else {
                        // Outside the box only stranded contraption pieces count.
                        if (!isLeftoverContraption(state)) continue
                    }
                    if (cooldowns.containsKey(cursor)) continue
                    if (eye.distanceToSqr(MinecraftClientCompat.blockCenter(cursor)) > maxRangeSqr) continue
                    if (!MinecraftClientCompat.canInteractWithBlock(cursor)) continue
                    if (BreakingFlowController.isPositionProtected(cursor)) continue
                    if (skipTouching && AABB(cursor).intersects(playerBox)) continue
                    // A piston has to go next to a piston-method block; normally
                    // mined targets need no free side.
                    if (usesPistonMethod(level, cursor, state) && !hasReplaceableNeighbor(level, cursor)) continue
                    result.add(cursor.immutable())
                }
            }
        }

        result.sortBy { eye.distanceToSqr(MinecraftClientCompat.blockCenter(it)) }
        return result
    }

    // -- queries for AutoPilot --

    fun hasActiveFlows(): Boolean = BreakingFlowController.activeFlows.isNotEmpty()

    /** Targets reachable from where the player stands right now (approaches not yet checked). */
    fun hasReachableWork(level: Level, player: LocalPlayer): Boolean =
        reachableWork(level, player).isNotEmpty()

    fun reachableWork(level: Level, player: LocalPlayer): List<BlockPos> {
        val area = area() ?: return emptyList()
        return findCandidates(level, player, area)
    }

    /** Retry blocks that failed from the previous position (called after relocating). */
    fun clearCooldowns() {
        cooldowns.clear()
    }

    internal fun targetBlockSet(): Set<Block> = targetBlocks()

    /** Only unbreakable blocks (bedrock, ...) get the piston-retraction treatment. */
    fun usesPistonMethod(level: Level, pos: BlockPos, state: net.minecraft.world.level.block.state.BlockState): Boolean {
        return state.getDestroySpeed(level, pos) < 0f
    }

    /**
     * Whether [state] should be mined by the automation: the configured
     * target blocks always, and with "clear all" every other breakable,
     * non-liquid block inside the box (pickaxe-mined the normal way).
     */
    fun isMineTarget(
        targets: Set<Block>,
        level: Level,
        pos: BlockPos,
        state: net.minecraft.world.level.block.state.BlockState,
    ): Boolean {
        if (state.isAir) return false
        if (targets.contains(state.block)) return true
        if (!Configs.AutoMine.CLEAR_ALL_BLOCKS.booleanValue) return false
        if (state.block is net.minecraft.world.level.block.LiquidBlock) return false
        // Vines (twisting/weeping/cave/wall vines) don't obstruct anything —
        // leave them alone instead of wasting time on them.
        if (state.block is net.minecraft.world.level.block.VineBlock) return false
        if (state.block is net.minecraft.world.level.block.GrowingPlantBlock) return false
        if (state.block is net.minecraft.world.level.block.CaveVinesBlock) return false
        if (state.block is net.minecraft.world.level.block.CaveVinesPlantBlock) return false
        return state.getDestroySpeed(level, pos) >= 0f
    }

    /**
     * A block WE placed that got stranded when a flow failed out of range —
     * pistons, redstone torches, support blocks. Mined like any target once
     * back in reach, even just outside the box (contraptions overhang it).
     */
    fun isLeftoverContraption(state: net.minecraft.world.level.block.state.BlockState): Boolean {
        if (!Configs.AutoMine.CLEAR_ALL_BLOCKS.booleanValue) return false
        val block = state.block
        return block === net.minecraft.world.level.block.Blocks.PISTON ||
            block === net.minecraft.world.level.block.Blocks.PISTON_HEAD ||
            block === net.minecraft.world.level.block.Blocks.REDSTONE_TORCH ||
            block === net.minecraft.world.level.block.Blocks.REDSTONE_WALL_TORCH ||
            block === Configs.Generic.supportBlock
    }

    /** How far outside the box leftover contraptions are still hunted. */
    const val CLEANUP_MARGIN = 2

    /** The next few queued targets, for the action overlay. */
    fun pendingPreview(limit: Int): List<BlockPos> {
        if (pendingTargets.isEmpty()) return emptyList()
        return pendingTargets.take(limit)
    }

    /** How many in-range targets are queued right now, for the HUD. */
    fun pendingCount(): Int = pendingTargets.size

    private fun hasReplaceableNeighbor(level: Level, pos: BlockPos): Boolean {
        for (face in Direction.entries) {
            if (MinecraftClientCompat.canBeReplaced(level, pos.relative(face))) return true
        }
        return false
    }

    private fun targetBlocks(): Set<Block> {
        val result = HashSet<Block>()
        for (name in Configs.AutoMine.TARGET_BLOCKS.strings) {
            if (name.isEmpty()) continue
            BlocksCompat.blockFromName(name)?.let { result.add(it) }
        }
        return result
    }
}
