package com.github.lxyan2333.bedrockminer.client.compat.modmenu

import com.github.lxyan2333.bedrockminer.client.automine.AutoMiner
import com.github.lxyan2333.bedrockminer.client.config.Configs
import fi.dy.masa.malilib.gui.GuiConfigsBase
import fi.dy.masa.malilib.gui.button.ButtonBase
import fi.dy.masa.malilib.gui.button.ButtonGeneric
import fi.dy.masa.malilib.gui.button.IButtonActionListener
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase
import fi.dy.masa.malilib.util.StringUtils
import net.minecraft.client.Minecraft

class GuiConfigs : GuiConfigsBase(10, 50, "bedrock-miner", null, "bedrockminer.gui.title.configs") {

    override fun initGui() {
        super.initGui()
        this.clearOptions()

        var x = 10
        val y = 26

        for (tab in ConfigGuiTab.entries) {
            val button = ButtonGeneric(x, y, -1, 20, tab.displayName)
            button.setEnabled(ConfigGuiState.currentTab != tab)
            this.addButton(button, ButtonListener(tab, this))
            x += button.width + 2
        }
    }

    override fun createListWidget(listX: Int, listY: Int): WidgetListConfigOptions {
        return WidgetListConfigOptionsPosButtons(
            listX, listY,
            this.getBrowserWidth(), this.getBrowserHeight(), this.getConfigWidth(),
            0f, this.useKeybindSearch(), this,
        )
    }

    override fun getConfigs(): List<ConfigOptionWrapper> {
        return when (ConfigGuiState.currentTab) {
            ConfigGuiTab.GENERIC -> ConfigOptionWrapper.createFor(Configs.Generic.OPTIONS)
            ConfigGuiTab.CLIENT -> ConfigOptionWrapper.createFor(Configs.Client.OPTIONS)
            ConfigGuiTab.SERVER -> Configs.Server.ALL_OPTIONS_WRAPPER
            ConfigGuiTab.AREA -> ConfigOptionWrapper.createFor(Configs.Area.OPTIONS)
            ConfigGuiTab.AUTO_MINE -> ConfigOptionWrapper.createFor(Configs.AutoMine.OPTIONS)
        }
    }

    private class ButtonListener(
        private val tab: ConfigGuiTab,
        private val parent: GuiConfigs,
    ) : IButtonActionListener {
        override fun actionPerformedWithButton(button: ButtonBase?, mouseButton: Int) {
            ConfigGuiState.currentTab = tab
            this.parent.reCreateListWidget()
            this.parent.getListWidget()?.resetScrollbarPosition()
            this.parent.initGui()
        }
    }

    /** Config list that adds a "Set here" button to the Pos1/Pos2 rows. */
    private class WidgetListConfigOptionsPosButtons(
        x: Int, y: Int, width: Int, height: Int, configWidth: Int,
        zLevel: Float, useKeybindSearch: Boolean, parent: GuiConfigsBase,
    ) : WidgetListConfigOptions(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent) {

        override fun createListEntryWidget(
            x: Int, y: Int, listIndex: Int, isOdd: Boolean, entry: ConfigOptionWrapper,
        ): WidgetConfigOption {
            if (entry.type == ConfigOptionWrapper.Type.CONFIG) {
                val posIndex = when {
                    entry.config === Configs.AutoMine.POS1 -> 1
                    entry.config === Configs.AutoMine.POS2 -> 2
                    else -> 0
                }
                if (posIndex != 0) {
                    return WidgetConfigOptionSetHere(
                        x, y, this.browserEntryWidth, this.browserEntryHeight,
                        this.maxLabelWidth, this.configWidth,
                        entry, listIndex, this.parent, this, posIndex,
                    )
                }
            }
            return super.createListEntryWidget(x, y, listIndex, isOdd, entry)
        }
    }

    /**
     * A Pos1/Pos2 config row with a "Set here" button placed right after the
     * reset button. Clicking it stores the player's current position in the
     * config and in the row's text field.
     */
    private class WidgetConfigOptionSetHere(
        x: Int, y: Int, width: Int, height: Int, labelWidth: Int, configWidth: Int,
        wrapper: ConfigOptionWrapper, listIndex: Int, host: IKeybindConfigGui,
        parent: WidgetListConfigOptionsBase<*, *>,
        posIndex: Int,
    ) : WidgetConfigOption(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent) {

        init {
            // Mirror the row layout: label (labelWidth + 10), text field
            // (configWidth + 2), then the reset button, then our button.
            val resetWidth = ButtonGeneric(0, 0, -1, 20, StringUtils.translate("malilib.gui.button.reset.caps")).width
            val buttonX = x + labelWidth + 10 + configWidth + 2 + resetWidth + 4
            val label = StringUtils.translate("bedrockminer.gui.button.automine.set_here")
            val button = ButtonGeneric(buttonX, y + 1, -1, 20, label)
            button.setEnabled(Minecraft.getInstance().player != null)
            this.addButton(button) { _, _ ->
                if (Minecraft.getInstance().player != null) {
                    val config = if (posIndex == 1) Configs.AutoMine.POS1 else Configs.AutoMine.POS2
                    if (posIndex == 1) AutoMiner.setPos1Here() else AutoMiner.setPos2Here()
                    // Keep the visible text field in sync, otherwise closing the
                    // screen would write the stale text back into the config.
                    this.textField?.textField()?.setValue(config.stringValue)
                }
            }
        }
    }

    enum class ConfigGuiTab(val translationKey: String) {
        GENERIC("bedrockminer.gui.button.config_gui.generic"),
        AREA("bedrockminer.gui.button.config_gui.area"),
        AUTO_MINE("bedrockminer.gui.button.config_gui.automine"),
        CLIENT("bedrockminer.gui.button.config_gui.client"),
        SERVER("bedrockminer.gui.button.config_gui.server");

        val displayName: String
            get() = StringUtils.translate(translationKey)
    }
}

object ConfigGuiState {
    var currentTab: GuiConfigs.ConfigGuiTab = GuiConfigs.ConfigGuiTab.GENERIC
}
