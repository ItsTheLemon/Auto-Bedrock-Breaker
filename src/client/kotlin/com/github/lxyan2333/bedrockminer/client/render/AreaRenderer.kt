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
     * Live view of what the automation is doing. Everything is drawn as
     * BEAMS (crossed translucent quads with real world-space thickness), not
     * GL lines, because line width attributes are capped to a hair's width
     * on most systems. Thickness follows the overlay width config slider.
     */
    private fun renderAutomationOverlay() {
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val player = client.player ?: return
        val cameraPos = RenderUtils.camPos()
        // Slider 1..10 maps to 0.004..0.04 blocks: thin even at maximum.
        val thick = Configs.AutoMine.overlayLineWidth * 0.004f
        val thin = thick * 0.55f

        val flowTargets = BreakingFlowController.activeFlows.toList().map { it.targetPos }
        val approaches = BreakingFlowController.activeFlows.toList().mapNotNull { it.currentApproach }
        val relocateBlock = AutoPilot.currentRelocateBlock
        val sidestepBlock = AutoPilot.currentSidestepBlock
        val clearBlocks = AutoPilot.currentClearBlocks
        val itemId = AutoPilot.currentItemTargetId
        val item = if (itemId != -1) level.getEntity(itemId) as? ItemEntity else null
        val highlighted = HashSet<BlockPos>(flowTargets)
        relocateBlock?.let { highlighted.add(it) }
        val queuedBlocks = AutoMiner.pendingPreview(6).filter { it !in highlighted }

        val now = System.currentTimeMillis()
        val pulse = 0.18f + 0.14f *
            kotlin.math.sin((now % 1200L).toFloat() / 1200f * (Math.PI * 2.0).toFloat())
        val hue = (now % 6000L).toFloat() / 6000f

        val ctx = RenderContext(
            { "bedrock-miner:automation_overlay" },
            MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL,
            //? if >=26.2
            0,
        )
        try {
            val buffer = ctx.builder
            val bodyPos = player.position().add(0.0, 0.9, 0.0)
            val feet = player.position()

            // ---- Active breaks: fill, outline, work line, pulse ring, mesh ----
            for ((index, pos) in flowTargets.withIndex()) {
                blockFill(buffer, pos, cameraPos, COLOR_BREAKING, pulse)
                beamBlockOutline(buffer, pos, cameraPos, COLOR_BREAKING, thick)
                beam(
                    buffer, bodyPos,
                    Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5),
                    cameraPos, COLOR_BREAKING.withAlpha(0.5f), thin,
                )
                if (index < 8) {
                    val fraction = (((now % 1100L).toDouble() / 1100.0) + index * 0.13) % 1.0
                    val ringRadius = 0.2 + fraction * 1.1
                    val alpha = ((1.0 - fraction) * 0.85).toFloat()
                    beamCircle(
                        buffer,
                        Vec3(pos.x + 0.5, pos.y + 0.04, pos.z + 0.5),
                        ringRadius, 14, cameraPos, COLOR_BREAKING.withAlpha(alpha), thin,
                    )
                }
                if (index < flowTargets.size - 1 && index < 11) {
                    val next = flowTargets[index + 1]
                    beam(
                        buffer,
                        Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5),
                        Vec3(next.x + 0.5, next.y + 0.5, next.z + 0.5),
                        cameraPos, COLOR_BREAKING.withAlpha(0.3f), thin * 0.7f,
                    )
                }
            }
            // Live contraptions: piston orange, torch red, support green.
            for (approach in approaches) {
                beamBlockOutline(buffer, approach.pistonPos, cameraPos, COLOR_PISTON, thin)
                beamBlockOutline(buffer, approach.torchPos, cameraPos, COLOR_TORCH, thin)
                approach.supportBlockPos?.let { beamBlockOutline(buffer, it, cameraPos, COLOR_SUPPORT, thin) }
            }

            // ---- Destination, sidestep, clearing, queue ----
            relocateBlock?.let {
                blockFill(buffer, it, cameraPos, COLOR_PATH_TARGET, 0.20f)
                beamBlockOutline(buffer, it, cameraPos, COLOR_PATH_TARGET, thick)
                // Beacon pillar.
                beam(
                    buffer,
                    Vec3(it.x + 0.5, it.y + 1.1, it.z + 0.5),
                    Vec3(it.x + 0.5, it.y + 4.2, it.z + 0.5),
                    cameraPos, COLOR_PATH_TARGET.withAlpha(0.8f), thick,
                )
            }
            sidestepBlock?.let {
                blockFill(buffer, it, cameraPos, COLOR_STAND, 0.18f)
                beamBlockOutline(buffer, it, cameraPos, COLOR_STAND, thick)
            }
            for (pos in clearBlocks) {
                blockFill(buffer, pos, cameraPos, COLOR_CLEARING, 0.20f)
                beamBlockOutline(buffer, pos, cameraPos, COLOR_CLEARING, thick)
            }
            for ((index, pos) in queuedBlocks.withIndex()) {
                beamBlockOutline(buffer, pos, cameraPos, COLOR_QUEUE.withAlpha(0.75f - index * 0.08f), thin)
                if (index < 4) {
                    beam(
                        buffer, bodyPos,
                        Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5),
                        cameraPos, COLOR_QUEUE.withAlpha(0.25f), thin * 0.6f,
                    )
                }
            }

            // ---- Item radar: every drop within 12 blocks classified ----
            // green = wanted and reachable, orange = wanted but blocked or
            // deferred, flashing red = close to despawning, grey = junk the
            // bot will not collect (cobbled deepslate, tuff and so on).
            val blink = ((now % 500L) < 250L)
            val fieldItems = level.getEntitiesOfClass(
                ItemEntity::class.java,
                player.boundingBox.inflate(12.0),
            ) { it.isAlive }
            for (entity in fieldItems.take(32)) {
                val p = entity.position()
                val wanted = AutoPilot.isWantedItem(entity.item.item)
                val color = when {
                    !wanted -> Color4f(0.62f, 0.62f, 0.68f, 0.45f)
                    AutoPilot.isItemAtRisk(entity.id) ->
                        if (blink) Color4f(1f, 0.2f, 0.15f, 1f) else Color4f(1f, 0.55f, 0.2f, 0.9f)
                    AutoPilot.isItemDeferred(entity.id) ||
                        !AutoPilot.isItemAccessible(level, player, entity) ->
                        Color4f(1f, 0.65f, 0.2f, 0.8f)
                    else -> Color4f(0.3f, 1f, 0.45f, 0.95f)
                }
                // Vertical marker beam plus a small diamond at the item.
                val markerHeight = if (wanted) 0.9 else 0.45
                beam(
                    buffer,
                    Vec3(p.x, p.y + 0.3, p.z),
                    Vec3(p.x, p.y + 0.3 + markerHeight, p.z),
                    cameraPos, color, if (wanted) thin else thin * 0.6f,
                )
                beamBox(
                    buffer,
                    p.x - 0.14, p.y, p.z - 0.14,
                    p.x + 0.14, p.y + 0.28, p.z + 0.14,
                    cameraPos, color, thin * 0.7f,
                )
            }
            // The currently targeted item gets the pickup line and a pointer.
            if (item != null) {
                val p = item.position()
                beam(buffer, bodyPos, p.add(0.0, 0.2, 0.0), cameraPos, COLOR_WALK_COLLECT, thick)
                chevron(buffer, p.add(0.0, 1.3, 0.0), cameraPos, hueColor((now % 3000L).toFloat() / 3000f, 1f), thick)
            }
            relocateBlock?.let {
                chevron(
                    buffer, Vec3(it.x + 0.5, it.y + 2.0, it.z + 0.5), cameraPos,
                    hueColor((now % 3000L).toFloat() / 3000f, 1f), thick,
                )
            }

            // ---- Ground radar: rings, grid, compass, sweep ----
            run {
                val radius = Configs.AutoMine.maxRange
                val ringColor = hueColor(hue, 0.6f)
                val center = Vec3(feet.x, feet.y + 0.045, feet.z)

                // Faint shaded disc: the radar has a surface, not just edges.
                disc(buffer, center, radius, 40, cameraPos, hueColor(hue, 0.04f))
                // Slightly stronger band under the tick ring.
                ring(buffer, center, radius * 0.86, radius, 40, cameraPos, hueColor(hue, 0.06f))
                // Classic afterglow wedge trailing the sweep.
                val sweepNow = (now % 2400L).toDouble() / 2400.0 * Math.PI * 2.0
                val glowSteps = 12
                for (g in 0 until glowSteps) {
                    val a1 = sweepNow - (g + 1) * 0.09
                    val a2 = sweepNow - g * 0.09
                    val alpha = 0.30f * (1f - g.toFloat() / glowSteps)
                    quad(
                        buffer,
                        center,
                        Vec3(center.x + radius * kotlin.math.cos(a1), center.y, center.z + radius * kotlin.math.sin(a1)),
                        Vec3(center.x + radius * kotlin.math.cos(a2), center.y, center.z + radius * kotlin.math.sin(a2)),
                        center,
                        cameraPos, hueColor(hue, alpha),
                    )
                }

                beamCircle(buffer, Vec3(feet.x, feet.y + 0.05, feet.z), radius, 40, cameraPos, ringColor, thick)
                beamCircle(
                    buffer, Vec3(feet.x, feet.y + 0.05, feet.z), radius * 0.55, 28, cameraPos,
                    hueColor((hue + 0.15f) % 1f, 0.35f), thin,
                )
                // Radial ticks.
                for (i in 0 until 8) {
                    val angle = i.toDouble() / 8.0 * Math.PI * 2.0
                    val cos = kotlin.math.cos(angle)
                    val sin = kotlin.math.sin(angle)
                    beam(
                        buffer,
                        Vec3(feet.x + radius * 0.88 * cos, feet.y + 0.05, feet.z + radius * 0.88 * sin),
                        Vec3(feet.x + radius * cos, feet.y + 0.05, feet.z + radius * sin),
                        cameraPos, ringColor.withAlpha(0.8f), thin,
                    )
                }
                // Compass, north red.
                for (cardinal in 0 until 4) {
                    val angle = cardinal * Math.PI / 2.0
                    val cos = kotlin.math.cos(angle)
                    val sin = kotlin.math.sin(angle)
                    val color = if (cardinal == 3) Color4f(1f, 0.3f, 0.3f, 0.95f) else Color4f(1f, 1f, 1f, 0.7f)
                    beam(
                        buffer,
                        Vec3(feet.x + radius * 0.75 * cos, feet.y + 0.06, feet.z + radius * 0.75 * sin),
                        Vec3(feet.x + radius * 1.08 * cos, feet.y + 0.06, feet.z + radius * 1.08 * sin),
                        cameraPos, color, thick,
                    )
                }
                // Rotating sweep with trails.
                val sweepBase = (now % 2400L).toDouble() / 2400.0 * Math.PI * 2.0
                for (trail in 0..2) {
                    val angle = sweepBase - trail * 0.21
                    beam(
                        buffer,
                        Vec3(feet.x, feet.y + 0.05, feet.z),
                        Vec3(
                            feet.x + radius * kotlin.math.cos(angle),
                            feet.y + 0.05,
                            feet.z + radius * kotlin.math.sin(angle),
                        ),
                        cameraPos, hueColor(hue, 0.85f - trail * 0.3f), if (trail == 0) thick else thin,
                    )
                }
                // Heading projection while moving.
                val motion = player.deltaMovement
                val speedH = kotlin.math.sqrt(motion.x * motion.x + motion.z * motion.z)
                if (speedH > 0.03) {
                    val hx = motion.x / speedH
                    val hz = motion.z / speedH
                    for (seg in 0 until 6) {
                        val from = 0.6 + seg * 0.55
                        beam(
                            buffer,
                            Vec3(feet.x + hx * from, feet.y + 0.12, feet.z + hz * from),
                            Vec3(feet.x + hx * (from + 0.32), feet.y + 0.12, feet.z + hz * (from + 0.32)),
                            cameraPos, Color4f(0.3f, 1f, 0.9f, 0.9f - seg * 0.12f), thin,
                        )
                    }
                }
            }

            // ---- Box corner brackets ----
            AutoMiner.previewArea()?.let { area ->
                val bracketColor = hueColor((hue + 0.5f) % 1f, 0.95f)
                val len = 0.7
                for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
                    val x = if (cx == 0) area.minX.toDouble() else area.maxX + 1.0
                    val y = if (cy == 0) area.minY.toDouble() else area.maxY + 1.0
                    val z = if (cz == 0) area.minZ.toDouble() else area.maxZ + 1.0
                    val corner = Vec3(x, y, z)
                    beam(buffer, corner, corner.add(if (cx == 0) len else -len, 0.0, 0.0), cameraPos, bracketColor, thick)
                    beam(buffer, corner, corner.add(0.0, if (cy == 0) len else -len, 0.0), cameraPos, bracketColor, thick)
                    beam(buffer, corner, corner.add(0.0, 0.0, if (cz == 0) len else -len), cameraPos, bracketColor, thick)
                }
            }

            // ---- Walk line, route, goal marker ----
            val walkTarget = PlayerMover.currentTarget
            if (walkTarget != null) {
                val color = when {
                    sidestepBlock != null -> COLOR_STAND
                    AutoPilot.phase == AutoPilot.Phase.COLLECTING ||
                        AutoPilot.phase == AutoPilot.Phase.MINING || item != null -> COLOR_WALK_COLLECT
                    else -> COLOR_WALK_RELOCATE
                }
                beam(buffer, bodyPos, walkTarget.add(0.0, 0.3, 0.0), cameraPos, color, thin)
                val route = PlayerMover.currentPath
                if (route.isNotEmpty()) {
                    val time = (now % 900L).toFloat() / 900f
                    var previous = player.position().add(0.0, 0.15, 0.0)
                    var previousY = player.position().y
                    for ((index, waypoint) in route.take(32).withIndex()) {
                        val next = Vec3(waypoint.x + 0.5, waypoint.y + 0.15, waypoint.z + 0.5)
                        val base = when {
                            waypoint.y > previousY + 0.5 -> COLOR_STEP_UP
                            waypoint.y < previousY - 0.5 -> COLOR_STEP_DOWN
                            else -> color
                        }
                        val marching = kotlin.math.sin(index * 0.45 - time * Math.PI * 2.0).toFloat()
                        val alpha = 0.55f + 0.45f * ((marching + 1f) / 2f)
                        beam(buffer, previous, next, cameraPos, base.withAlpha(alpha), thin)
                        // Step tiles: strongly shaded surface on every block it
                        // plans to walk over, framed, brightest for the next
                        // step, breathing with the marching pulse.
                        val tileAlpha = ((if (index == 0) 0.65f else 0.45f - index * 0.010f).coerceAtLeast(0.18f)) *
                            (0.8f + 0.2f * ((marching + 1f) / 2f))
                        quad(
                            buffer,
                            Vec3(waypoint.x + 0.08, waypoint.y + 0.03, waypoint.z + 0.08),
                            Vec3(waypoint.x + 0.92, waypoint.y + 0.03, waypoint.z + 0.08),
                            Vec3(waypoint.x + 0.92, waypoint.y + 0.03, waypoint.z + 0.92),
                            Vec3(waypoint.x + 0.08, waypoint.y + 0.03, waypoint.z + 0.92),
                            cameraPos, base.withAlpha(tileAlpha),
                        )
                        // Tile frame.
                        val f = tileAlpha + 0.25f
                        beam(buffer, Vec3(waypoint.x + 0.08, waypoint.y + 0.035, waypoint.z + 0.08), Vec3(waypoint.x + 0.92, waypoint.y + 0.035, waypoint.z + 0.08), cameraPos, base.withAlpha(f), thin * 0.5f)
                        beam(buffer, Vec3(waypoint.x + 0.92, waypoint.y + 0.035, waypoint.z + 0.08), Vec3(waypoint.x + 0.92, waypoint.y + 0.035, waypoint.z + 0.92), cameraPos, base.withAlpha(f), thin * 0.5f)
                        beam(buffer, Vec3(waypoint.x + 0.92, waypoint.y + 0.035, waypoint.z + 0.92), Vec3(waypoint.x + 0.08, waypoint.y + 0.035, waypoint.z + 0.92), cameraPos, base.withAlpha(f), thin * 0.5f)
                        beam(buffer, Vec3(waypoint.x + 0.08, waypoint.y + 0.035, waypoint.z + 0.92), Vec3(waypoint.x + 0.08, waypoint.y + 0.035, waypoint.z + 0.08), cameraPos, base.withAlpha(f), thin * 0.5f)
                        // Direction arrowhead every second segment.
                        if (index % 2 == 0) {
                            val seg = next.subtract(previous)
                            val segH = Vec3(seg.x, 0.0, seg.z)
                            if (segH.lengthSqr() > 1.0e-4) {
                                val dir = segH.normalize()
                                val mid = previous.add(seg.scale(0.55)).add(0.0, 0.04, 0.0)
                                val left = Vec3(-dir.z, 0.0, dir.x)
                                val backLeft = mid.subtract(dir.scale(0.30)).add(left.scale(0.20))
                                val backRight = mid.subtract(dir.scale(0.30)).subtract(left.scale(0.20))
                                beam(buffer, backLeft, mid, cameraPos, base.withAlpha(0.95f), thin * 0.7f)
                                beam(buffer, backRight, mid, cameraPos, base.withAlpha(0.95f), thin * 0.7f)
                            }
                        }
                        beamBox(
                            buffer,
                            next.x - 0.09, next.y - 0.09, next.z - 0.09,
                            next.x + 0.09, next.y + 0.09, next.z + 0.09,
                            cameraPos, base.withAlpha(0.9f), thin,
                        )
                        previous = next
                        previousY = waypoint.y.toDouble()
                    }
                }
                beamBox(
                    buffer,
                    walkTarget.x - 0.18, walkTarget.y + 0.02, walkTarget.z - 0.18,
                    walkTarget.x + 0.18, walkTarget.y + 0.14, walkTarget.z + 0.18,
                    cameraPos, color, thin,
                )
                // Pulsing landing ring at the walking goal.
                val goalFraction = ((now % 1000L).toDouble() / 1000.0)
                beamCircle(
                    buffer,
                    Vec3(walkTarget.x, walkTarget.y + 0.03, walkTarget.z),
                    0.25 + goalFraction * 0.45, 16, cameraPos,
                    color.withAlpha(((1.0 - goalFraction) * 0.9).toFloat()), thin * 0.6f,
                )
            }

            val meshData = buffer.build()
            if (meshData != null) {
                ctx.draw(meshData, false, true)
                meshData.close()
            }
        } catch (err: Exception) {
            MaLiLib.LOGGER.error("AreaRenderer.renderAutomationOverlay(): Draw exception; {}", err.message)
        } finally {
            ctx.close()
        }
    }

    /** One line segment as two crossed quads: visible at any thickness. */
    private fun beam(buffer: BufferBuilder, from: Vec3, to: Vec3, cameraPos: Vec3, color: Color4f, thickness: Float) {
        val direction = to.subtract(from)
        if (direction.lengthSqr() < 1.0e-8) return
        val dir = direction.normalize()
        var side = dir.cross(Vec3(0.0, 1.0, 0.0))
        if (side.lengthSqr() < 1.0e-6) side = Vec3(1.0, 0.0, 0.0)
        side = side.normalize().scale(thickness.toDouble())
        val up = dir.cross(side).normalize().scale(thickness.toDouble())
        quad(buffer, from.subtract(side), from.add(side), to.add(side), to.subtract(side), cameraPos, color)
        quad(buffer, from.subtract(up), from.add(up), to.add(up), to.subtract(up), cameraPos, color)
    }

    private fun quad(buffer: BufferBuilder, a: Vec3, b: Vec3, c: Vec3, d: Vec3, cameraPos: Vec3, color: Color4f) {
        for (v in arrayOf(a, b, c, d)) {
            buffer.addVertex(
                (v.x - cameraPos.x).toFloat(), (v.y - cameraPos.y).toFloat(), (v.z - cameraPos.z).toFloat(),
            ).setColor(color.r, color.g, color.b, color.a)
        }
    }

    /** Twelve edge beams forming a box outline. */
    private fun beamBox(
        buffer: BufferBuilder,
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
        cameraPos: Vec3, color: Color4f, thickness: Float,
    ) {
        val c000 = Vec3(minX, minY, minZ); val c100 = Vec3(maxX, minY, minZ)
        val c010 = Vec3(minX, maxY, minZ); val c110 = Vec3(maxX, maxY, minZ)
        val c001 = Vec3(minX, minY, maxZ); val c101 = Vec3(maxX, minY, maxZ)
        val c011 = Vec3(minX, maxY, maxZ); val c111 = Vec3(maxX, maxY, maxZ)
        beam(buffer, c000, c100, cameraPos, color, thickness); beam(buffer, c010, c110, cameraPos, color, thickness)
        beam(buffer, c001, c101, cameraPos, color, thickness); beam(buffer, c011, c111, cameraPos, color, thickness)
        beam(buffer, c000, c010, cameraPos, color, thickness); beam(buffer, c100, c110, cameraPos, color, thickness)
        beam(buffer, c001, c011, cameraPos, color, thickness); beam(buffer, c101, c111, cameraPos, color, thickness)
        beam(buffer, c000, c001, cameraPos, color, thickness); beam(buffer, c100, c101, cameraPos, color, thickness)
        beam(buffer, c010, c011, cameraPos, color, thickness); beam(buffer, c110, c111, cameraPos, color, thickness)
    }

    private fun beamBlockOutline(buffer: BufferBuilder, pos: BlockPos, cameraPos: Vec3, color: Color4f, thickness: Float) {
        beamBox(
            buffer,
            pos.x - 0.005, pos.y - 0.005, pos.z - 0.005,
            pos.x + 1.005, pos.y + 1.005, pos.z + 1.005,
            cameraPos, color, thickness,
        )
    }

    /** Filled translucent disc (triangle fan out of degenerate quads). */
    private fun disc(buffer: BufferBuilder, center: Vec3, radius: Double, segments: Int, cameraPos: Vec3, color: Color4f) {
        var previous: Vec3? = null
        for (i in 0..segments) {
            val angle = i.toDouble() / segments * Math.PI * 2.0
            val point = Vec3(
                center.x + radius * kotlin.math.cos(angle),
                center.y,
                center.z + radius * kotlin.math.sin(angle),
            )
            previous?.let { quad(buffer, center, it, point, center, cameraPos, color) }
            previous = point
        }
    }

    /** Filled translucent annulus between two radii. */
    private fun ring(
        buffer: BufferBuilder, center: Vec3, innerRadius: Double, outerRadius: Double,
        segments: Int, cameraPos: Vec3, color: Color4f,
    ) {
        var previousInner: Vec3? = null
        var previousOuter: Vec3? = null
        for (i in 0..segments) {
            val angle = i.toDouble() / segments * Math.PI * 2.0
            val cos = kotlin.math.cos(angle)
            val sin = kotlin.math.sin(angle)
            val inner = Vec3(center.x + innerRadius * cos, center.y, center.z + innerRadius * sin)
            val outer = Vec3(center.x + outerRadius * cos, center.y, center.z + outerRadius * sin)
            if (previousInner != null && previousOuter != null) {
                quad(buffer, previousInner!!, previousOuter!!, outer, inner, cameraPos, color)
            }
            previousInner = inner
            previousOuter = outer
        }
    }

    private fun beamCircle(
        buffer: BufferBuilder, center: Vec3, radius: Double, segments: Int,
        cameraPos: Vec3, color: Color4f, thickness: Float,
    ) {
        var previous: Vec3? = null
        for (i in 0..segments) {
            val angle = i.toDouble() / segments * Math.PI * 2.0
            val point = Vec3(
                center.x + radius * kotlin.math.cos(angle),
                center.y,
                center.z + radius * kotlin.math.sin(angle),
            )
            previous?.let { beam(buffer, it, point, cameraPos, color, thickness) }
            previous = point
        }
    }

    /** Downward pointing bobbing chevron. */
    private fun chevron(buffer: BufferBuilder, base: Vec3, cameraPos: Vec3, color: Color4f, thickness: Float) {
        val bob = 0.22 * kotlin.math.sin((System.currentTimeMillis() % 900L).toDouble() / 900.0 * Math.PI * 2.0)
        val tip = base.add(0.0, bob, 0.0)
        beam(buffer, tip.add(-0.28, 0.42, 0.0), tip, cameraPos, color, thickness)
        beam(buffer, tip.add(0.28, 0.42, 0.0), tip, cameraPos, color, thickness)
        beam(buffer, tip.add(0.0, 0.42, -0.28), tip, cameraPos, color, thickness)
        beam(buffer, tip.add(0.0, 0.42, 0.28), tip, cameraPos, color, thickness)
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
