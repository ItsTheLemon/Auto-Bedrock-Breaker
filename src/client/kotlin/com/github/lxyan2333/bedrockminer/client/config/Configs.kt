package com.github.lxyan2333.bedrockminer.client.config

import com.github.lxyan2333.bedrockminer.client.area.AreaRestriction
import com.github.lxyan2333.bedrockminer.client.automine.AutoMiner
import com.github.lxyan2333.bedrockminer.client.breaking.BreakingFlowController
import com.github.lxyan2333.bedrockminer.client.compat.BlocksCompat.isValidBlockName
import com.github.lxyan2333.bedrockminer.client.compat.modmenu.GuiConfigs
import com.github.lxyan2333.bedrockminer.client.message.Messager
import com.github.lxyan2333.bedrockminer.compat.GsonCompat
import com.github.lxyan2333.bedrockminer.compat.IdentifierCompat
import com.google.gson.JsonObject
import com.google.common.collect.ImmutableList
import fi.dy.masa.malilib.MaLiLibReference
import fi.dy.masa.malilib.config.ConfigUtils
import fi.dy.masa.malilib.config.IConfigBase
import fi.dy.masa.malilib.config.IConfigHandler
//? if >=1.21.11 {
import fi.dy.masa.malilib.config.options.ConfigBlockState
//?}
import fi.dy.masa.malilib.config.options.ConfigString
import fi.dy.masa.malilib.config.options.ConfigBoolean
//? if >=1.18 {
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed
//?}
import fi.dy.masa.malilib.config.options.ConfigColor
//? if >=1.21 {
import fi.dy.masa.malilib.config.options.ConfigFloat
//?} else
//import fi.dy.masa.malilib.config.options.ConfigDouble
import fi.dy.masa.malilib.config.options.ConfigHotkey
import fi.dy.masa.malilib.config.options.ConfigInteger
import fi.dy.masa.malilib.config.options.ConfigOptionList
import fi.dy.masa.malilib.config.options.ConfigStringList
import fi.dy.masa.malilib.event.InputEventHandler
import fi.dy.masa.malilib.gui.GuiBase
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper
import fi.dy.masa.malilib.hotkeys.IKeybindManager
import fi.dy.masa.malilib.hotkeys.IKeybindProvider
import fi.dy.masa.malilib.util.StringUtils
import fi.dy.masa.malilib.util.FileUtils
//? if >=1.21.11 {
import fi.dy.masa.malilib.util.data.json.JsonUtils
//?} else
//import fi.dy.masa.malilib.util.JsonUtils
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SupportType
//? if <1.21
//import java.io.File
import java.nio.file.Files
import kotlin.io.path.exists

object Configs : IConfigHandler, IKeybindProvider {
    //? if >=1.21.11 {
    private val configFile = FileUtils.getConfigDirectory().resolve("bedrock-miner.json")
    //?} else if >=1.21 {
    //private val configFile = MaLiLibReference.CONFIG_DIR.resolve("bedrock-miner.json")
    //?} else
    //private val configFile = File(FileUtils.getConfigDirectory(), "bedrock-miner.json")

    object Generic {
        //? if >=1.18 {
        val BEDROCK_MINER_ENABLED: ConfigBooleanHotkeyed = ConfigBooleanHotkeyed(
            "toggleEnabled",
            false,
            "LEFT_ALT,B,M",
            StringUtils.translate("bedrockminer.config.toggle_enabled.comment"),
        ).apply {
            setValueChangeCallback { v ->
                if (v.booleanValue) {
                    BreakingFlowController.enable()
                } else {
                    BreakingFlowController.disable()
                }
            }
        }
        //?} else {
        /*val BEDROCK_MINER_ENABLED: ConfigBoolean = ConfigBoolean(
            "toggleEnabled",
            false,
            StringUtils.translate("bedrockminer.config.toggle_enabled.comment"),
        ).apply {
            setValueChangeCallback { v ->
                if (v.booleanValue) {
                    BreakingFlowController.enable()
                } else {
                    BreakingFlowController.disable()
                }
            }
        }

        val BEDROCK_MINER_ENABLE_HOTKEY: ConfigHotkey = ConfigHotkey(
            "toggleEnabledHotkey",
            "LEFT_ALT,B,M",
            StringUtils.translate("bedrockminer.config.toggle_enabled.comment"),
        ).apply {
            keybind.setCallback { _, _ ->
                BEDROCK_MINER_ENABLED.booleanValue = !BEDROCK_MINER_ENABLED.booleanValue
                true
            }
        }
        *///?}

        val APPROACH_MODE: ConfigOptionList = ConfigOptionList(
            "approachMode",
            ApproachMode.VANILLA_FAST,
            StringUtils.translate("bedrockminer.config.approach.comment"),
        )

        val OPEN_CONFIG_GUI: ConfigHotkey = ConfigHotkey(
            "openConfigGui",
            "LEFT_ALT,B,C",
            StringUtils.translate("bedrockminer.config.opengui.comment"),
        ).apply {
            keybind.setCallback { _, _ ->
                GuiBase.openGui(GuiConfigs())
                true
            }
        }

        val MAX_RETRIES: ConfigInteger = ConfigInteger(
            "maxRetries",
            2, 1, 100,
            StringUtils.translate("bedrockminer.config.max_retries.comment"),
        )

        val REMOVE_GHOST_BLOCKS: ConfigBoolean = ConfigBoolean(
            "removeGhostBlocks",
            true,
            StringUtils.translate("bedrockminer.config.remove_ghost_blocks.comment"),
        )

        val SKIP_INSTANT_MINE_CHECK: ConfigBoolean = ConfigBoolean(
            "skipInstantMineCheck",
            false,
            StringUtils.translate("bedrockminer.config.skip_instant_mine_check.comment"),
        )

        val WAIT_TICKS: ConfigInteger = ConfigInteger(
            "waitTicks",
            20, 1, 200,
            StringUtils.translate("bedrockminer.config.wait_ticks.comment"),
        )

        val TOOL_PROTECT: ConfigBoolean = ConfigBoolean(
            "toolBreakProtection",
            true,
            StringUtils.translate("bedrockminer.config.tool_protect.comment"),
        )

        val AUTO_EAT: ConfigBoolean = ConfigBoolean(
            "autoEat",
            true,
            StringUtils.translate("bedrockminer.config.auto_eat.comment"),
        )

        // Note: renamed key (was toolBreakProtectionThreshold) so stale saved
        // values from old builds don't override the new 150 default.
        val TOOL_PROTECT_THRESHOLD: ConfigInteger = ConfigInteger(
            "toolProtectMinDurability",
            150, 25, 600, true,
            StringUtils.translate("bedrockminer.config.tool_protect_threshold.comment"),
        )

        //? if >=1.21.11 {
        val SUPPORT_BLOCK: ConfigBlockState = ConfigBlockState(
            "supportBlock",
            Blocks.SLIME_BLOCK.defaultBlockState(),
            StringUtils.translate("bedrockminer.config.support_block.comment"),
        ).apply {
            setValueChangeCallback { config ->
                config.blockStateValue.cache?.let {
                    if (!isValidSupportBlock(config.blockStateValue.block)) {
                        config.setBlockStateValue(config.lastBlockStateValue)
                        Messager.actionBar(StringUtils.translate("bedrockminer.message.invalid_support_block"))
                    }
                }
            }
        }
        //?} else {
        /*val SUPPORT_BLOCK: ConfigString = ConfigString(
            "supportBlock",
            "minecraft:slime_block",
            StringUtils.translate("bedrockminer.config.support_block.comment"),
        ).apply {
            setValueChangeCallback { config ->
                val block = blockFromName(config.stringValue)
                if (block == null || !isValidSupportBlock(block)) {
                    config.setValueFromString(config.defaultStringValue)
                    Messager.actionBar(StringUtils.translate("bedrockminer.message.invalid_support_block"))
                }
            }
        }
        *///?}

        val supportBlock: Block
            get() {
                //? if >=1.21.11 {
                return SUPPORT_BLOCK.blockStateValue.block
                //?} else
                //return blockFromName(SUPPORT_BLOCK.stringValue)?.takeIf(::isValidSupportBlock) ?: Blocks.SLIME_BLOCK
            }

        val OPTIONS: List<IConfigBase> = listOf(
            BEDROCK_MINER_ENABLED,
            //? if <1.18
            //BEDROCK_MINER_ENABLE_HOTKEY,
            APPROACH_MODE,
            OPEN_CONFIG_GUI,
            MAX_RETRIES,
            WAIT_TICKS,
            SUPPORT_BLOCK,
            TOOL_PROTECT,
            TOOL_PROTECT_THRESHOLD,
            AUTO_EAT,
            REMOVE_GHOST_BLOCKS,
            SKIP_INSTANT_MINE_CHECK,
        )
    }

    object Client {
        val BLOCK_LIST: ConfigStringList = ConfigStringList(
            "blockList",
            ImmutableList.of(),
            StringUtils.translate("bedrockminer.config.blockList.comment"),
        ).apply {
            setValueChangeCallback { config ->
                val invalid = config.strings.firstOrNull { if (it == "") false else !isValidBlockName(it) }
                if (invalid != null) {
                    Messager.actionBar(StringUtils.translate("bedrockminer.message.invalid_block_name", invalid))
                    //? if >=1.21.11 {
                    config.setStrings(config.lastStringListValue)
                    //?} else
                    //config.setStrings(config.defaultStrings)
                }
            }
        }

        private fun defaultBedrockName(): String {
            val localBedrockName = StringUtils.translate("bedrockminer.config.client.bedrockname")
            if (isValidBlockName(localBedrockName)) {
                return localBedrockName
            }
            return "minecraft:bedrock"
        }

        val ALLOW_LIST: ConfigStringList = ConfigStringList(
            "allowList",
            ImmutableList.of(defaultBedrockName()),
            StringUtils.translate("bedrockminer.config.allowList.comment"),
        ).apply {
            setValueChangeCallback { config ->
                val invalid = config.strings.firstOrNull { if (it == "") false else !isValidBlockName(it) }
                if (invalid != null) {
                    Messager.actionBar(StringUtils.translate("bedrockminer.message.invalid_block_name", invalid))
                    //? if >=1.21.11 {
                    config.setStrings(config.lastStringListValue)
                    //?} else
                    //config.setStrings(config.defaultStrings)
                }
            }
        }

        val AlloeOrBlockMode: ConfigOptionList = ConfigOptionList(
            "blockListMode",
            AllowOrBlockMode.BLOCKED,
            StringUtils.translate("bedrockminer.config.alloworblockmode.comment"),
        )

        val OPTIONS: List<IConfigBase> = listOf(BLOCK_LIST, ALLOW_LIST, AlloeOrBlockMode)
    }

    object Server {
        val BLOCK_LIST: ConfigStringList = ConfigStringList(
            "blockList",
            ImmutableList.of(),
            StringUtils.translate("bedrockminer.config.server.blockList.comment"),
        )

        val ALLOW_LIST: ConfigStringList = ConfigStringList(
            "allowList",
            ImmutableList.of(),
            StringUtils.translate("bedrockminer.config.server.allowList.comment"),
        )

        val AllowBlockMode: ConfigOptionList = ConfigOptionList(
            "blockListMode",
            AllowOrBlockMode.BLOCKED,
            StringUtils.translate("bedrockminer.config.server.alloworblockmode.comment"),
        )

        val WAIT_SERVER_TICK_PLAYER_ENTITY_TICKS: ConfigInteger = ConfigInteger(
            "waitServerTickPlayerEntityTicks",
            2, 0, 100,
            StringUtils.translate("bedrockminer.config.server.waitServerTickPlayerEntityTicks.comment"),
        )

        val OPTIONS: List<IConfigBase> =
            listOf(WAIT_SERVER_TICK_PLAYER_ENTITY_TICKS, BLOCK_LIST, ALLOW_LIST, AllowBlockMode)

        val ALL_OPTIONS_WRAPPER: List<ConfigOptionWrapper> = listOf(
            ConfigOptionWrapper(WAIT_SERVER_TICK_PLAYER_ENTITY_TICKS),
            ConfigOptionWrapper("bedrockminer.config.server.note"),
            ConfigOptionWrapper("bedrockminer.config.server.note2"),
            ConfigOptionWrapper(BLOCK_LIST),
            ConfigOptionWrapper(ALLOW_LIST),
            ConfigOptionWrapper(AllowBlockMode),
        )

    }

    object Area {
        val AREA_RESTRICTION_ENABLED: ConfigBoolean = ConfigBoolean(
            "areaRestrictionEnabled",
            false,
            StringUtils.translate("bedrockminer.config.area.area_restriction_enabled.comment"),
        )

        val RESTRICT_MINING_AREA: ConfigStringList = ConfigStringList(
            "restrictMiningArea",
            ImmutableList.of(),
            StringUtils.translate("bedrockminer.config.area.restrict_mining_area.comment"),
        ).apply {
            setValueChangeCallback { config ->
                val invalid = config.strings.firstOrNull { it.isNotEmpty() && !AreaRestriction.isValidArea(it) }
                if (invalid != null) {
                    Messager.actionBar(StringUtils.translate("bedrockminer.message.invalid_area", invalid))
                    //? if >=1.21.11 {
                    config.setStrings(config.lastStringListValue)
                    //?} else
                    //config.setStrings(config.defaultStrings)
                }
            }
        }

        val AREA_BOX_COLOR: ConfigColor = ConfigColor(
            "areaBoxColor",
            "#FF40FF78",
            StringUtils.translate("bedrockminer.config.area.area_box_color.comment"),
        )

        val HIDE_AREA_BOX_BEHIND_BLOCKS: ConfigBoolean = ConfigBoolean(
            "hideAreaBoxBehindBlocks",
            false,
            StringUtils.translate("bedrockminer.config.area.hide_area_box_behind_blocks.comment"),
        )

        //? if >=1.21 {
        val AREA_BOX_LINE_WIDTH: ConfigFloat = ConfigFloat(
            "areaBoxLineWidth",
            2.0f,
            0.5f,
            8.0f,
            true,
            StringUtils.translate("bedrockminer.config.area.area_box_line_width.comment"),
        )
        //?} else {
        /*val AREA_BOX_LINE_WIDTH: ConfigDouble = ConfigDouble(
            "areaBoxLineWidth",
            2.0,
            0.5,
            8.0,
            true,
            StringUtils.translate("bedrockminer.config.area.area_box_line_width.comment"),
        )
        *///?}

        val areaBoxLineWidth: Float
            get() {
                //? if >=1.21 {
                return AREA_BOX_LINE_WIDTH.floatValue
                //?} else
                //return AREA_BOX_LINE_WIDTH.doubleValue.toFloat()
            }

        val OPTIONS: List<IConfigBase> = listOf(
            AREA_RESTRICTION_ENABLED,
            RESTRICT_MINING_AREA,
            AREA_BOX_COLOR,
            HIDE_AREA_BOX_BEHIND_BLOCKS,
            AREA_BOX_LINE_WIDTH,
        )
    }

    /**
     * Auto mine: keep breaking the configured target blocks inside the box
     * spanned by [POS1] and [POS2], without the player clicking on them.
     * Only blocks the player can actually reach are targeted.
     */
    object AutoMine {
        //? if >=1.18 {
        val ENABLED: ConfigBooleanHotkeyed = ConfigBooleanHotkeyed(
            "autoMineEnabled",
            false,
            "LEFT_ALT,B,A",
            StringUtils.translate("bedrockminer.config.automine.enabled.comment"),
        ).apply {
            setValueChangeCallback { v -> AutoMiner.onToggled(v.booleanValue) }
        }
        //?} else {
        /*val ENABLED: ConfigBoolean = ConfigBoolean(
            "autoMineEnabled",
            false,
            StringUtils.translate("bedrockminer.config.automine.enabled.comment"),
        ).apply {
            setValueChangeCallback { v -> AutoMiner.onToggled(v.booleanValue) }
        }

        val ENABLE_HOTKEY: ConfigHotkey = ConfigHotkey(
            "autoMineEnabledHotkey",
            "LEFT_ALT,B,A",
            StringUtils.translate("bedrockminer.config.automine.enabled.comment"),
        ).apply {
            keybind.setCallback { _, _ ->
                ENABLED.booleanValue = !ENABLED.booleanValue
                true
            }
        }
        *///?}

        val POS1: ConfigString = ConfigString(
            "autoMinePos1",
            "",
            StringUtils.translate("bedrockminer.config.automine.pos1.comment"),
        ).apply {
            setValueChangeCallback { config -> validatePos(config) }
        }

        val POS2: ConfigString = ConfigString(
            "autoMinePos2",
            "",
            StringUtils.translate("bedrockminer.config.automine.pos2.comment"),
        ).apply {
            setValueChangeCallback { config -> validatePos(config) }
        }

        val TARGET_BLOCKS: ConfigStringList = ConfigStringList(
            "autoMineTargetBlocks",
            ImmutableList.of("minecraft:bedrock"),
            StringUtils.translate("bedrockminer.config.automine.target_blocks.comment"),
        ).apply {
            setValueChangeCallback { config ->
                val invalid = config.strings.firstOrNull { if (it == "") false else !isValidBlockName(it) }
                if (invalid != null) {
                    Messager.actionBar(StringUtils.translate("bedrockminer.message.invalid_block_name", invalid))
                    //? if >=1.21.11 {
                    config.setStrings(config.lastStringListValue)
                    //?} else
                    //config.setStrings(config.defaultStrings)
                }
            }
        }

        /** Also pickaxe-mine every other breakable block inside the box. */
        val CLEAR_ALL_BLOCKS: ConfigBoolean = ConfigBoolean(
            "autoMineClearNonTargets",
            true,
            StringUtils.translate("bedrockminer.config.automine.clear_all_blocks.comment"),
        )

        val SKIP_BLOCKS_TOUCHING_PLAYER: ConfigBoolean = ConfigBoolean(
            "autoMineSkipBlocksTouchingPlayer",
            true,
            StringUtils.translate("bedrockminer.config.automine.skip_blocks_touching_player.comment"),
        )

        val SCAN_INTERVAL: ConfigInteger = ConfigInteger(
            "autoMineScanIntervalTicks",
            10, 1, 200,
            StringUtils.translate("bedrockminer.config.automine.scan_interval.comment"),
        )

        val MAX_CONCURRENT: ConfigInteger = ConfigInteger(
            "autoMineMaxConcurrent",
            1, 1, 20, true,
            StringUtils.translate("bedrockminer.config.automine.max_concurrent.comment"),
        )

        val LAUNCH_DELAY: ConfigInteger = ConfigInteger(
            "autoMineLaunchDelayTicks",
            2, 0, 40, true,
            StringUtils.translate("bedrockminer.config.automine.launch_delay.comment"),
        )

        //? if >=1.21 {
        val MAX_RANGE: ConfigFloat = ConfigFloat(
            "autoMineMaxRange",
            3.5f, 1.0f, 4.5f, true,
            StringUtils.translate("bedrockminer.config.automine.max_range.comment"),
        )
        //?} else {
        /*val MAX_RANGE: ConfigDouble = ConfigDouble(
            "autoMineMaxRange",
            3.5, 1.0, 4.5, true,
            StringUtils.translate("bedrockminer.config.automine.max_range.comment"),
        )
        *///?}

        val maxRange: Double
            get() {
                //? if >=1.21 {
                return MAX_RANGE.floatValue.toDouble()
                //?} else
                //return MAX_RANGE.doubleValue
            }

        val FAIL_COOLDOWN: ConfigInteger = ConfigInteger(
            "autoMineFailCooldownTicks",
            100, 1, 6000,
            StringUtils.translate("bedrockminer.config.automine.fail_cooldown.comment"),
        )

        val SHOW_BOX: ConfigBoolean = ConfigBoolean(
            "autoMineShowBox",
            true,
            StringUtils.translate("bedrockminer.config.automine.show_box.comment"),
        )

        val BOX_COLOR: ConfigColor = ConfigColor(
            "autoMineBoxColor",
            "#FFFF9A1F",
            StringUtils.translate("bedrockminer.config.automine.box_color.comment"),
        )

        val BOX_FILL_COLOR: ConfigColor = ConfigColor(
            "autoMineBoxFillColor",
            "#30FF9A1F",
            StringUtils.translate("bedrockminer.config.automine.box_fill_color.comment"),
        )

        //? if >=1.21 {
        val OVERLAY_LINE_WIDTH: ConfigFloat = ConfigFloat(
            "overlayBeamWidth",
            3.0f, 1.0f, 10.0f, true,
            StringUtils.translate("bedrockminer.config.automine.overlay_line_width.comment"),
        )
        //?} else {
        /*val OVERLAY_LINE_WIDTH: ConfigDouble = ConfigDouble(
            "overlayBeamWidth",
            3.0, 1.0, 10.0, true,
            StringUtils.translate("bedrockminer.config.automine.overlay_line_width.comment"),
        )
        *///?}

        val overlayLineWidth: Float
            get() {
                //? if >=1.21 {
                return OVERLAY_LINE_WIDTH.floatValue
                //?} else
                //return OVERLAY_LINE_WIDTH.doubleValue.toFloat()
            }

        val OPTIONS: List<IConfigBase> = listOf(
            ENABLED,
            //? if <1.18
            //ENABLE_HOTKEY,
            POS1,
            POS2,
            TARGET_BLOCKS,
            CLEAR_ALL_BLOCKS,
            SKIP_BLOCKS_TOUCHING_PLAYER,
            MAX_RANGE,
            SCAN_INTERVAL,
            MAX_CONCURRENT,
            LAUNCH_DELAY,
            FAIL_COOLDOWN,
            SHOW_BOX,
            BOX_COLOR,
            BOX_FILL_COLOR,
            OVERLAY_LINE_WIDTH,
        )

        private fun validatePos(config: ConfigString) {
            if (!AutoMiner.isValidPosString(config.stringValue)) {
                Messager.actionBar(StringUtils.translate("bedrockminer.message.automine.invalid_pos", config.stringValue))
                config.setValueFromString("")
            }
        }
    }

    override fun load() {
        try {
            val element = GsonCompat.parseFile(configFile) ?: return
            if (element.isJsonObject) {
                ConfigUtils.readConfigBase(element.asJsonObject, "Generic", Generic.OPTIONS)
                ConfigUtils.readConfigBase(element.asJsonObject, "Client", Client.OPTIONS)
                ConfigUtils.readConfigBase(element.asJsonObject, "Server", Server.OPTIONS)
                ConfigUtils.readConfigBase(element.asJsonObject, "Area", Area.OPTIONS)
                ConfigUtils.readConfigBase(element.asJsonObject, "AutoMine", AutoMine.OPTIONS)
            }
        } catch (_: Exception) {
        }
    }

    override fun save() {
        try {
            //? if >=1.21.11 {
            if (!FileUtils.getConfigDirectory().exists()) {
                Files.createDirectories(FileUtils.getConfigDirectory())
            }
            //?} else if >=1.21 {
            //if (!MaLiLibReference.CONFIG_DIR.exists()) {
            //    Files.createDirectories(MaLiLibReference.CONFIG_DIR)
            //}
            //?} else
            //FileUtils.getConfigDirectory().mkdirs()
            val root = JsonObject()
            ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS)
            ConfigUtils.writeConfigBase(root, "Client", Client.OPTIONS)
            ConfigUtils.writeConfigBase(root, "Server", Server.OPTIONS)
            ConfigUtils.writeConfigBase(root, "Area", Area.OPTIONS)
            ConfigUtils.writeConfigBase(root, "AutoMine", AutoMine.OPTIONS)
            //? if >=1.21.11 {
            JsonUtils.writeJsonToFile(root, configFile)
            //?} else if >=1.21 {
            //JsonUtils.writeJsonToFileAsPath(root, configFile)
            //?} else
            //JsonUtils.writeJsonToFile(root, configFile)
        } catch (_: Exception) {
        }
    }

    override fun onConfigsChanged() {
        super.onConfigsChanged()
        BreakingFlowController.cancelAllFlows()
    }

    override fun addHotkeys(manager: IKeybindManager?) {
        manager?.addHotkeysForCategory(
            "bedrock-miner",
            "bedrock-miner.hotkeys.generic",
            listOf(
                //? if >=1.18 {
                Generic.BEDROCK_MINER_ENABLED,
                AutoMine.ENABLED,
                //?} else {
                /*Generic.BEDROCK_MINER_ENABLE_HOTKEY,
                AutoMine.ENABLE_HOTKEY,
                *///?}
                Generic.OPEN_CONFIG_GUI,
            )
        )
    }

    override fun addKeysToMap(manager: IKeybindManager?) {
        //? if >=1.18 {
        manager?.addKeybindToMap(Generic.BEDROCK_MINER_ENABLED.keybind)
        manager?.addKeybindToMap(AutoMine.ENABLED.keybind)
        //?} else {
        /*manager?.addKeybindToMap(Generic.BEDROCK_MINER_ENABLE_HOTKEY.keybind)
        manager?.addKeybindToMap(AutoMine.ENABLE_HOTKEY.keybind)
        *///?}
        manager?.addKeybindToMap(Generic.OPEN_CONFIG_GUI.keybind)
    }

    fun init() {
        InputEventHandler.getKeybindManager().registerKeybindProvider(this)
    }

    private fun blockFromName(name: String): Block? {
        return IdentifierCompat.block(name)
    }

    private fun isValidSupportBlock(block: Block): Boolean {
        return block.defaultBlockState().cache?.isFaceSturdy(Direction.UP, SupportType.CENTER) == true
    }
}
