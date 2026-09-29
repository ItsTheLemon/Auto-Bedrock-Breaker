package com.github.lxyan2333.bedrockminer.client.render

import com.github.lxyan2333.bedrockminer.client.area.AreaRestriction
import com.github.lxyan2333.bedrockminer.client.automate.AutoPilot
import com.github.lxyan2333.bedrockminer.client.automate.PlayerMover
import com.github.lxyan2333.bedrockminer.client.automine.AutoMiner
import com.github.lxyan2333.bedrockminer.client.breaking.BreakingFlowController
import com.github.lxyan2333.bedrockminer.client.compat.MinecraftClientCompat
import com.github.lxyan2333.bedrockminer.client.config.Configs
//? if >=1.21.11 {
import com.mojang.blaze3d.vertex.BufferBuilder
import fi.dy.masa.malilib.util.data.Color4f
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.Vec3
//?}
//? if >=26.2 {
import com.github.lxyan2333.bedrockminer.client.breaking.InventoryManager
import fi.dy.masa.malilib.render.GuiContext
import fi.dy.masa.malilib.util.StringUtils
//?}
//? if >=26.3 {
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderTarget
//?} else if >=26.1 {
/*import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderTarget
*///?}
//? if <1.21 {
/*import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
//? if >=1.17
import com.mojang.blaze3d.vertex.VertexFormat
*///?}
//? if >=1.21.11 {
import fi.dy.masa.malilib.MaLiLib
import fi.dy.masa.malilib.render.MaLiLibPipelines
import fi.dy.masa.malilib.render.RenderContext
//?}
import fi.dy.masa.malilib.config.options.ConfigColor
import fi.dy.masa.malilib.interfaces.IRenderer
import fi.dy.masa.malilib.render.RenderUtils
import net.minecraft.client.Minecraft
//? if <1.21
//import net.minecraft.client.renderer.GameRenderer
//? if >=26.1 {
import net.minecraft.client.renderer.RenderBuffers
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.util.profiling.ProfilerFiller
//?}
import net.minecraft.core.BlockPos
//? if >=26.1 {
import org.joml.Matrix4fc
import org.joml.Vector4f
//?} else if >=1.19.4 {
/*import org.joml.Matrix4f*///?}
//? if <1.19.4
//import com.mojang.math.Matrix4f


object AreaRenderer : IRenderer {
    //? if >=26.3 {
    override fun onRenderWorldLast(
        fb: RenderTarget,
        cameraState: CameraRenderState,
        culling: Frustum,
        buffers: RenderBuffers,
        terrainFog: GpuBufferSlice,
        fogColor: Vector4f,
        profiler: ProfilerFiller
    ) {
        profiler.push("bedrock_miner_area_restriction")
        renderAreas()
        profiler.pop()
    }
    //?} else if >=26.1 {
    /*override fun onRenderWorldLast(
        fb: RenderTarget,
        modelViewMatrix: Matrix4fc,
        cameraState: CameraRenderState,
        culling: Frustum,
        buffers: RenderBuffers,
        terrainFog: GpuBufferSlice,
        fogColor: Vector4f,
        profiler: ProfilerFiller
    ) {
        profiler.push("bedrock_miner_area_restriction")
        renderAreas()
        profiler.pop()
    }
    *///?} else if >=1.20.5 {
    /*override fun onRenderWorldLast(
        posMatrix: Matrix4f,
        projMatrix: Matrix4f
    ) {
        renderAreas()
    }
    *///?} else if >=1.17 {
    /*override fun onRenderWorldLast(
        matrixStack: PoseStack,
        projMatrix: Matrix4f
    ) {
        renderAreas()
    }
    *///?} else {
    /*override fun onRenderWorldLast(
        partialTicks: Float,
        matrixStack: PoseStack
    ) {
        renderAreas()
    }
    *///?}

    private const val MAX_RENDER_DISTANCE_SQR = 64.0 * 64.0

    /** Pull box surfaces off the block grid so they never z-fight with terrain. */
    private const val BOX_RENDER_INSET = 0.03f

    private fun renderAreas() {
        val player = Minecraft.getInstance().player ?: return
        val eyePos = MinecraftClientCompat.eyePosition(player)

        if (Configs.Area.AREA_RESTRICTION_ENABLED.booleanValue) {
            for (area in AreaRestriction.configuredAreas()) {
                if (area.distanceToSqr(eyePos) > MAX_RENDER_DISTANCE_SQR) continue
                renderAreaOutline(area.pos1, area.pos2, Configs.Area.AREA_BOX_COLOR)
            }
        }

        //? if >=1.21.11 {
        if (AutoPilot.active) {
            renderAutomationOverlay()
        }
        //?}

        if (AutoMiner.shouldRenderBox()) {
            val area = AutoMiner.previewArea() ?: return
            if (area.distanceToSqr(eyePos) > MAX_RENDER_DISTANCE_SQR) return
            //? if >=1.21.11
            renderAreaFill(area.pos1, area.pos2, Configs.AutoMine.BOX_FILL_COLOR)
            renderAreaOutline(area.pos1, area.pos2, Configs.AutoMine.BOX_COLOR)
        }
    }

    //? if >=1.21.11 {
    /** Translucent shade on all six faces of the box, so the volume is obvious. */
    private fun renderAreaFill(pos1: BlockPos, pos2: BlockPos, colorConfig: ConfigColor) {
        val color = colorConfig.color
        if (color.a <= 0f) return

        val cameraPos = RenderUtils.camPos()
        val inset = BOX_RENDER_INSET
        val minX = (minOf(pos1.x, pos2.x) - cameraPos.x).toFloat() + inset
        val minY = (minOf(pos1.y, pos2.y) - cameraPos.y).toFloat() + inset
        val minZ = (minOf(pos1.z, pos2.z) - cameraPos.z).toFloat() + inset
        val maxX = (maxOf(pos1.x, pos2.x) + 1 - cameraPos.x).toFloat() - inset
        val maxY = (maxOf(pos1.y, pos2.y) + 1 - cameraPos.y).toFloat() - inset
        val maxZ = (maxOf(pos1.z, pos2.z) + 1 - cameraPos.z).toFloat() - inset
        // Always depth-tested: only the visible surface of the box is shaded,
        // faces buried in terrain (or behind it) stay hidden — no murky
        // "filled inside" look.
        val pipeline = MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_LEQUAL_DEPTH_NO_CULL

        //? if >=26.2 {
        val ctx = RenderContext({ "bedrock-miner:automine_box_fill" }, pipeline, 0)
        //?} else {
        /*val ctx = RenderContext({ "bedrock-miner:automine_box_fill" }, pipeline)
        *///?}
        try {
            val buffer = ctx.builder
            RenderUtils.drawBoxAllSidesBatchedQuads(minX, minY, minZ, maxX, maxY, maxZ, color, buffer)

            val meshData = buffer.build()
            if (meshData != null) {
                ctx.draw(meshData, false, true)
                meshData.close()
            }
        } catch (err: Exception) {
            MaLiLib.LOGGER.error("AreaRenderer.renderAreaFill(): Draw exception; {}", err.message)
        } finally {
            ctx.close()
        }
    }
    //?}

    private fun renderAreaOutline(pos1: BlockPos, pos2: BlockPos, colorConfig: ConfigColor) {
        //? if >=1.21.11 {
        val cameraPos = RenderUtils.camPos()
        // Inset slightly so the planes never sit exactly on block faces —
        // coplanar surfaces z-fight and flicker while moving.
        val inset = BOX_RENDER_INSET
        val minX = (minOf(pos1.x, pos2.x) - cameraPos.x).toFloat() + inset
        val minY = (minOf(pos1.y, pos2.y) - cameraPos.y).toFloat() + inset
        val minZ = (minOf(pos1.z, pos2.z) - cameraPos.z).toFloat() + inset
        val maxX = (maxOf(pos1.x, pos2.x) + 1 - cameraPos.x).toFloat() - inset
        val maxY = (maxOf(pos1.y, pos2.y) + 1 - cameraPos.y).toFloat() - inset
        val maxZ = (maxOf(pos1.z, pos2.z) + 1 - cameraPos.z).toFloat() - inset
        val color = colorConfig.color
        val pipeline = if (Configs.Area.HIDE_AREA_BOX_BEHIND_BLOCKS.booleanValue) {
            MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH
        } else {
            MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_CULL
        }

        //? if >=26.2 {
        val ctx = RenderContext({ "bedrock-miner:area_restriction_outline" }, pipeline, 0)
        //?} else {
        /*val ctx = RenderContext({ "bedrock-miner:area_restriction_outline" }, pipeline)
        *///?}
        try {
            val buffer = ctx.builder
            RenderUtils.drawBoxAllEdgesBatchedLines(
                minX, minY, minZ, maxX, maxY, maxZ, color, Configs.Area.AREA_BOX_LINE_WIDTH.floatValue, buffer
            )

            val meshData = buffer.build()
            if (meshData != null) {
                ctx.draw(meshData, false, true)
                meshData.close()
            }
        } catch (err: Exception) {
            MaLiLib.LOGGER.error("AreaRenderer.renderAreaOutline(): Draw exception; {}", err.message)
        } finally {
            ctx.close()
        }
        //?} else if >=1.21 {
        /*val color = colorConfig.color
        RenderUtils.renderAreaOutline(
            pos1,
            pos2,
            Configs.Area.areaBoxLineWidth,
            color,
            color,
            color,
            Minecraft.getInstance()
        )
        *///?} else {
        /*renderAreaOutlineLegacy(pos1, pos2, colorConfig)
        *///?}
    }

    //? if >=1.21.11 {
    private val COLOR_BREAKING = Color4f(1.0f, 0.25f, 0.2f, 1.0f)
    private val COLOR_PATH_TARGET = Color4f(1.0f, 0.85f, 0.1f, 1.0f)
    private val COLOR_CLEARING = Color4f(1.0f, 0.55f, 0.1f, 1.0f)
    private val COLOR_ITEM = Color4f(0.3f, 1.0f, 0.45f, 1.0f)
    private val COLOR_WALK_RELOCATE = Color4f(0.2f, 0.85f, 1.0f, 0.9f)
    private val COLOR_WALK_COLLECT = Color4f(0.3f, 1.0f, 0.45f, 0.9f)
    private val COLOR_STAND = Color4f(0.55f, 0.75f, 1.0f, 1.0f)
    private val COLOR_QUEUE = Color4f(1.0f, 1.0f, 1.0f, 0.5f)
    private val COLOR_STEP_UP = Color4f(1.0f, 0.8f, 0.25f, 1.0f)
    private val COLOR_STEP_DOWN = Color4f(0.45f, 0.65f, 1.0f, 1.0f)
    private val COLOR_PISTON = Color4f(1.0f, 0.62f, 0.3f, 1.0f)
    private val COLOR_TORCH = Color4f(1.0f, 0.35f, 0.35f, 1.0f)
    private val COLOR_SUPPORT = Color4f(0.5f, 1.0f, 0.55f, 1.0f)
    private val COLOR_ITEM_FIELD = Color4f(0.3f, 1.0f, 0.45f, 0.4f)
    private val COLOR_ITEM_RISK = Color4f(1.0f, 0.45f, 0.15f, 1.0f)
    private val COLOR_RANGE_RING = Color4f(0.4f, 0.9f, 1.0f, 0.35f)

    /**
     * Live view of what the automation is doing: red outlines on blocks being
     * broken, a yellow outline on the block being walked to, orange on path
     * blocks being cleared, green on the item being collected, and a line
     * from the player to the current walking goal.
     */
    private fun renderAutomationOverlay() {
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val player = client.player ?: return
        val cameraPos = RenderUtils.camPos()
        val width = Configs.AutoMine.overlayLineWidth
        val thinWidth = maxOf(2.5f, width * 0.7f)

        val flowTargets = BreakingFlowController.activeFlows.toList().map { it.targetPos }
        val relocateBlock = AutoPilot.currentRelocateBlock
        val sidestepBlock = AutoPilot.currentSidestepBlock
        val clearBlocks = AutoPilot.currentClearBlocks
        val itemId = AutoPilot.currentItemTargetId
        val item = if (itemId != -1) level.getEntity(itemId) as? ItemEntity else null
        // The next few queued blocks, minus the ones already highlighted.
        val highlighted = HashSet<BlockPos>(flowTargets)
        relocateBlock?.let { highlighted.add(it) }
        val queuedBlocks = AutoMiner.pendingPreview(6).filter { it !in highlighted }

        // Pulsing alpha makes the "being broken" blocks read as active.
        val pulse = 0.18f + 0.14f *
            kotlin.math.sin((System.currentTimeMillis() % 1200L).toFloat() / 1200f * (Math.PI * 2.0).toFloat())

        // Pass 1: translucent face shading on all highlighted blocks.
        //? if >=26.2 {
        val fillCtx = RenderContext(
            { "bedrock-miner:automation_overlay_fill" },
            MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL,
            0,
        )
        //?} else {
        /*val fillCtx = RenderContext(
            { "bedrock-miner:automation_overlay_fill" },
            MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL,
        )
        *///?}
        try {
            val buffer = fillCtx.builder
            for (pos in flowTargets) {
                blockFill(buffer, pos, cameraPos, COLOR_BREAKING, pulse)
            }
            relocateBlock?.let { blockFill(buffer, it, cameraPos, COLOR_PATH_TARGET, 0.20f) }
            sidestepBlock?.let { blockFill(buffer, it, cameraPos, COLOR_STAND, 0.18f) }
            for (pos in clearBlocks) {
                blockFill(buffer, pos, cameraPos, COLOR_CLEARING, 0.20f)
            }
            if (item != null) {
                val p = item.position()
                RenderUtils.drawBoxAllSidesBatchedQuads(
                    (p.x - 0.25 - cameraPos.x).toFloat(), (p.y - cameraPos.y).toFloat(),
                    (p.z - 0.25 - cameraPos.z).toFloat(),
                    (p.x + 0.25 - cameraPos.x).toFloat(), (p.y + 0.5 - cameraPos.y).toFloat(),
                    (p.z + 0.25 - cameraPos.z).toFloat(),
                    COLOR_ITEM.withAlpha(0.25f), buffer,
                )
            }
            val meshData = buffer.build()
            if (meshData != null) {
                fillCtx.draw(meshData, false, true)
                meshData.close()
            }
        } catch (err: Exception) {
            MaLiLib.LOGGER.error("AreaRenderer.renderAutomationOverlay(): Fill exception; {}", err.message)
        } finally {
            fillCtx.close()
        }

        // Pass 2: outlines and the walking line.
        //? if >=26.2 {
        val ctx = RenderContext(
            { "bedrock-miner:automation_overlay" },
            MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_CULL,
            0,
        )
        //?} else {
        /*val ctx = RenderContext(
            { "bedrock-miner:automation_overlay" },
            MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_CULL,
        )
        *///?}
        try {
            val buffer = ctx.builder

            val bodyPos = player.position().add(0.0, 0.9, 0.0)
            for (flow in BreakingFlowController.activeFlows.toList()) {
                blockOutline(buffer, flow.targetPos, cameraPos, COLOR_BREAKING, width)
                // Work line from the player to each block being broken.
                line(
                    buffer, bodyPos,
                    Vec3(flow.targetPos.x + 0.5, flow.targetPos.y + 0.5, flow.targetPos.z + 0.5),
                    cameraPos, COLOR_BREAKING.withAlpha(0.65f), thinWidth,
                )
                // The live piston contraption of this flow: piston (orange),
                // redstone torch (red), support block (green).
                flow.currentApproach?.let { approach ->
                    blockOutline(buffer, approach.pistonPos, cameraPos, COLOR_PISTON, thinWidth)
                    blockOutline(buffer, approach.torchPos, cameraPos, COLOR_TORCH, thinWidth)
                    approach.supportBlockPos?.let { blockOutline(buffer, it, cameraPos, COLOR_SUPPORT, thinWidth) }
                }
            }

            // All wanted drops nearby, faint green; ones near their despawn
            // clock pulse orange so you see what the bot is prioritizing.
            val itemPulse = 0.5f + 0.5f *
                kotlin.math.sin((System.currentTimeMillis() % 700L).toDouble() / 700.0 * Math.PI * 2.0).toFloat()
            val fieldItems = level.getEntitiesOfClass(
                ItemEntity::class.java,
                player.boundingBox.inflate(12.0),
            ) { it.isAlive && AutoPilot.isWantedItem(it.item.item) }
            for (entity in fieldItems.take(24)) {
                val p = entity.position()
                val atRisk = AutoPilot.isItemAtRisk(entity.id)
                val itemColor = if (atRisk) COLOR_ITEM_RISK.withAlpha(0.5f + 0.5f * itemPulse) else COLOR_ITEM_FIELD
                RenderUtils.drawBoxAllEdgesBatchedLines(
                    (p.x - 0.15 - cameraPos.x).toFloat(), (p.y - cameraPos.y).toFloat(),
                    (p.z - 0.15 - cameraPos.z).toFloat(),
                    (p.x + 0.15 - cameraPos.x).toFloat(), (p.y + 0.3 - cameraPos.y).toFloat(),
                    (p.z + 0.15 - cameraPos.z).toFloat(),
                    itemColor, thinWidth, buffer,
                )
            }

            // Radar display around the player: double hue-cycling ring,
            // radial tick marks, and a rotating sweep with fading trails.
            run {
                val radius = Configs.AutoMine.maxRange
                val feet = player.position()
                val hue = (System.currentTimeMillis() % 6000L).toFloat() / 6000f
                val ringColor = hueColor(hue, 0.55f)
                val innerColor = hueColor((hue + 0.15f) % 1f, 0.35f)
                var previous: Vec3? = null
                var previousInner: Vec3? = null
                for (i in 0..40) {
                    val angle = i.toDouble() / 40.0 * Math.PI * 2.0
                    val cos = kotlin.math.cos(angle)
                    val sin = kotlin.math.sin(angle)
                    val point = Vec3(feet.x + radius * cos, feet.y + 0.05, feet.z + radius * sin)
                    val inner = Vec3(feet.x + radius * 0.55 * cos, feet.y + 0.05, feet.z + radius * 0.55 * sin)
                    previous?.let { line(buffer, it, point, cameraPos, ringColor, width) }
                    previousInner?.let { line(buffer, it, inner, cameraPos, innerColor, thinWidth) }
                    previous = point
                    previousInner = inner
                }
                // Radial tick marks.
                for (i in 0 until 16) {
                    val angle = i.toDouble() / 16.0 * Math.PI * 2.0
                    val cos = kotlin.math.cos(angle)
                    val sin = kotlin.math.sin(angle)
                    line(
                        buffer,
                        Vec3(feet.x + radius * 0.88 * cos, feet.y + 0.05, feet.z + radius * 0.88 * sin),
                        Vec3(feet.x + radius * cos, feet.y + 0.05, feet.z + radius * sin),
                        cameraPos, ringColor.withAlpha(0.8f), thinWidth,
                    )
                }
                // Rotating sweep with two fading trails.
                val sweepBase = (System.currentTimeMillis() % 2400L).toDouble() / 2400.0 * Math.PI * 2.0
                for (trail in 0..2) {
                    val angle = sweepBase - trail * 0.21
                    val alpha = 0.85f - trail * 0.3f
                    line(
                        buffer,
                        Vec3(feet.x, feet.y + 0.05, feet.z),
                        Vec3(
                            feet.x + radius * kotlin.math.cos(angle),
                            feet.y + 0.05,
                            feet.z + radius * kotlin.math.sin(angle),
                        ),
                        cameraPos, hueColor(hue, alpha), if (trail == 0) width else thinWidth,
                    )
                }
            }
        } catch (err: Exception) {
            MaLiLib.LOGGER.error("AreaRenderer.renderAutomationOverlay(): Draw exception; {}", err.message)
        } finally {
            ctx.close()
        }
    }

    private fun blockFill(buffer: BufferBuilder, pos: BlockPos, cameraPos: Vec3, color: Color4f, alpha: Float) {
        RenderUtils.drawBoxAllSidesBatchedQuads(
            (pos.x - 0.002 - cameraPos.x).toFloat(), (pos.y - 0.002 - cameraPos.y).toFloat(),
            (pos.z - 0.002 - cameraPos.z).toFloat(),
            (pos.x + 1.002 - cameraPos.x).toFloat(), (pos.y + 1.002 - cameraPos.y).toFloat(),
            (pos.z + 1.002 - cameraPos.z).toFloat(),
            color.withAlpha(alpha), buffer,
        )
    }

    private fun blockOutline(buffer: BufferBuilder, pos: BlockPos, cameraPos: Vec3, color: Color4f, width: Float) {
        RenderUtils.drawBoxAllEdgesBatchedLines(
            (pos.x - 0.005 - cameraPos.x).toFloat(), (pos.y - 0.005 - cameraPos.y).toFloat(),
            (pos.z - 0.005 - cameraPos.z).toFloat(),
            (pos.x + 1.005 - cameraPos.x).toFloat(), (pos.y + 1.005 - cameraPos.y).toFloat(),
            (pos.z + 1.005 - cameraPos.z).toFloat(),
            color, width, buffer,
        )
    }

    /** Simple hue wheel (t in 0..1) for the animated ring. */
    private fun hueColor(t: Float, alpha: Float): Color4f {
        val h = t * 6f
        val x = 1f - kotlin.math.abs(h % 2f - 1f)
        return when (h.toInt() % 6) {
            0 -> Color4f(1f, x, 0f, alpha)
            1 -> Color4f(x, 1f, 0f, alpha)
            2 -> Color4f(0f, 1f, x, alpha)
            3 -> Color4f(0f, x, 1f, alpha)
            4 -> Color4f(x, 0f, 1f, alpha)
            else -> Color4f(1f, 0f, x, alpha)
        }
    }

    private fun line(buffer: BufferBuilder, from: Vec3, to: Vec3, cameraPos: Vec3, color: Color4f, width: Float) {
        buffer.addVertex(
            (from.x - cameraPos.x).toFloat(), (from.y - cameraPos.y).toFloat(), (from.z - cameraPos.z).toFloat(),
        ).setColor(color.r, color.g, color.b, color.a).setLineWidth(width)
        buffer.addVertex(
            (to.x - cameraPos.x).toFloat(), (to.y - cameraPos.y).toFloat(), (to.z - cameraPos.z).toFloat(),
        ).setColor(color.r, color.g, color.b, color.a).setLineWidth(width)
    }
    //?}

    //? if >=26.2 {
    override fun onExtractGuiOverlayPost(
        ctx: GuiContext,
        partialTicks: Float,
        profiler: ProfilerFiller,
    ) {
        if (!AutoPilot.active) return
        try {
            renderHud(ctx)
        } catch (err: Exception) {
            MaLiLib.LOGGER.error("AreaRenderer.renderHud(): {}", err.message)
        }
    }

    /** Bedrock block as 7x7 pixel art (grey-shade indices into ICON_PALETTE). */
    private val BEDROCK_ICON = intArrayOf(
        1, 1, 3, 3, 1, 2, 1,
        1, 3, 3, 1, 2, 2, 1,
        2, 3, 1, 1, 2, 1, 0,
        2, 2, 1, 3, 1, 0, 0,
        1, 1, 3, 3, 1, 0, 2,
        1, 3, 3, 1, 1, 2, 2,
        1, 1, 1, 2, 2, 2, 1,
    )
    private val ICON_PALETTE = intArrayOf(
        0xFF6E6E72.toInt(), 0xFF54545A.toInt(), 0xFF3B3B41.toInt(), 0xFF88888C.toInt(),
    )

    /** Status console: icon + title, phase, progress/ETA/speed/time, stock, log. */
    private fun renderHud(ctx: GuiContext) {
        val font = ctx.fontRenderer()

        val phaseText = when (AutoPilot.hudPhase) {
            AutoPilot.Phase.MINING ->
                StringUtils.translate("bedrockminer.hud.autopilot.mining", BreakingFlowController.activeFlows.size)
            AutoPilot.Phase.COLLECTING -> StringUtils.translate("bedrockminer.hud.autopilot.collecting")
            AutoPilot.Phase.CLEARING -> StringUtils.translate("bedrockminer.hud.autopilot.clearing")
            AutoPilot.Phase.RELOCATING -> StringUtils.translate("bedrockminer.hud.autopilot.relocating")
            AutoPilot.Phase.EATING -> StringUtils.translate("bedrockminer.hud.autopilot.eating")
            AutoPilot.Phase.PAUSING -> StringUtils.translate("bedrockminer.hud.autopilot.mining", 0)
        }
        val phaseColor = when (AutoPilot.hudPhase) {
            AutoPilot.Phase.MINING -> 0xFF60FF60.toInt()
            AutoPilot.Phase.COLLECTING -> 0xFF40E0A0.toInt()
            AutoPilot.Phase.CLEARING -> 0xFFFFA040.toInt()
            AutoPilot.Phase.RELOCATING -> 0xFF40C8FF.toInt()
            AutoPilot.Phase.EATING -> 0xFFFF8899.toInt()
            AutoPilot.Phase.PAUSING -> 0xFFB0B0B0.toInt()
        }

        val total = AutoPilot.hudInitialTargets
        val remaining = AutoPilot.hudRemainingTargets
        val broken = (total - remaining).coerceAtLeast(0)
        val percent = if (total > 0) broken * 100 / total else 0
        val durability = InventoryManager.activePickaxeRemainingDurability()

        val elapsed = AutoPilot.hudElapsedTicks
        val elapsedSeconds = elapsed / 20L
        val timeText = String.format("%d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
        val speed = if (elapsed > 100) broken * 1200L / elapsed else 0L
        val etaText = if (broken > 0 && remaining > 0) {
            val etaSeconds = remaining.toLong() * elapsed / broken / 20L
            String.format("%d:%02d", etaSeconds / 60, etaSeconds % 60)
        } else {
            "-:--"
        }

        // (text, color, sectionBreakBefore)
        val lines = ArrayList<Triple<String, Int, Boolean>>()
        lines.add(Triple(phaseText, phaseColor, false))
        lines.add(Triple(StringUtils.translate("bedrockminer.hud.panel.progress", broken, total, percent), 0xFFF0F0F0.toInt(), true))
        lines.add(Triple(StringUtils.translate("bedrockminer.hud.panel.eta", etaText), 0xFFB8C8D8.toInt(), false))
        lines.add(Triple(StringUtils.translate("bedrockminer.hud.panel.speed", speed), 0xFFB8C8D8.toInt(), false))
        lines.add(Triple(StringUtils.translate("bedrockminer.hud.panel.time", timeText), 0xFFB8C8D8.toInt(), false))
        lines.add(Triple(StringUtils.translate("bedrockminer.hud.panel.items", AutoPilot.hudItemsCollected), 0xFFE8E8E8.toInt(), true))
        lines.add(Triple(
            StringUtils.translate("bedrockminer.hud.panel.nearby", AutoPilot.hudNearbyItems, AutoPilot.hudRiskItems),
            if (AutoPilot.hudRiskItems > 0) 0xFFFFA040.toInt() else 0xFFB8C8D8.toInt(), false,
        ))
        lines.add(Triple(StringUtils.translate("bedrockminer.hud.panel.queue", AutoMiner.pendingCount()), 0xFFB8C8D8.toInt(), false))
        if (durability != null) {
            val threshold = Configs.Generic.TOOL_PROTECT_THRESHOLD.integerValue
            val color = when {
                durability <= threshold -> 0xFFFF6060.toInt()
                durability <= threshold * 2 -> 0xFFFFD040.toInt()
                else -> 0xFF60FF60.toInt()
            }
            lines.add(Triple(StringUtils.translate("bedrockminer.hud.panel.pick", durability), color, false))
        } else {
            lines.add(Triple(StringUtils.translate("bedrockminer.hud.panel.pick_none"), 0xFFFF6060.toInt(), false))
        }
        val events = AutoPilot.hudRecentEvents
        for ((index, eventText) in events.withIndex()) {
            val shade = when (index) {
                0 -> 0xFFC0C0C0.toInt()
                1 -> 0xFF8A8A8A.toInt()
                else -> 0xFF636363.toInt()
            }
            lines.add(Triple(eventText, shade, index == 0))
        }

        val x = 8
        val lineStep = font.lineHeight + 3
        val sectionGap = 5
        val barWidth = 134
        val barHeight = 6
        val titleText = StringUtils.translate("bedrockminer.hud.panel.title")
        val sections = lines.count { it.third }
        var panelWidth = maxOf(font.width(titleText) + 20, barWidth)
        for (entry in lines) panelWidth = maxOf(panelWidth, font.width(entry.first))
        panelWidth += 4
        val titleHeight = 16
        val panelHeight = titleHeight + lines.size * lineStep + sections * sectionGap + barHeight + 8

        // Frame: phase-colored ring, dark body, accent bar, title strip.
        val borderColor = (phaseColor and 0x00FFFFFF) or (0xA0 shl 24)
        RenderUtils.drawRect(ctx, x - 5, 3, panelWidth + 12, panelHeight + 10, borderColor)
        RenderUtils.drawRect(ctx, x - 4, 4, panelWidth + 10, panelHeight + 8, 0xEE101016.toInt())
        RenderUtils.drawRect(ctx, x - 4, 4, 2, panelHeight + 8, phaseColor)
        RenderUtils.drawRect(ctx, x - 2, 4, panelWidth + 8, titleHeight + 2, 0xFF1B1B24.toInt())

        // Pixel-art bedrock icon + title.
        for (row in 0 until 7) {
            for (col in 0 until 7) {
                val shade = ICON_PALETTE[BEDROCK_ICON[row * 7 + col]]
                RenderUtils.drawRect(ctx, x + col * 2, 6 + row * 2, 2, 2, shade)
            }
        }
        ctx.drawString(font, titleText, x + 18, 9, 0xFFFFB020.toInt())

        var y = 6 + titleHeight
        for ((text, color, sectionBreak) in lines) {
            if (sectionBreak) {
                y += 2
                RenderUtils.drawRect(ctx, x - 2, y, panelWidth + 6, 1, 0xFF2E2E3A.toInt())
                y += sectionGap - 2
            }
            ctx.drawString(font, text, x, y, color)
            y += lineStep
        }

        // Progress bar with border, fill and percent-synced phase color cap.
        RenderUtils.drawRect(ctx, x - 1, y, barWidth + 2, barHeight + 2, 0xFF3A3A46.toInt())
        RenderUtils.drawRect(ctx, x, y + 1, barWidth, barHeight, 0xFF20202A.toInt())
        if (total > 0) {
            val filled = (barWidth * broken / total).coerceIn(0, barWidth)
            if (filled > 0) {
                RenderUtils.drawRect(ctx, x, y + 1, filled, barHeight, 0xFF50E070.toInt())
                RenderUtils.drawRect(ctx, x + filled - 1, y + 1, 1, barHeight, 0xFFB0FFC0.toInt())
            }
        }
    }
    //?}

    //? if <1.21 {
    /*private fun renderAreaOutlineLegacy(pos1: BlockPos, pos2: BlockPos, colorConfig: ConfigColor) {
        val mc = Minecraft.getInstance()
        val cameraPos = mc.gameRenderer.mainCamera.position
        val minX = minOf(pos1.x, pos2.x) - cameraPos.x
        val minY = minOf(pos1.y, pos2.y) - cameraPos.y
        val minZ = minOf(pos1.z, pos2.z) - cameraPos.z
        val maxX = maxOf(pos1.x, pos2.x) + 1 - cameraPos.x
        val maxY = maxOf(pos1.y, pos2.y) + 1 - cameraPos.y
        val maxZ = maxOf(pos1.z, pos2.z) + 1 - cameraPos.z
        val color = colorConfig.color
        val depthEnabled = Configs.Area.HIDE_AREA_BOX_BEHIND_BLOCKS.booleanValue

        RenderSystem.lineWidth(Configs.Area.areaBoxLineWidth)
        //? if >=1.17
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        if (!depthEnabled) {
            RenderSystem.disableDepthTest()
        }

        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.builder
        //? if >=1.17 {
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)
        //?} else
        //buffer.begin(1, DefaultVertexFormat.POSITION_COLOR)
        RenderUtils.drawBoxAllEdgesBatchedLines(minX, minY, minZ, maxX, maxY, maxZ, color, buffer)
        tessellator.end()

        if (!depthEnabled) {
            RenderSystem.enableDepthTest()
        }
    }
    *///?}
}
