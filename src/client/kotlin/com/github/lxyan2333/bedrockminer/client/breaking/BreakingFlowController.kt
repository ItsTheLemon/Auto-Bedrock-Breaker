package com.github.lxyan2333.bedrockminer.client.breaking

import com.github.lxyan2333.bedrockminer.client.area.AreaRestriction
import com.github.lxyan2333.bedrockminer.client.automine.AutoMiner
import com.github.lxyan2333.bedrockminer.client.config.AllowOrBlockMode
import com.github.lxyan2333.bedrockminer.client.config.Configs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import com.github.lxyan2333.bedrockminer.client.message.Messager
import com.github.lxyan2333.bedrockminer.compat.IdentifierCompat
import com.github.lxyan2333.bedrockminer.config.ServerConfigData
import net.minecraft.world.level.block.state.BlockState
import fi.dy.masa.malilib.util.StringUtils
import net.minecraft.world.InteractionResult

object BreakingFlowController {
    var enabled = false
        get() = field
        set(value) {
            field = value
        }

    var scope: CoroutineScope? = null
    val activeFlows = mutableSetOf<BreakingFlow>()
    var isInternalBreak = false
        get() = field
        set(value) {
            field = value
        }

    /** True while a normal (hold-to-mine) block break is running — it must run alone. */
    var exclusiveMineActive = false

    fun isPositionProtected(pos: BlockPos): Boolean = activeFlows.any { it.currentApproach?.occupies(pos) == true }

    fun toggle() {
        if (enabled) disable() else enable()
    }

    fun enable() {
        if (scope == null) startConsumer()
        if (enabled) return
        enabled = true
        Messager.actionBar(StringUtils.translate("bedrockminer.message.started"))
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        // Auto mine cannot run without the main toggle; switch it off too so it
        // does not silently resume the next time the mod is enabled.
        AutoMiner.onMainDisabled()
        activeFlows.forEach { it.doCleanUp = false }
        Messager.actionBar(StringUtils.translate("bedrockminer.message.stopped"))
        scope?.cancel()
        scope = null
        activeFlows.clear()
    }

    private fun listContains(list: List<String>, blockState: BlockState): Boolean {
        val blockId = IdentifierCompat.blockId(blockState.block).toString()
        if (list.contains(blockId)) {
            return true
        }
        return list.any { it.equals(blockState.block.name.string, ignoreCase = true) }
    }

    /**
     * Whether this mod may break [blockState], considering server config, the
     * special-block list and the client allow/block lists.
     *
     * @param notify show the reason on the action bar when the block is refused.
     * Pass false for automated checks that run every few ticks.
     */
    fun isBlockTypeAllowed(blockState: BlockState, notify: Boolean = true, bypassClientLists: Boolean = false): Boolean {
        val blockId = IdentifierCompat.blockId(blockState.block).toString()

        // Always block special blocks unless server explicitly allows them
        val isIntegratedServer = Minecraft.getInstance().singleplayerServer != null
        if (!ServerConfigData.serverHasMod && !isIntegratedServer && ServerConfigData.SPECIAL_BLOCKS.contains(blockId)) {
            if (notify) {
                Messager.actionBar(StringUtils.translate("bedrockminer.message.restricted.server_special_block", blockState.block.name.string))
            }
            return false
        }

        if (ServerConfigData.serverHasMod && !isIntegratedServer) {
            // Server block list takes precedence
            if (ServerConfigData.serverBlockList.contains(blockId)) {
                if (notify) {
                    Messager.actionBar(StringUtils.translate("bedrockminer.message.restricted.server_block_list", blockState.block.name.string))
                }
                return false
            }

            // Server allow list
            if (!ServerConfigData.serverAllowList.contains(blockId)) {
                if (ServerConfigData.serverBlockListMode == "BLOCKED") {
                    if (notify) {
                        Messager.actionBar(
                            StringUtils.translate(
                                "bedrockminer.message.restricted.server_allow_list", blockState.block.name.string
                            )
                        )
                    }
                    return false
                }
            }
        }

        // Blocks the user explicitly configured as auto mine targets are
        // client-side intent — server rules above still apply in full.
        if (bypassClientLists) {
            return true
        }

        // Client allow list
        if (Configs.Client.ALLOW_LIST.strings.let { listContains(it, blockState) }) {
            return true
        }

        // Client block list
        if (Configs.Client.BLOCK_LIST.strings.let { listContains(it, blockState) }) {
            return false
        }

        // client mode
        val clientMode = Configs.Client.AlloeOrBlockMode.optionListValue as AllowOrBlockMode
        when (clientMode) {
            AllowOrBlockMode.BLOCKED -> return false
            AllowOrBlockMode.ALLOWED -> return true
        }
    }


    fun tryEnqueueBlock(pos: BlockPos): InteractionResult {
        val level = Minecraft.getInstance().level ?: return InteractionResult.PASS

        if (!enabled) {
            return InteractionResult.PASS
        }
        if (isInternalBreak) {
            return InteractionResult.PASS
        }
        if (isPositionProtected(pos)) {
            return InteractionResult.FAIL
        }
        val blockState = level.getBlockState(pos)
        if (!isBlockTypeAllowed(blockState)) {
            return InteractionResult.PASS
        }
        if (!AreaRestriction.isPositionAllowed(pos)) {
            Messager.actionBar(
                StringUtils.translate(
                    "bedrockminer.message.restricted.area",
                    pos.x,
                    pos.y,
                    pos.z,
                )
            )
            return InteractionResult.PASS
        }

        launchFlow(pos, blockState)
        return InteractionResult.FAIL
    }

    /**
     * Enqueue a block without any user interaction (used by [AutoMiner]).
     * Performs the same checks as [tryEnqueueBlock] but never prints messages.
     *
     * @return the launched flow, or null if the block was refused.
     */
    fun enqueueAutomatic(pos: BlockPos): BreakingFlow? {
        val level = Minecraft.getInstance().level ?: return null
        if (!enabled) return null
        if (isPositionProtected(pos)) return null
        val blockState = level.getBlockState(pos)
        val isConfiguredTarget = AutoMiner.isMineTarget(AutoMiner.targetBlockSet(), level, pos, blockState)
        if (!isBlockTypeAllowed(blockState, notify = false, bypassClientLists = isConfiguredTarget)) return null
        if (!AreaRestriction.isPositionAllowed(pos)) return null
        return launchFlow(pos, blockState)
    }

    private fun launchFlow(pos: BlockPos, blockState: BlockState): BreakingFlow {
        val flow = BreakingFlow(pos, blockState)
        val scope = this.scope ?: startConsumer()
        activeFlows.add(flow)
        scope.launch {
            try {
                flow.execute()
            } finally {
                activeFlows.remove(flow)
            }
        }
        return flow
    }

    fun cancelAllFlows() {
        activeFlows.forEach { it.doCleanUp = false }
        scope?.cancel()
        scope = null
        activeFlows.clear()
        if (enabled) startConsumer()
    }

    fun onDisconnect() {
        cancelAllFlows()
    }

    fun startConsumer(): CoroutineScope {
        val newScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        scope = newScope
        return newScope
    }
}