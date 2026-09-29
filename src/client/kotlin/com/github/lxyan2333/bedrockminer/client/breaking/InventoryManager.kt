package com.github.lxyan2333.bedrockminer.client.breaking

import com.github.lxyan2333.bedrockminer.client.compat.MinecraftClientCompat
import com.github.lxyan2333.bedrockminer.client.message.Messager
import net.minecraft.client.Minecraft
//? if >=1.21
import net.minecraft.core.registries.Registries
import net.minecraft.tags.FluidTags
import net.minecraft.world.effect.MobEffectUtil
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.entity.ai.attributes.Attributes
import fi.dy.masa.malilib.util.StringUtils
import com.github.lxyan2333.bedrockminer.client.config.Configs
//? if >= 1.21.1 {
import net.minecraft.client.gui.screens.inventory.InventoryScreen
//?}

object InventoryManager {
    private const val INSTANT_MINE_THRESHOLD = 45f

    //? if >= 1.21.1 {
    fun switchItemToOffHand(item: Item): Boolean {
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        val inventoryScreen = InventoryScreen(player)
        for (i in player.inventoryMenu.slots) {
            if (i.item.`is`(item)) {
                MinecraftClientCompat.swapInventorySlot(
                    inventoryScreen.menu.containerId, i.index, 40, player
                )
                return true
            }
        }
        return false
    }
    //?}

    suspend fun switchToItem(item: Item): Boolean {
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        val inventory = player.inventory

        // A nearly broken tool is never accepted, even if it is already in hand.
        fun selectionSatisfied(): Boolean {
            val selected = MinecraftClientCompat.getSelectedItem(inventory)
            return if (item == Items.DIAMOND_PICKAXE) {
                !isToolProtected(selected) &&
                    (isEligiblePickaxe(selected) ||
                        getBlockBreakingSpeed(Blocks.PISTON.defaultBlockState(), selected) > INSTANT_MINE_THRESHOLD)
            } else {
                selected.item == item
            }
        }

        if (selectionSatisfied()) return true

        val slot = if (item == Items.DIAMOND_PICKAXE) {
            getEfficientToolSlot(inventory)
        } else {
            findSlotWithItem(inventory, item)
        }
        if (slot == -1) return false

        // With a foreign container open (chest, furnace, ...), slot indices
        // belong to that menu — a swap would move the wrong items or silently
        // fail. Refuse the switch; the breaking guards then hold off safely.
        if (player.containerMenu !== player.inventoryMenu) return false

        if (Inventory.isHotbarSlot(slot)) {
            MinecraftClientCompat.setSelectedSlot(inventory, slot)
            client.connection?.send(ServerboundSetCarriedItemPacket(MinecraftClientCompat.selectedSlot(inventory)))
        } else {
            pickFromInventory(slot)
            client.connection?.send(ServerboundSetCarriedItemPacket(MinecraftClientCompat.selectedSlot(inventory)))
            // VERIFY the swap actually took effect; if the SWAP click did not
            // work here, fall back to plain pickup clicks and check again.
            ClientTickScheduler.awaitTicks(1)
            if (!selectionSatisfied()) {
                pickupSwapFromInventory(slot)
                client.connection?.send(ServerboundSetCarriedItemPacket(MinecraftClientCompat.selectedSlot(inventory)))
                ClientTickScheduler.awaitTicks(1)
            }
        }
        //? if >= 1.21.1 {
        if (item == Items.DIAMOND_PICKAXE) {
            ClientTickScheduler.awaitTicks(Configs.Server.WAIT_SERVER_TICK_PLAYER_ENTITY_TICKS.integerValue)
        }
        //?}
        // Never report success with the wrong (or a protected) tool in hand.
        val satisfied = selectionSatisfied()
        if (satisfied && item == Items.DIAMOND_PICKAXE) {
            // Tell the player their pickaxe was rotated out.
            Messager.actionBar(StringUtils.translate("bedrockminer.message.pickaxe_swapped"))
            com.github.lxyan2333.bedrockminer.client.automate.AutoPilot.externalEvent("bedrockminer.hud.event.pick_swapped")
        }
        return satisfied
    }

    private fun pickFromInventory(slot: Int) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val inventory = player.inventory

        val switch = inventory.suitableHotbarSlot
        MinecraftClientCompat.swapInventorySlot(player.containerMenu.containerId, slot, switch, player)
        MinecraftClientCompat.setSelectedSlot(inventory, switch)
    }

    /**
     * Fallback swap using three PICKUP clicks (take stack, place into a
     * hotbar slot, put the old stack back). Main-inventory indices 9-35 map
     * 1:1 to inventory-menu slots; hotbar index h is menu slot 36+h.
     */
    private fun pickupSwapFromInventory(slot: Int) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val inventory = player.inventory
        if (slot < 9) return // hotbar handled elsewhere

        val hotbar = inventory.suitableHotbarSlot
        val containerId = player.containerMenu.containerId
        MinecraftClientCompat.pickupClick(containerId, slot, 0, player)
        MinecraftClientCompat.pickupClick(containerId, 36 + hotbar, 0, player)
        MinecraftClientCompat.pickupClick(containerId, slot, 0, player)
        MinecraftClientCompat.setSelectedSlot(inventory, hotbar)
    }

    private fun findSlotWithItem(inventory: Inventory, item: Item): Int {
        for (i in 0 until inventory.containerSize) {
            if (MinecraftClientCompat.stackIs(inventory.getItem(i), item)) return i
        }
        return -1
    }

    private fun getEfficientToolSlot(inventory: Inventory): Int {
        if (Configs.Generic.SKIP_INSTANT_MINE_CHECK.booleanValue) {
            var bestSlot = -1
            var bestSpeed = 0f
            for (i in 0 until inventory.containerSize) {
                val stack = inventory.getItem(i)
                if (isToolProtected(stack)) continue
                val speed = getBlockBreakingSpeed(Blocks.PISTON.defaultBlockState(), stack)
                if (speed > bestSpeed) {
                    bestSpeed = speed
                    bestSlot = i
                }
            }
            return bestSlot
        }
        // Prefer a pickaxe that instant-mines right now; otherwise accept any
        // healthy diamond/netherite Efficiency V pickaxe, whatever its other
        // enchants or name.
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (isToolProtected(stack)) continue
            if (getBlockBreakingSpeed(Blocks.PISTON.defaultBlockState(), stack) > INSTANT_MINE_THRESHOLD) {
                return i
            }
        }
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (isToolProtected(stack)) continue
            if (isEligiblePickaxe(stack)) return i
        }
        return -1
    }

    fun canInstantlyMinePiston(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        val inventory = player.inventory
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (stack.isEmpty || isToolProtected(stack)) continue
            if (getBlockBreakingSpeed(Blocks.PISTON.defaultBlockState(), stack) > INSTANT_MINE_THRESHOLD) {
                return true
            }
        }
        return false
    }

    private fun canInstantlyMinePistonIgnoringProtection(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        val inventory = player.inventory
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (stack.isEmpty) continue
            if (getBlockBreakingSpeed(Blocks.PISTON.defaultBlockState(), stack) > INSTANT_MINE_THRESHOLD) {
                return true
            }
        }
        return false
    }

    private fun remainingDurability(stack: ItemStack): Int {
        return if (stack.isDamageableItem) stack.maxDamage - stack.damageValue else Int.MAX_VALUE
    }

    /** True when the tool currently in hand is blacklisted — refuse to swing it. */
    fun isSelectedToolProtected(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return isToolProtected(MinecraftClientCompat.getSelectedItem(player.inventory))
    }

    /** Nearly broken tools are fully blacklisted: never selected, never used. */
    fun isToolProtected(stack: ItemStack): Boolean {
        if (!Configs.Generic.TOOL_PROTECT.booleanValue) return false
        if (stack.isEmpty || !stack.isDamageableItem) return false
        return remainingDurability(stack) <= Configs.Generic.TOOL_PROTECT_THRESHOLD.integerValue
    }

    /** True when the only fast-enough pickaxes left are all nearly broken. */
    fun toolsWorn(): Boolean {
        if (!Configs.Generic.TOOL_PROTECT.booleanValue) return false
        if (Configs.Generic.SKIP_INSTANT_MINE_CHECK.booleanValue) return false
        return !canInstantlyMinePiston() && canInstantlyMinePistonIgnoringProtection()
    }

    /**
     * Remaining durability of the pickaxe that is actually in use right now —
     * the selected one when it is a fast pickaxe, otherwise the one the next
     * switch would pick. This is the number that visibly counts down.
     */
    fun activePickaxeRemainingDurability(): Int? {
        val player = Minecraft.getInstance().player ?: return null
        val selected = MinecraftClientCompat.getSelectedItem(player.inventory)
        if (!selected.isEmpty && selected.isDamageableItem &&
            (isEligiblePickaxe(selected) ||
                getBlockBreakingSpeed(Blocks.PISTON.defaultBlockState(), selected) > INSTANT_MINE_THRESHOLD)
        ) {
            return remainingDurability(selected)
        }
        return bestPickaxeRemainingDurability()
    }

    /** Remaining durability of the best usable (unprotected) pickaxe, for the HUD. */
    fun bestPickaxeRemainingDurability(): Int? {
        val player = Minecraft.getInstance().player ?: return null
        val inventory = player.inventory
        var best: Int? = null
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (stack.isEmpty || isToolProtected(stack)) continue
            if (isEligiblePickaxe(stack) ||
                getBlockBreakingSpeed(Blocks.PISTON.defaultBlockState(), stack) > INSTANT_MINE_THRESHOLD
            ) {
                val remaining = remainingDurability(stack)
                if (remaining != Int.MAX_VALUE && (best == null || remaining > (best ?: 0))) {
                    best = remaining
                }
            }
        }
        return best
    }

    private fun getEfficiencyLevel(stack: ItemStack): Int {
        if (stack.isEmpty) return 0
        //? if >=1.21 {
        val world = Minecraft.getInstance().level ?: return 0
        val efficiency = world.registryAccess().lookup(Registries.ENCHANTMENT).get().getOrThrow(
            Enchantments.EFFICIENCY
        )
        return EnchantmentHelper.getItemEnchantmentLevel(efficiency, stack)
        //?} else if >=1.20.5 {
        //return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.EFFICIENCY, stack)
        //?} else
        //return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, stack)
    }

    /**
     * A pickaxe the automation may use as a replacement: diamond or netherite
     * with Efficiency V — names and other enchants don't matter.
     */
    fun isEligiblePickaxe(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (stack.item != Items.DIAMOND_PICKAXE && stack.item != Items.NETHERITE_PICKAXE) return false
        return getEfficiencyLevel(stack) >= 5
    }

    private fun getBlockBreakingSpeed(block: BlockState, stack: ItemStack): Float {
        val player = Minecraft.getInstance().player ?: return 0f

        var speed = stack.getDestroySpeed(block)
        if (speed > 1.0f) {
            val level = getEfficiencyLevel(stack)
            if (level > 0 && !stack.isEmpty) {
                speed += (level * level + 1).toFloat()
            }
        }

        if (MobEffectUtil.hasDigSpeed(player)) {
            speed *= 1.0f + (getDigSpeedAmplification() + 1) * 0.2f
        }

        //? if >=1.21.11 {
        val miningFatigue = MobEffects.MINING_FATIGUE
        //?} else
        //val miningFatigue = MobEffects.DIG_SLOWDOWN
        if (player.hasEffect(miningFatigue)) {
            val amplifier = player.getEffect(miningFatigue)?.amplifier ?: 0
            speed *= when (amplifier) {
                0 -> 0.3f
                1 -> 0.09f
                2 -> 0.0027f
                else -> 8.1E-4f
            }
        }

        //? if >=1.21 {
        speed *= player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED).toFloat()

        if (player.isEyeInFluid(FluidTags.WATER)) {
            speed *= player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED).toFloat()
        }
        //?} else {
        /*if (player.isEyeInFluid(FluidTags.WATER) && !EnchantmentHelper.hasAquaAffinity(player)) {
            speed /= 5.0f
        }
        *///?}

        if (!MinecraftClientCompat.isOnGround(player)) {
            speed /= 5.0f
        }

        return speed
    }

    private fun getDigSpeedAmplification(): Int {
        val player = Minecraft.getInstance().player ?: return 0
        //? if >=1.21 {
        return MobEffectUtil.getDigSpeedAmplification(player)
        //?} else {
        /*val haste = player.getEffect(MobEffects.DIG_SPEED)?.amplifier?.let(::unsignedByteAmplifier) ?: 0
        val conduit = player.getEffect(MobEffects.CONDUIT_POWER)?.amplifier?.let(::unsignedByteAmplifier) ?: 0
        return maxOf(haste, conduit)
        *///?}
    }

    private fun unsignedByteAmplifier(amplifier: Int): Int {
        return if (amplifier < 0) amplifier + 256 else amplifier
    }

    fun countItem(item: Item): Int {
        val player = Minecraft.getInstance().player ?: return 0
        return player.inventory.countItem(item)
    }

    /** Any healthy (unprotected) usable pickaxe left at all? When this goes
     *  false the automation must STOP — never continue with broken tools. */
    fun hasUsablePickaxe(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        val inventory = player.inventory
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (stack.isEmpty || isToolProtected(stack)) continue
            if (isEligiblePickaxe(stack) ||
                getBlockBreakingSpeed(Blocks.PISTON.defaultBlockState(), stack) > INSTANT_MINE_THRESHOLD
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Non-suspending slot select (used by auto-eat): hotbar slots directly,
     * main-inventory slots via a swap click. Verification is the caller's
     * next tick.
     */
    fun selectItemNow(slot: Int): Boolean {
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        val inventory = player.inventory
        if (Inventory.isHotbarSlot(slot)) {
            MinecraftClientCompat.setSelectedSlot(inventory, slot)
        } else {
            if (player.containerMenu !== player.inventoryMenu) return false
            pickFromInventory(slot)
        }
        client.connection?.send(ServerboundSetCarriedItemPacket(MinecraftClientCompat.selectedSlot(inventory)))
        return true
    }

    /** True only when consumable supplies are ACTUALLY missing — never for
     *  transient tooling problems like the haste beacon being out of range. */
    fun suppliesMissing(): Boolean {
        return countItem(Blocks.PISTON.asItem()) < 2 ||
            countItem(Blocks.REDSTONE_TORCH.asItem()) < 1 ||
            countItem(Configs.Generic.supportBlock.asItem()) < 1
    }

    fun checkRequiredItems(): String? {
        val client = Minecraft.getInstance()
        val gameMode = client.gameMode ?: return StringUtils.translate("bedrockminer.message.not_in_game")

        if (!gameMode.playerMode.isSurvival) {
            return StringUtils.translate("bedrockminer.message.survival_only")
        }
        if (countItem(Blocks.PISTON.asItem()) < 2) {
            return StringUtils.translate("bedrockminer.message.need_pistons")
        }
        if (countItem(Blocks.REDSTONE_TORCH.asItem()) < 1) {
            return StringUtils.translate("bedrockminer.message.need_torches")
        }
        val supportBlock = Configs.Generic.supportBlock
        if (countItem(supportBlock.asItem()) < 1) {
            val supportBlockName = supportBlock.name.string
            return StringUtils.translate("bedrockminer.message.need_support", supportBlockName)
        }
        if (!Configs.Generic.SKIP_INSTANT_MINE_CHECK.booleanValue && !canInstantlyMinePiston()) {
            return if (toolsWorn()) {
                StringUtils.translate(
                    "bedrockminer.message.tools_worn",
                    Configs.Generic.TOOL_PROTECT_THRESHOLD.integerValue,
                )
            } else {
                StringUtils.translate("bedrockminer.message.need_efficiency")
            }
        }
        return null
    }
}
