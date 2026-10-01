package com.github.lxyan2333.bedrockminer.client.automate

import com.github.lxyan2333.bedrockminer.client.area.AreaRestriction
import com.github.lxyan2333.bedrockminer.client.automine.AutoMiner
import com.github.lxyan2333.bedrockminer.client.breaking.BreakingFlowController
import com.github.lxyan2333.bedrockminer.client.breaking.InventoryManager
import com.github.lxyan2333.bedrockminer.client.breaking.approach.ApproachBase
import com.github.lxyan2333.bedrockminer.client.compat.MinecraftClientCompat
import com.github.lxyan2333.bedrockminer.client.config.Configs
import com.github.lxyan2333.bedrockminer.client.message.Messager
import fi.dy.masa.malilib.util.StringUtils
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * Fully automated mining: `/bedrockbreaker automate on`.
 *
 * A strict step-by-step state machine so the individual jobs never interfere:
 *
 * - **MINING**: stand still and let [AutoMiner] break everything reachable
 *   from here. The phase only ends when no piston setup is still running, so
 *   an in-flight break is never abandoned by walking out of range.
 * - **COLLECTING**: launching new setups is suppressed; walk to dropped
 *   pistons / torches / support blocks. Collection is *prioritized*, not
 *   constant: after each mining spot only nearby items are picked up, and a
 *   full sweep happens only when supplies run low, an item is close to
 *   despawning, or the box is finished. Everything else is left lying — it
 *   lives for 5 minutes and is usually picked up in passing anyway.
 * - **CLEARING**: an item that cannot be walked to (bedrock here is bumpy) is
 *   not fought forever — the target blocks between the player and the item
 *   are mined with the normal piston flows to open a path, then the walk is
 *   retried. After a couple of rounds without success the item is skipped for
 *   good.
 * - **RELOCATING**: walk toward the nearest remaining target until it is
 *   inside mining range. Wanted items lying close to the route are grabbed in
 *   passing via a small detour — collection without extra trips.
 * - A short PAUSING beat separates phase changes.
 */
object AutoPilot {
    /** Zero: phase changes are instant — no beat between steps. */
    private const val PHASE_PAUSE_TICKS = 0

    // -- collection tuning --
    /** Inside a collection run, items this close count as worth grabbing too. */
    private const val CLOSE_ITEM_RADIUS = 8.0
    /** First-seen age after which an item counts as despawn-endangered (the 4-minute mark of the 5-minute clock). */
    private const val DESPAWN_RISK_TICKS = 4800L
    /** Give up walking to a single item after this many ticks. */
    private const val ITEM_WALK_TIMEOUT_TICKS = 60
    /** Walk almost onto the item — borderline distances miss the pickup box. */
    private const val ITEM_PICKUP_RADIUS = 0.6
    /** Inside this distance the item is as good as picked up — retarget instantly. */
    private const val ITEM_PASS_DISTANCE_SQR = 0.9 * 0.9
    /** A passed-but-unpicked item (a lip in between) comes back after this. */
    private const val PASSED_ITEM_RETRY_TICKS = 100L
    /** In pickup range but not picked up after this: geometry-blocked, move on. */
    private const val ARRIVED_GIVE_UP_TICKS = 5
    /** Terrain-blocked items retry after this (the area flattens over time). */
    private const val DEFER_RETRY_TICKS = 1200L
    private const val DEFER_HARD_RETRY_TICKS = 2400L
    private const val ITEM_SEARCH_MARGIN = 8.0
    /** How often (ticks) the item tracker refreshes first-seen ages. */
    private const val ITEM_SWEEP_INTERVAL = 20
    /** Keep this many pistons/torches spare; below it collection becomes urgent. */
    private const val SUPPLY_HEADROOM = 2

    // -- path clearing --
    private const val MAX_CLEAR_ROUNDS = 2
    private const val CLEAR_PHASE_TIMEOUT_TICKS = 300
    private const val MAX_CLEAR_BLOCKS = 4
    /** Path blocks may lie slightly outside the box (items roll off the edge). */
    private const val CLEAR_BOX_MARGIN = 2

    // -- relocation --
    private const val MINING_IDLE_TIMEOUT_TICKS = 150
    private const val UNREACHABLE_RETRY_TICKS = 2400L
    /** Route-check failures retry sooner: mining keeps reshaping the ground. */
    private const val UNREACHABLE_SOFT_RETRY_TICKS = 600L
    /** Tiny: arrival must never be stricter than what the miner can reach. */
    private const val ARRIVE_RANGE_MARGIN = 0.05
    private const val RELOCATE_STALL_TICKS = 8
    /**
     * Preferred eye distance to the target block when walking up to it.
     * Standing here keeps the whole piston contraption comfortably inside
     * reach with margin to spare, so failed pistons retry reliably instead
     * of flickering at the rim of the reach sphere.
     */
    private const val COMFORT_EYE_DISTANCE = 2.4

    /** How often (ticks) the remaining-blocks count for the HUD refreshes. */
    private const val REMAINING_COUNT_INTERVAL = 100

    /** The expensive in-range scan is cached this many ticks. */
    private const val REACHABLE_CACHE_TICKS = 3

    /** No progress at all for this long: self-heal instead of sitting there. */
    private const val STALL_RESET_TICKS = 600

    enum class Phase { PAUSING, MINING, COLLECTING, CLEARING, RELOCATING, EATING, ESCAPING }

    // -- auto eat --
    /** Eat when hunger drops 3 bars BELOW FULL (20 - 6 = 14 points): the
     *  goal is to always stay near full, never to flirt with starvation. */
    private const val EAT_TRIGGER_FOOD = 14
    /** Eat back to completely full. */
    private const val EAT_UNTIL_FOOD = 20
    private const val EAT_PHASE_TIMEOUT_TICKS = 400

    // -- pit escape --
    private const val ESCAPE_TIMEOUT_TICKS = 300
    private const val ESCAPE_MAX_PILLARS = 5
    /** How often (ticks) the trapped-in-a-pit check runs. */
    private const val TRAP_CHECK_INTERVAL = 10

    var active = false
        private set
    var phase = Phase.PAUSING
        private set

    /** The phase to show in the HUD (a brief PAUSING shows what comes next). */
    val hudPhase: Phase
        get() = if (phase == Phase.PAUSING) nextPhase else phase

    private var nextPhase = Phase.MINING
    private var pauseTicks = 0
    private var tick = 0L
    private var miningIdleTicks = 0

    // collecting
    private var collectAll = false
    private var currentItemId = -1
    private var itemTicks = 0
    private var arrivedTicks = 0
    private var didFinalSweep = false

    // clearing
    private val clearTargets = ArrayDeque<BlockPos>()
    private var clearItemId = -1
    private var clearRounds = 0
    private var clearTicks = 0

    // relocating
    private var relocateTarget: BlockPos? = null
    private var relocateStallTicks = 0
    private var lastArrivedTarget: BlockPos? = null

    /** Stand-aside spot used when the next target is under the player's feet. */
    private var sidestepTarget: BlockPos? = null

    /** How deep the mover may drop while walking to the sidestep spot. */
    private var sidestepDrop = 3

    // -- overlay accessors (rendering) --

    val currentRelocateBlock: BlockPos?
        get() = if (active) relocateTarget else null

    val currentClearBlocks: List<BlockPos>
        get() = if (active && phase == Phase.CLEARING) clearTargets.toList() else emptyList()

    val currentItemTargetId: Int
        get() = if (active && phase == Phase.COLLECTING) currentItemId else -1

    val currentSidestepBlock: BlockPos?
        get() = if (active) sidestepTarget else null

    /** Blocks we could not reach or break; value = tick after which to retry. */
    private val unreachable = HashMap<BlockPos, Long>()

    /**
     * Items put aside for now; value = tick after which to retry. Retries are
     * cheap because terrain-blocked items are filtered out *before* walking,
     * so a deferred item is only chased again once its spot became walkable.
     */
    private val deferredItems = HashMap<Int, Long>()

    /** First tick each wanted item was seen, to judge despawn risk. */
    private val firstSeen = HashMap<Int, Long>()

    /** Whether each tracked item was near the player at the last sweep. */
    private val lastNearPlayer = HashMap<Int, Boolean>()

    // -- HUD statistics --
    var hudInitialTargets = 0
        private set
    var hudRemainingTargets = 0
        private set
    var hudItemsCollected = 0
        private set
    var hudNearbyItems = 0
        private set
    var hudRiskItems = 0
        private set

    /** Whether a tracked item is approaching its despawn (for the overlay). */
    fun isItemAtRisk(id: Int): Boolean {
        if (!active) return false
        val seen = firstSeen[id] ?: return false
        return tick - seen >= DESPAWN_RISK_TICKS
    }

    /** Whether the automation cares about this item type (for the overlay). */
    fun isWantedItem(item: Item): Boolean = active && wantedItems().contains(item)

    /** Whether an item is currently set aside (deferred), for the overlay. */
    fun isItemDeferred(id: Int): Boolean = active && deferredItems.containsKey(id)

    /** Whether the item's spot is trivially walkable right now, for the overlay. */
    fun isItemAccessible(level: Level, player: LocalPlayer, entity: ItemEntity): Boolean =
        easyToReach(level, player, entity)
    /** Small rolling activity log for the HUD. */
    private val recentEvents = ArrayDeque<Pair<String, Long>>()

    /** The last few notable events, newest first, stale ones dropped. */
    val hudRecentEvents: List<String>
        get() = if (!active) emptyList() else recentEvents.filter { tick - it.second <= 400 }.take(3).map { it.first }

    /** Ticks since automation started, for the HUD's ETA estimate. */
    val hudElapsedTicks: Long
        get() = tick

    private fun event(key: String) {
        recentEvents.addFirst(StringUtils.translate(key) to tick)
        while (recentEvents.size > 4) recentEvents.removeLast()
    }

    /** Event pushed from outside (e.g. the inventory code on a tool swap). */
    fun externalEvent(key: String) {
        if (active) event(key)
    }

    private var reachableCache = false
    private var reachableCacheTick = Long.MIN_VALUE / 2
    private var lastProgressTick = 0L

    private fun hasReachableWorkCached(level: Level, player: LocalPlayer): Boolean {
        if (tick - reachableCacheTick >= REACHABLE_CACHE_TICKS) {
            reachableCache = AutoMiner.hasReachableWork(level, player)
            reachableCacheTick = tick
        }
        return reachableCache
    }

    private fun markProgress() {
        lastProgressTick = tick
    }

    // -- command entry points --

    fun start(source: FabricClientCommandSource) {
        if (active) {
            source.sendError(message("bedrockminer.message.autopilot.already_active"))
            return
        }
        if (AutoMiner.area() == null) {
            source.sendError(message("bedrockminer.message.autopilot.no_area"))
            return
        }
        if (!Configs.Generic.BEDROCK_MINER_ENABLED.booleanValue) {
            Configs.Generic.BEDROCK_MINER_ENABLED.booleanValue = true
        }
        if (!Configs.AutoMine.ENABLED.booleanValue) {
            Configs.AutoMine.ENABLED.booleanValue = true
        }
        resetState()
        active = true
        val client = Minecraft.getInstance()
        val level = client.level
        val area = AutoMiner.area()
        if (level != null && area != null) {
            hudInitialTargets = countRemainingTargets(level, area)
            hudRemainingTargets = hudInitialTargets
        }
        enterPhase(Phase.MINING)
        source.sendFeedback(message("bedrockminer.message.autopilot.started"))
    }

    fun stopByCommand(source: FabricClientCommandSource) {
        if (!active) {
            source.sendError(message("bedrockminer.message.autopilot.not_active"))
            return
        }
        deactivate()
        source.sendFeedback(message("bedrockminer.message.autopilot.stopped"))
    }

    fun sendStatus(source: FabricClientCommandSource) {
        val key = if (active) "bedrockminer.message.autopilot.status_on" else "bedrockminer.message.autopilot.status_off"
        source.sendFeedback(message(key, phase.name))
    }

    /** Silent stop for disconnects and level changes. */
    fun abandon() {
        if (!active) return
        active = false
        forceUseKey(false)
        PlayerMover.clear()
        AutoMiner.launchesSuppressed = false
    }

    // -- lifecycle --

    private fun deactivate(chatKey: String? = null, vararg args: Any) {
        if (!active) return
        active = false
        forceUseKey(false)
        PlayerMover.clear()
        AutoMiner.launchesSuppressed = false
        if (Configs.AutoMine.ENABLED.booleanValue) {
            Configs.AutoMine.ENABLED.booleanValue = false
        }
        if (chatKey != null) {
            Messager.chat(StringUtils.translate(chatKey, *args))
        }
    }

    private fun resetState() {
        phase = Phase.PAUSING
        nextPhase = Phase.MINING
        pauseTicks = PHASE_PAUSE_TICKS
        tick = 0L
        miningIdleTicks = 0
        collectAll = false
        currentItemId = -1
        itemTicks = 0
        arrivedTicks = 0
        didFinalSweep = false
        hadActiveFlows = false
        clearTargets.clear()
        clearItemId = -1
        clearRounds = 0
        clearTicks = 0
        relocateTarget = null
        relocateStallTicks = 0
        lastArrivedTarget = null
        sidestepTarget = null
        unreachable.clear()
        deferredItems.clear()
        firstSeen.clear()
        lastNearPlayer.clear()
        hudInitialTargets = 0
        hudRemainingTargets = 0
        hudItemsCollected = 0
        recentEvents.clear()
        lastProgressTick = 0L
        reachableCacheTick = Long.MIN_VALUE / 2
    }

    private fun enterPhase(target: Phase, pause: Int = PHASE_PAUSE_TICKS) {
        forceUseKey(false)
        PlayerMover.clear()
        // Force a fresh in-range check on the next mining tick.
        reachableCacheTick = Long.MIN_VALUE / 2
        markProgress()
        retriedExhaust = false
        if (pause <= 0) {
            phase = target
            return
        }
        phase = Phase.PAUSING
        nextPhase = target
        pauseTicks = pause
    }

    // -- per tick --

    private fun updateSuppression() {
        // Launches only while deliberately mining — and mining means the
        // player stands perfectly still, so contraptions never leave reach.
        AutoMiner.launchesSuppressed = active && phase != Phase.MINING
    }

    fun onTick() {
        updateSuppression()
        if (!active) return
        tick++

        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (level == null || player == null) {
            abandon()
            return
        }
        if (player.isDeadOrDying) {
            deactivate("bedrockminer.message.autopilot.aborted")
            return
        }
        if (!BreakingFlowController.enabled || !Configs.AutoMine.ENABLED.booleanValue) {
            deactivate("bedrockminer.message.autopilot.aborted")
            return
        }
        val area = AutoMiner.area()
        if (area == null) {
            deactivate("bedrockminer.message.autopilot.no_area")
            return
        }
        // Note: no menu/screen gate — automation keeps running while the
        // inventory, chat or any other screen is open, or the window is
        // unfocused. It should never need the player.

        unreachable.entries.removeIf { it.value <= tick }
        deferredItems.entries.removeIf { it.value <= tick }
        if (tick % ITEM_SWEEP_INTERVAL == 0L) {
            trackItems(level, player, area)
        }
        if (tick % REMAINING_COUNT_INTERVAL == 0L) {
            hudRemainingTargets = countRemainingTargets(level, area)
        }

        // Last-resort watchdog: whatever the state, never sit still forever.
        // Forget every skip list, rescan, and start over from mining.
        if (tick - lastProgressTick > STALL_RESET_TICKS) {
            unreachable.clear()
            deferredItems.clear()
            AutoMiner.clearCooldowns()
            AutoMiner.requestScan()
            lastArrivedTarget = null
            relocateTarget = null
            sidestepTarget = null
            currentItemId = -1
            event("bedrockminer.hud.event.watchdog_reset")
            enterPhase(Phase.MINING)
            return
        }

        // Physically trapped in a pit (fell in, or the floor got mined)?
        // Pillar out with a placeable block: jump, place beneath, repeat.
        if (phase != Phase.ESCAPING && tick % TRAP_CHECK_INTERVAL == 0L &&
            !AutoMiner.hasActiveFlows() &&
            MinecraftClientCompat.isOnGround(player) &&
            PathFinder.isTrapCell(level, player.blockPosition())
        ) {
            if (pillarItem(player) != null) {
                event("bedrockminer.hud.event.escape")
                escapeStage = 0
                escapePillars = 0
                escapeTicks = 0
                enterPhase(Phase.ESCAPING)
            } else {
                deactivate("bedrockminer.message.autopilot.stuck")
                return
            }
        }

        // Hungry? Pause everything (once running setups drained) and eat.
        if (Configs.Generic.AUTO_EAT.booleanValue && phase != Phase.EATING &&
            player.foodData.foodLevel <= EAT_TRIGGER_FOOD &&
            !AutoMiner.hasActiveFlows() &&
            findFoodSlot(player) != -1
        ) {
            event("bedrockminer.hud.event.eating")
            eatTicks = 0
            enterPhase(Phase.EATING)
        }

        when (phase) {
            Phase.PAUSING -> {
                if (--pauseTicks <= 0) phase = nextPhase
            }

            Phase.MINING -> tickMining(level, player, area)

            Phase.COLLECTING -> tickCollecting(level, player, area)

            Phase.CLEARING -> tickClearing(level, player, area)

            Phase.RELOCATING -> tickRelocating(level, player, area)

            Phase.EATING -> tickEating(player)

            Phase.ESCAPING -> tickEscaping(level, player)
        }

        // Phases may have just changed: recompute so AutoMiner (which ticks
        // right after this) can already scan and launch in the same tick.
        updateSuppression()
    }

    // -- phases --

    private var hadActiveFlows = false

    /** One free cooldown-clear retry per stint before relocating. */
    private var retriedExhaust = false

    private fun tickMining(level: Level, player: LocalPlayer, area: AreaRestriction.Area) {
        if (AutoMiner.hasActiveFlows()) {
            hadActiveFlows = true
            miningIdleTicks = 0
            markProgress()
            // Stand perfectly still: every active setup must stay in reach.
            PlayerMover.clear()
            // The wait is not wasted — pick (and route-check) the next
            // destination NOW, so the walk starts the tick mining ends.
            warmNextDestination(level, player, area)
            return
        }
        if (hadActiveFlows) {
            // A wave of setups just finished: their holes expose new targets —
            // rescan immediately instead of waiting out the scan interval.
            hadActiveFlows = false
            AutoMiner.requestScan()
            reachableCacheTick = Long.MIN_VALUE / 2
        }

        if (hasReachableWorkCached(level, player)) {
            val missing = InventoryManager.checkRequiredItems()
            if (missing != null) {
                miningIdleTicks = 0
                // Every usable pickaxe is nearly broken or gone: STOP.
                // Never, ever continue on broken tools.
                if (InventoryManager.toolsWorn() || !InventoryManager.hasUsablePickaxe()) {
                    deactivate("bedrockminer.message.autopilot.tools_worn")
                    return
                }
                // Only ACTUALLY missing pistons/torches/support may stop the
                // automation. Anything else (e.g. Efficiency/Haste check while
                // outside the beacon) is transient: hold, show why, retry.
                if (!InventoryManager.suppliesMissing()) {
                    Messager.actionBar(missing)
                    markProgress()
                    return
                }
                // Out of pistons/torches: the drops lying around may fix that;
                // if there are none, give up with the reason.
                if (findNearestItem(level, player, area, all = true) != null) {
                    startCollecting(all = true)
                } else {
                    deactivate("bedrockminer.message.autopilot.out_of_items")
                }
                return
            }
            miningIdleTicks++
            if (miningIdleTicks < MINING_IDLE_TIMEOUT_TICKS) return
            // Candidates are in range but nothing ever completes: set them
            // aside so relocation does not keep bringing us back here.
            for (pos in AutoMiner.reachableWork(level, player)) {
                unreachable[pos] = tick + UNREACHABLE_RETRY_TICKS
            }
        }
        miningIdleTicks = 0

        // Before ever deciding to leave: blocks that failed once sit on a
        // retry cooldown and are invisible to the reach check — clear them
        // and rescan ONCE. Never walk away from bedrock that is right here.
        if (!retriedExhaust) {
            retriedExhaust = true
            AutoMiner.clearCooldowns()
            AutoMiner.requestScan()
            reachableCacheTick = Long.MIN_VALUE / 2
            return
        }

        // Done mining here — MINING ALWAYS WINS. As long as target blocks
        // remain, go straight to the next spot (items on the way are grabbed
        // in passing). Dedicated collection only for real emergencies:
        //  - supplies low                 -> collect everything reachable
        //  - an item nears the 4-min mark -> rescue run
        //  - the box is finished          -> final sweep (in planNext)
        when {
            suppliesLow(player) && findNearestItem(level, player, area, all = true) != null ->
                startCollecting(all = true)

            hasDespawnRiskItem(level, player, area) ->
                startCollecting(all = false)

            else -> planNext(level, player, area)
        }
    }

    private fun startCollecting(all: Boolean) {
        collectAll = all
        currentItemId = -1
        itemTicks = 0
        arrivedTicks = 0
        enterPhase(Phase.COLLECTING)
    }

    private fun tickCollecting(level: Level, player: LocalPlayer, area: AreaRestriction.Area) {
        var item = level.getEntity(currentItemId) as? ItemEntity
        if (item == null || !item.isAlive) {
            item = selectCollectItem(level, player, area)
            if (item?.id != currentItemId) clearRounds = 0
            itemTicks = 0
            arrivedTicks = 0
        }

        // Drive-through: as soon as an item is inside pickup range, vanilla
        // pickup has it — retarget the next one in the same tick and keep
        // walking, never stop and wait. (If a lip blocked the pickup, the
        // short defer brings the item back later.)
        var chained = 0
        while (item != null && withinPickup(player, item) && chained++ < 8) {
            deferItem(item.id, PASSED_ITEM_RETRY_TICKS)
            item = selectCollectItem(level, player, area)
            itemTicks = 0
            arrivedTicks = 0
            clearRounds = 0
        }
        currentItemId = item?.id ?: -1
        if (item == null) {
            // Piston wave still running (launched while we collected): hold
            // inside its leash until it drains, then reach the farther items.
            if (AutoMiner.hasActiveFlows()) {
                PlayerMover.clear()
                markProgress()
                // Use the hold to pre-pick the next destination.
                warmNextDestination(level, player, area)
                return
            }
            planNext(level, player, area)
            return
        }

        itemTicks++
        if (itemTicks > ITEM_WALK_TIMEOUT_TICKS) {
            handleUnreachableItem(level, player, area, item)
            return
        }

        // Walk NEAR the item, not onto it: vanilla pickup grabs it in passing.
        PlayerMover.setTarget(item.position(), ITEM_PICKUP_RADIUS, 3, 2.0)
        when (PlayerMover.tick(level, player)) {
            PlayerMover.Result.MOVING -> markProgress()
            PlayerMover.Result.STUCK -> handleUnreachableItem(level, player, area, item)
            PlayerMover.Result.ARRIVED -> {
                // As close as the mover gets yet not inside the pickup box:
                // geometry is in the way. Move on immediately, no standing.
                deferItem(item.id, DEFER_RETRY_TICKS)
            }
            else -> arrivedTicks = 0
        }
    }

    /**
     * The item cannot be walked to right now. In a supplies-low or final-sweep
     * run it is worth mining the blocking bedrock to get there; otherwise the
     * item is put aside and retried once the area around it has been
     * flattened (the walkability filter decides that, cheaply, later).
     */
    private fun handleUnreachableItem(level: Level, player: LocalPlayer, area: AreaRestriction.Area, item: ItemEntity) {
        if (!collectAll) {
            deferItem(item.id, DEFER_RETRY_TICKS)
            return
        }
        if (clearRounds >= MAX_CLEAR_ROUNDS) {
            deferItem(item.id, DEFER_HARD_RETRY_TICKS)
            return
        }
        val blocking = blockingPathBlocks(level, player, area, item.position())
        if (blocking.isEmpty()) {
            deferItem(item.id, DEFER_HARD_RETRY_TICKS)
            return
        }
        clearRounds++
        clearTargets.clear()
        clearTargets.addAll(blocking)
        clearItemId = item.id
        clearTicks = 0
        event("bedrockminer.hud.event.clearing_path")
        enterPhase(Phase.CLEARING)
    }

    private fun deferItem(id: Int, retryTicks: Long) {
        deferredItems[id] = tick + retryTicks
        currentItemId = -1
        clearRounds = 0
        arrivedTicks = 0
        markProgress()
    }

    private fun tickClearing(level: Level, player: LocalPlayer, area: AreaRestriction.Area) {
        clearTicks++
        if (clearTicks > CLEAR_PHASE_TIMEOUT_TICKS) {
            deferItem(clearItemId, DEFER_HARD_RETRY_TICKS)
            clearTargets.clear()
            enterPhase(Phase.COLLECTING)
            return
        }

        // Path flows use the same engine as mining, with the same overlap
        // protection — and never run at the same time as a walking phase.
        if (AutoMiner.hasActiveFlows()) return

        val maxRangeSqr = Configs.AutoMine.maxRange * Configs.AutoMine.maxRange
        val eye = MinecraftClientCompat.eyePosition(player)
        while (clearTargets.isNotEmpty()) {
            val pos = clearTargets.removeFirst()
            if (!isTargetBlock(level, pos)) continue
            if (eye.distanceToSqr(MinecraftClientCompat.blockCenter(pos)) > maxRangeSqr) continue
            // Normally mined path blocks need no piston approach.
            val normalBlock = !AutoMiner.usesPistonMethod(level, pos, level.getBlockState(pos))
            if (!normalBlock && ApproachBase.findBest(level, pos) == null) continue
            if (BreakingFlowController.enqueueAutomatic(pos) != null) return
        }

        // Nothing left to launch and no flow running: retry the walk.
        itemTicks = 0
        enterPhase(Phase.COLLECTING)
    }

    /** Destination picked and route-verified ahead of time, during waits. */
    private var warmTarget: BlockPos? = null

    /**
     * Think WHILE waiting, not after: during piston-drain holds this picks
     * the nearest remaining target and verifies a route to it exists, one
     * candidate per tick. By the time the player may move again the
     * decision is already made — zero thinking pause between tasks.
     */
    private fun warmNextDestination(level: Level, player: LocalPlayer, area: AreaRestriction.Area) {
        val cached = warmTarget
        if (cached != null && isTargetBlock(level, cached) && !unreachable.containsKey(cached)) return
        warmTarget = null
        val candidate = nearestRemainingTarget(level, player, area) ?: return
        if (candidate == lastArrivedTarget) return
        if (PathFinder.canReach(
                level, player.blockPosition(),
                MinecraftClientCompat.blockCenter(candidate), 2.0, 3,
            )
        ) {
            warmTarget = candidate
        } else {
            unreachable[candidate] = tick + UNREACHABLE_SOFT_RETRY_TICKS
        }
    }

    private fun planNext(level: Level, player: LocalPlayer, area: AreaRestriction.Area) {
        // Fast path: work is reachable from right here (typical after a
        // collection run) — resume mining in place, skip the full box scan
        // and the walk entirely.
        if (phase != Phase.MINING && AutoMiner.hasReachableWork(level, player)) {
            AutoMiner.requestScan()
            enterPhase(Phase.MINING)
            return
        }

        var next = warmTarget?.takeIf { isTargetBlock(level, it) && !unreachable.containsKey(it) }
        warmTarget = null
        if (next == null) next = nearestRemainingTarget(level, player, area)
        if (next != null && next == lastArrivedTarget) {
            // We already stood next to this block and mining could not break
            // it; set it aside instead of shuttling back and forth.
            unreachable[next] = tick + UNREACHABLE_RETRY_TICKS
            next = nearestRemainingTarget(level, player, area)
        }
        // Commit ONLY to a destination a route provably exists to: check
        // the nearest few right now (microseconds each) and set aside
        // whatever nothing can walk to. The player never starts a trip
        // that is going to fail.
        var routeChecked = 0
        while (next != null && routeChecked < 6) {
            if (PathFinder.canReach(
                    level, player.blockPosition(),
                    MinecraftClientCompat.blockCenter(next), 2.0, 3,
                )
            ) {
                break
            }
            unreachable[next] = tick + UNREACHABLE_SOFT_RETRY_TICKS
            routeChecked++
            next = nearestRemainingTarget(level, player, area)
        }
        if (next != null) {
            relocateTarget = next
            relocateStallTicks = 0
            // Standing on (or right above) the target blocks mining it: first
            // step aside to a walkable spot a couple of blocks away.
            sidestepTarget = null
            val feet = player.position()
            val horizontalSqr = (next.x + 0.5 - feet.x).let { dx -> dx * dx } +
                (next.z + 0.5 - feet.z).let { dz -> dz * dz }
            if (horizontalSqr < 1.5 * 1.5 && feet.y >= next.y) {
                sidestepTarget = findStandSpotAway(level, player, next)
                if (sidestepTarget != null) {
                    event("bedrockminer.hud.event.step_aside")
                }
                if (sidestepTarget == null) {
                    // Nowhere to stand aside: this block is not minable now.
                    unreachable[next] = tick + UNREACHABLE_RETRY_TICKS
                    relocateTarget = null
                    planNext(level, player, area)
                    return
                }
            }
            enterPhase(Phase.RELOCATING)
            return
        }

        // Box is clean: one final sweep for everything still lying around.
        if (!didFinalSweep && findNearestItem(level, player, area, all = true) != null) {
            didFinalSweep = true
            startCollecting(all = true)
            return
        }

        val skipped = countRemainingTargets(level, area)
        if (skipped > 0) {
            deactivate("bedrockminer.message.autopilot.done_skipped", skipped)
        } else {
            deactivate("bedrockminer.message.autopilot.done")
        }
    }

    private fun tickRelocating(level: Level, player: LocalPlayer, area: AreaRestriction.Area) {
        val target = relocateTarget
        if (target == null || !isTargetBlock(level, target)) {
            relocateTarget = null
            sidestepTarget = null
            planNext(level, player, area)
            return
        }

        // Step aside first when the target is under our feet.
        val sidestep = sidestepTarget
        if (sidestep != null) {
            PlayerMover.setTarget(Vec3(sidestep.x + 0.5, sidestep.y.toDouble(), sidestep.z + 0.5), 0.9, sidestepDrop)
            when (PlayerMover.tick(level, player)) {
                PlayerMover.Result.ARRIVED -> sidestepTarget = null
                PlayerMover.Result.STUCK -> {
                    sidestepTarget = null
                    unreachable[target] = tick + UNREACHABLE_RETRY_TICKS
                    relocateTarget = null
                }
                else -> {}
            }
            return
        }

        val center = MinecraftClientCompat.blockCenter(target)
        // Walk up CLOSE, not merely into reach: stopping the moment the
        // block crosses the reach sphere leaves every placement at max
        // range, where pistons fail and retry forever.
        val arriveDistance = maxOf(
            1.0,
            minOf(Configs.AutoMine.maxRange - ARRIVE_RANGE_MARGIN, COMFORT_EYE_DISTANCE),
        )
        val eye = MinecraftClientCompat.eyePosition(player)
        if (eye.distanceTo(center) <= arriveDistance) {
            PlayerMover.clear()
            // New position, new angles: retry blocks that failed elsewhere,
            // and scan immediately — no waiting for the next interval.
            AutoMiner.clearCooldowns()
            AutoMiner.requestScan()
            lastArrivedTarget = target
            relocateTarget = null
            enterPhase(Phase.MINING)
            return
        }

        // Press INTO comfortable range rather than stopping on its rim: the
        // eye-distance accept above fires mid-walk the moment we are truly
        // close, so the walk ends deep in range and never stalls at the
        // boundary. A per-target sideways offset varies the approach angle,
        // so a piston angle that failed once is retried from a fresh spot.
        val angleSeed = kotlin.random.Random(target.hashCode() * 31 + (tick / 600).toInt())
        val offsetX = (angleSeed.nextDouble() - 0.5) * 1.6
        val offsetZ = (angleSeed.nextDouble() - 0.5) * 1.6
        PlayerMover.setTarget(
            Vec3(center.x + offsetX, center.y, center.z + offsetZ),
            maxOf(0.9, arriveDistance - 0.8),
        )
        when (PlayerMover.tick(level, player)) {
            PlayerMover.Result.STUCK -> {
                unreachable[target] = tick + UNREACHABLE_RETRY_TICKS
                relocateTarget = null
                event("bedrockminer.hud.event.target_skipped")
            }
            PlayerMover.Result.ARRIVED -> {
                // As close as the mover gets, but the block is still outside
                // strict arrival distance: don't stand around — if anything is
                // minable from here, mine; otherwise set the block aside.
                relocateStallTicks++
                if (relocateStallTicks > RELOCATE_STALL_TICKS) {
                    AutoMiner.clearCooldowns()
                    if (AutoMiner.hasReachableWork(level, player)) {
                        AutoMiner.requestScan()
                        lastArrivedTarget = target
                        relocateTarget = null
                        enterPhase(Phase.MINING)
                    } else {
                        unreachable[target] = tick + UNREACHABLE_RETRY_TICKS
                        relocateTarget = null
                    }
                }
            }
            else -> {
                relocateStallTicks = 0
                markProgress()
            }
        }
    }

    private var eatTicks = 0

    /** Whether we are holding the vanilla use key down programmatically. */
    private var useKeyForced = false

    /**
     * Eating must go through the real input path: a direct useItem() call is
     * cancelled next tick by vanilla, which releases the use item whenever
     * the use key is not physically held. Holding the key makes vanilla run
     * the whole eat exactly like a player holding right click.
     */
    private fun forceUseKey(down: Boolean) {
        if (useKeyForced == down) return
        useKeyForced = down
        Minecraft.getInstance().options.keyUse.setDown(down)
    }

    // escaping
    private var escapeStage = 0
    private var escapeStartY = 0.0
    private var escapeFeetCell: BlockPos? = null
    private var escapePillars = 0
    private var escapeTicks = 0

    /** Stand still and eat until completely fed, then resume mining. */
    private fun tickEating(player: LocalPlayer) {
        PlayerMover.clear()
        markProgress()
        eatTicks++
        val gameMode = Minecraft.getInstance().gameMode

        val done = player.foodData.foodLevel >= EAT_UNTIL_FOOD ||
            eatTicks > EAT_PHASE_TIMEOUT_TICKS ||
            (!player.isUsingItem && findFoodSlot(player) == -1)
        if (done) {
            forceUseKey(false)
            if (player.isUsingItem) gameMode?.releaseUsingItem(player)
            AutoMiner.requestScan()
            enterPhase(Phase.MINING)
            return
        }

        // Tilt the view up while chewing so the use click can never interact
        // with a block in front of the crosshair.
        player.xRot = (player.xRot - 8f).coerceAtLeast(-60f)

        val slot = findFoodSlot(player)
        val selected = MinecraftClientCompat.getSelectedItem(player.inventory)
        if (!isEdible(selected)) {
            // Wrong item in hand: release, switch, verify next tick.
            forceUseKey(false)
            if (slot != -1) InventoryManager.selectItemNow(slot)
            return
        }
        // Food in hand: hold right click, vanilla does the actual eating and
        // chain-eats until we let go at full hunger.
        forceUseKey(true)
    }

    /** The block used to pillar out of a pit: pistons first, then support. */
    private fun pillarItem(player: LocalPlayer): net.minecraft.world.item.Item? {
        if (InventoryManager.countItem(Blocks.PISTON.asItem()) > 0) return Blocks.PISTON.asItem()
        val support = Configs.Generic.supportBlock.asItem()
        if (InventoryManager.countItem(support) > 0) return support
        return null
    }

    /**
     * Jump-and-place escape: while airborne above the old floor cell, place
     * a block into it, land on it, repeat until an exit exists.
     */
    private fun tickEscaping(level: Level, player: LocalPlayer) {
        PlayerMover.clear()
        markProgress()
        escapeTicks++
        if (escapeTicks > ESCAPE_TIMEOUT_TICKS || escapePillars >= ESCAPE_MAX_PILLARS) {
            deactivate("bedrockminer.message.autopilot.stuck")
            return
        }
        if (!PathFinder.isTrapCell(level, player.blockPosition())) {
            // A way out exists again: back to work.
            AutoMiner.clearCooldowns()
            AutoMiner.requestScan()
            enterPhase(Phase.MINING)
            return
        }

        when (escapeStage) {
            0 -> if (MinecraftClientCompat.isOnGround(player)) {
                escapeFeetCell = player.blockPosition()
                escapeStartY = player.position().y
                val motion = player.deltaMovement
                player.setDeltaMovement(motion.x * 0.2, 0.42, motion.z * 0.2)
                escapeStage = 1
            }

            1 -> {
                if (player.position().y > escapeStartY + 0.9) {
                    // High enough: place the block into the old floor cell.
                    val cell = escapeFeetCell
                    val item = pillarItem(player)
                    if (cell != null && item != null) {
                        val scope = BreakingFlowController.scope ?: BreakingFlowController.startConsumer()
                        scope.launch {
                            com.github.lxyan2333.bedrockminer.client.breaking.BlockPlacer.simpleBlockPlacement(cell, item)
                        }
                    }
                    escapeStage = 2
                } else if (escapeTicks % 20 == 19 && MinecraftClientCompat.isOnGround(player)) {
                    escapeStage = 0 // the jump did not take, try again
                }
            }

            2 -> if (MinecraftClientCompat.isOnGround(player) &&
                player.position().y > escapeStartY + 0.5
            ) {
                escapePillars++
                escapeStage = 0
            } else if (escapeTicks % 30 == 29 && MinecraftClientCompat.isOnGround(player)) {
                escapeStage = 0 // placement missed, retry the jump
            }
        }
    }

    /** Food slot to eat from; golden carrots preferred, junk food excluded. */
    private fun findFoodSlot(player: LocalPlayer): Int {
        val inventory = player.inventory
        var fallback = -1
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (!isEdible(stack)) continue
            if (stack.item === net.minecraft.world.item.Items.GOLDEN_CARROT) return i
            if (fallback == -1) fallback = i
        }
        return fallback
    }

    private fun isEdible(stack: net.minecraft.world.item.ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (!stack.has(net.minecraft.core.component.DataComponents.FOOD)) return false
        // Never auto-eat items with nasty side effects.
        val item = stack.item
        return item !== net.minecraft.world.item.Items.CHORUS_FRUIT &&
            item !== net.minecraft.world.item.Items.ROTTEN_FLESH &&
            item !== net.minecraft.world.item.Items.SPIDER_EYE &&
            item !== net.minecraft.world.item.Items.POISONOUS_POTATO &&
            item !== net.minecraft.world.item.Items.PUFFERFISH
    }

    // -- queries --

    private fun isTargetBlock(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return AutoMiner.isMineTarget(AutoMiner.targetBlockSet(), level, pos, state) ||
            AutoMiner.isLeftoverContraption(state)
    }

    /** Target blocks that stand between the player and [itemPos] at walking height. */
    private fun blockingPathBlocks(
        level: Level,
        player: LocalPlayer,
        area: AreaRestriction.Area,
        itemPos: Vec3,
    ): List<BlockPos> {
        val targets = AutoMiner.targetBlockSet()
        val start = player.position()
        val delta = itemPos.subtract(start)
        val length = delta.length()
        if (length < 0.5) return emptyList()
        val steps = ceil(length / 0.5).toInt()
        val result = LinkedHashSet<BlockPos>()
        for (i in 1..steps) {
            val point = start.add(delta.scale(i.toDouble() / steps))
            for (dy in 0..1) {
                val pos = BlockPos.containing(point.x, point.y + dy, point.z)
                val state = level.getBlockState(pos)
                if (!AutoMiner.isMineTarget(targets, level, pos, state)) continue
                if (area.contains(pos)) {
                    // inside the box: anything minable may be cleared
                } else {
                    // Outside the box NOTHING of the world is ours to break:
                    // only unbreakables (the piston method's own targets) and
                    // stranded contraption pieces, within a small margin.
                    // Beacon bases, builds, iron blocks stay untouched.
                    if (state.getDestroySpeed(level, pos) >= 0f &&
                        !AutoMiner.isLeftoverContraption(state)
                    ) {
                        continue
                    }
                    if (pos.x < area.minX - CLEAR_BOX_MARGIN || pos.x > area.maxX + CLEAR_BOX_MARGIN) continue
                    if (pos.y < area.minY - CLEAR_BOX_MARGIN || pos.y > area.maxY + CLEAR_BOX_MARGIN) continue
                    if (pos.z < area.minZ - CLEAR_BOX_MARGIN || pos.z > area.maxZ + CLEAR_BOX_MARGIN) continue
                }
                result.add(pos.immutable())
                if (result.size >= MAX_CLEAR_BLOCKS) return result.toList()
            }
        }
        return result.toList()
    }

    /** One box pass: the nearest not-set-aside target AND the total remaining count. */
    private fun scanRemaining(level: Level, player: LocalPlayer, area: AreaRestriction.Area): Pair<BlockPos?, Int> {
        val targets = AutoMiner.targetBlockSet()
        val origin = player.position()
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        var count = 0
        val margin = AutoMiner.CLEANUP_MARGIN
        for (pos in BlockPos.betweenClosed(
            BlockPos(area.minX - margin, area.minY - margin, area.minZ - margin),
            BlockPos(area.maxX + margin, area.maxY + margin, area.maxZ + margin),
        )) {
            val state = level.getBlockState(pos)
            if (area.contains(pos)) {
                if (!AutoMiner.isMineTarget(targets, level, pos, state)) continue
            } else {
                if (!AutoMiner.isLeftoverContraption(state)) continue
            }
            count++
            if (unreachable.containsKey(pos)) continue
            val distance = origin.distanceToSqr(MinecraftClientCompat.blockCenter(pos))
            if (distance < bestDistance) {
                bestDistance = distance
                best = pos.immutable()
            }
        }
        return best to count
    }

    /** Cached distance-sorted remaining targets: picking the next location
     *  is instant instead of a full box scan on every decision. */
    private val targetCache = ArrayList<BlockPos>()
    private var targetCacheTick = -10_000L

    private fun nearestRemainingTarget(level: Level, player: LocalPlayer, area: AreaRestriction.Area): BlockPos? {
        // Serve from the cache first, dropping entries that got mined or
        // set aside in the meantime.
        if (tick - targetCacheTick <= 100) {
            while (targetCache.isNotEmpty()) {
                val candidate = targetCache.removeAt(0)
                if (unreachable.containsKey(candidate)) continue
                if (!isTargetBlock(level, candidate)) continue
                return candidate
            }
        }
        // Cache stale or exhausted: one full refresh.
        val targets = AutoMiner.targetBlockSet()
        val origin = player.position()
        val margin = AutoMiner.CLEANUP_MARGIN
        val found = ArrayList<Pair<BlockPos, Double>>()
        var count = 0
        for (pos in BlockPos.betweenClosed(
            BlockPos(area.minX - margin, area.minY - margin, area.minZ - margin),
            BlockPos(area.maxX + margin, area.maxY + margin, area.maxZ + margin),
        )) {
            val state = level.getBlockState(pos)
            if (area.contains(pos)) {
                if (!AutoMiner.isMineTarget(targets, level, pos, state)) continue
            } else {
                if (!AutoMiner.isLeftoverContraption(state)) continue
            }
            count++
            if (unreachable.containsKey(pos)) continue
            found.add(pos.immutable() to origin.distanceToSqr(MinecraftClientCompat.blockCenter(pos)))
        }
        hudRemainingTargets = count
        found.sortBy { it.second }
        targetCache.clear()
        for (i in 0 until minOf(found.size, 128)) targetCache.add(found[i].first)
        targetCacheTick = tick
        return if (targetCache.isEmpty()) null else targetCache.removeAt(0)
    }

    private fun countRemainingTargets(level: Level, area: AreaRestriction.Area): Int {
        val player = Minecraft.getInstance().player ?: return 0
        return scanRemaining(level, player, area).second
    }

    /**
     * The next item worth collecting right now (cheap existence check).
     * With [all] every wanted item counts; otherwise only items that are
     * close by or in despawn danger.
     */
    private fun findNearestItem(level: Level, player: LocalPlayer, area: AreaRestriction.Area, all: Boolean): ItemEntity? {
        val closeRadiusSqr = CLOSE_ITEM_RADIUS * CLOSE_ITEM_RADIUS
        return wantedItemEntities(level, area) { entity ->
            all ||
                entity.distanceToSqr(player) <= closeRadiusSqr ||
                tick - (firstSeen[entity.id] ?: tick) >= DESPAWN_RISK_TICKS
        }.minByOrNull { it.distanceToSqr(player) }
    }

    /**
     * Nearest item that is actually walkable-to, verified with a real path
     * search over the closest few candidates — so a nearby item is never
     * skipped in favour of a distant one just because it sits on rough
     * terrain, and an unreachable one never causes a doomed walk.
     */
    /**
     * Vanilla picks an item up when it enters the player's touch box: about
     * one block around, half a block below the feet, two above. Passing an
     * item only counts when it is truly inside that envelope.
     */
    private fun withinPickup(player: LocalPlayer, item: ItemEntity): Boolean {
        val dx = item.x - player.x
        val dz = item.z - player.z
        val dy = item.y - player.y
        return dx * dx + dz * dz <= 1.0 && dy > -0.55 && dy < 2.2
    }

    private fun selectCollectItem(level: Level, player: LocalPlayer, area: AreaRestriction.Area): ItemEntity? {
        val closeRadiusSqr = CLOSE_ITEM_RADIUS * CLOSE_ITEM_RADIUS
        val candidates = wantedItemEntities(level, area) { entity ->
            collectAll ||
                entity.distanceToSqr(player) <= closeRadiusSqr ||
                tick - (firstSeen[entity.id] ?: tick) >= DESPAWN_RISK_TICKS
        }.sortedBy { it.distanceToSqr(player) }

        for (candidate in candidates.take(5)) {
            // Right next to us: no need to plan anything.
            if (candidate.distanceToSqr(player) <= 4.0) return candidate
            if (PathFinder.canReach(
                    level, player.blockPosition(), candidate.position(),
                    ITEM_PICKUP_RADIUS, 3, verticalTolerance = 2.0,
                )
            ) {
                return candidate
            }
            // Do NOT re-run this failed search every selection: shelve the
            // item briefly so the decision is instant next time.
            deferItem(candidate.id, PASSED_ITEM_RETRY_TICKS)
        }
        return null
    }

    /**
     * Cheap walkability check done *before* any walking: the item lies at
     * roughly the player's level and its spot has room to stand and ground
     * below. Items on top of bedrock bumps or down in pockets fail this and
     * are simply not chased until the terrain around them is flattened.
     */
    private fun easyToReach(level: Level, player: LocalPlayer, item: ItemEntity): Boolean {
        val dy = item.y - player.y
        if (dy > 1.2 || dy < -2.5) return false
        val pos = BlockPos.containing(item.x, item.y + 0.2, item.z)
        if (!PlayerMover.isPassable(level, pos) || !PlayerMover.isPassable(level, pos.above())) return false
        for (i in 1..2) {
            if (!PlayerMover.isPassable(level, pos.below(i))) return true
        }
        return false
    }

    private fun wantedItemEntities(
        level: Level,
        area: AreaRestriction.Area,
        extraFilter: (ItemEntity) -> Boolean = { true },
    ): List<ItemEntity> {
        val wanted = wantedItems()
        val searchBox = AABB(
            area.minX.toDouble(), area.minY.toDouble(), area.minZ.toDouble(),
            area.maxX + 1.0, area.maxY + 1.0, area.maxZ + 1.0,
        ).inflate(ITEM_SEARCH_MARGIN)
        return level.getEntitiesOfClass(ItemEntity::class.java, searchBox) { entity ->
            entity.isAlive && !deferredItems.containsKey(entity.id) &&
                wanted.contains(entity.item.item) && extraFilter(entity)
        }
    }

    private fun hasDespawnRiskItem(level: Level, player: LocalPlayer, area: AreaRestriction.Area): Boolean {
        return wantedItemEntities(level, area) { entity ->
            tick - (firstSeen[entity.id] ?: tick) >= DESPAWN_RISK_TICKS
        }.isNotEmpty()
    }

    /** Refresh first-seen ages and count items that vanished next to the player as collected. */
    private fun trackItems(level: Level, player: LocalPlayer, area: AreaRestriction.Area) {
        val seen = HashSet<Int>()
        var risk = 0
        for (entity in wantedItemEntities(level, area)) {
            seen.add(entity.id)
            firstSeen.putIfAbsent(entity.id, tick)
            lastNearPlayer[entity.id] = entity.distanceToSqr(player) < 9.0
            if (tick - (firstSeen[entity.id] ?: tick) >= DESPAWN_RISK_TICKS) risk++
        }
        for (id in firstSeen.keys) {
            if (id !in seen && lastNearPlayer[id] == true) hudItemsCollected++
        }
        firstSeen.keys.retainAll(seen)
        lastNearPlayer.keys.retainAll(seen)
        hudNearbyItems = seen.size
        hudRiskItems = risk
    }

    private fun suppliesLow(player: LocalPlayer): Boolean {
        val needed = Configs.AutoMine.MAX_CONCURRENT.integerValue + SUPPLY_HEADROOM
        return countInInventory(player, Blocks.PISTON.asItem()) < needed ||
            countInInventory(player, Blocks.REDSTONE_TORCH.asItem()) < needed ||
            countInInventory(player, Configs.Generic.supportBlock.asItem()) < SUPPLY_HEADROOM
    }

    private fun countInInventory(player: LocalPlayer, item: Item): Int {
        val inventory = player.inventory
        var total = 0
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (stack.item === item) total += stack.count
        }
        return total
    }

    private fun wantedItems(): Set<Item> = setOf(
        Blocks.PISTON.asItem(),
        Blocks.REDSTONE_TORCH.asItem(),
        Configs.Generic.supportBlock.asItem(),
    )

    /**
     * A walkable spot 2-4 blocks away from the player, not on top of
     * [avoid] — used to step off a block so it can be mined. Spots up to
     * 3 below the feet count; when nothing else exists, a second pass
     * accepts spots requiring a deeper (but survivable) drop.
     */
    private fun findStandSpotAway(level: Level, player: LocalPlayer, avoid: BlockPos): BlockPos? {
        val feet = player.blockPosition()
        // Pass 1: comfortable spots. Pass 2: anything survivable — standing
        // on a lone block forever is worse than dropping a few blocks.
        for (pass in 0..1) {
            for (radius in 2..4) {
                for (dx in -radius..radius) {
                    for (dz in -radius..radius) {
                        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != radius) continue
                        val dyRange = if (pass == 0) intArrayOf(0, -1, 1, -2, -3) else intArrayOf(-4, -5)
                        for (dy in dyRange) {
                            val pos = feet.offset(dx, dy, dz)
                            if (pos == avoid || pos.below() == avoid) continue
                            if (!PlayerMover.isPassable(level, pos)) continue
                            if (!PlayerMover.isPassable(level, pos.above())) continue
                            if (PlayerMover.isPassable(level, pos.below())) continue
                            sidestepDrop = if (pass == 0) 4 else 6
                            return pos.immutable()
                        }
                    }
                }
            }
        }
        return null
    }

    private fun message(key: String, vararg args: Any) =
        MinecraftClientCompat.literal(StringUtils.translate(key, *args))
}
