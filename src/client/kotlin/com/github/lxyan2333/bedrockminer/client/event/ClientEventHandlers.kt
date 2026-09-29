package com.github.lxyan2333.bedrockminer.client.event

import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.world.InteractionResult
import com.github.lxyan2333.bedrockminer.client.automate.AutoPilot
import com.github.lxyan2333.bedrockminer.client.automine.AutoMiner
import com.github.lxyan2333.bedrockminer.client.breaking.BreakingFlowController
import com.github.lxyan2333.bedrockminer.client.command.ModClientCommands
import com.github.lxyan2333.bedrockminer.client.breaking.ClientTickScheduler
import com.github.lxyan2333.bedrockminer.client.config.Configs
import com.github.lxyan2333.bedrockminer.client.config.ClientConfigHandler
import com.github.lxyan2333.bedrockminer.client.message.Messager
import com.github.lxyan2333.bedrockminer.client.network.ClientNetworkHandler
import com.github.lxyan2333.bedrockminer.client.render.AreaRenderer
import com.github.lxyan2333.bedrockminer.network.ModNetwork
import fi.dy.masa.malilib.config.ConfigManager
import fi.dy.masa.malilib.event.RenderEventHandler
import fi.dy.masa.malilib.util.StringUtils
//? if >= 26.2
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.block.Blocks

object ClientEventHandlers {
    //? if < 26.2
    //private var lastLevel: ClientLevel? = null


    private fun showBedrockRightClickHint() {
        //? if >=1.18 {
        val toggleHotkey = Configs.Generic.BEDROCK_MINER_ENABLED.keybind.keysDisplayString
        //?} else
        //val toggleHotkey = Configs.Generic.BEDROCK_MINER_ENABLE_HOTKEY.keybind.keysDisplayString
        val configHotkey = Configs.Generic.OPEN_CONFIG_GUI.keybind.keysDisplayString
        Messager.actionBar(
            StringUtils.translate(
                "bedrockminer.message.bedrock_right_click_hint", toggleHotkey, configHotkey
            )
        )
    }

    fun register() {
        ConfigManager.getInstance().registerConfigHandler("bedrock-miner", Configs)
        Configs.init()
        RenderEventHandler.getInstance().registerWorldLastRenderer(AreaRenderer)
        //? if >=26.2
        RenderEventHandler.getInstance().registerInGameGuiRenderer(AreaRenderer)
        ModNetwork.registerPayloadTypes()
        ClientNetworkHandler.registerClientHandlers()
        ModClientCommands.register()

        UseBlockCallback.EVENT.register { player, world, _, hitResult ->
            if (world.isClientSide && player.mainHandItem.isEmpty && world.getBlockState(hitResult.blockPos).block == Blocks.BEDROCK) {
                showBedrockRightClickHint()
            }
            InteractionResult.PASS
        }

        AttackBlockCallback.EVENT.register { _, world, _, pos, _ ->
            if (!world.isClientSide) {
                return@register InteractionResult.PASS
            }

            return@register BreakingFlowController.tryEnqueueBlock(pos)
        }

        //? if >= 26.2 {
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ ->
            AutoPilot.abandon()
            BreakingFlowController.cancelAllFlows()
            AutoMiner.reset()
        }
        ClientTickEvents.END_LEVEL_TICK.register {
            // AutoPilot first: it decides whether AutoMiner may launch this tick.
            AutoPilot.onTick()
            ClientTickScheduler.onTick()
            AutoMiner.onTick()
        }
        //? } else {
        /*ClientTickEvents.START_CLIENT_TICK.register { client ->
            val currentLevel = client.level ?: return@register
            if (currentLevel != lastLevel) {
                lastLevel = currentLevel
                AutoPilot.abandon()
                BreakingFlowController.cancelAllFlows()
                AutoMiner.reset()
            }
            AutoPilot.onTick()
            ClientTickScheduler.onTick()
            AutoMiner.onTick()
        }
        *///?}

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            if (Configs.Generic.BEDROCK_MINER_ENABLED.booleanValue) {
                BreakingFlowController.enable()
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            AutoPilot.abandon()
            BreakingFlowController.onDisconnect()
            AutoMiner.reset()
            ClientConfigHandler.resetOnDisconnect()
        }
    }
}
