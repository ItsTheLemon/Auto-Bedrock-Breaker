# Auto Bedrock Breaker

Fully automated bedrock clearing. You mark a box, run one command, and the mod does the entire job by itself: breaking, walking, collecting, managing its own tools and food. You can tab out and come back to a clean area.

This is a fork of [Fabric-Bedrock-Miner](https://github.com/LXYan2333/Fabric-Bedrock-Miner) by [LXYan2333](https://github.com/LXYan2333). The original mod and the whole piston breaking technique are their work, go leave them a star. Everything the original does still works here, this fork adds the automation on top.

## How to use (the short version)

1. Install this jar plus the dependencies listed below into your `mods` folder
2. In game, open the config menu with `LEFT_ALT+B+C` and go to the **Auto Mine** tab
3. Stand in one corner of the area you want cleared, click **Set here** next to Pos1
4. Stand in the opposite corner, click **Set here** next to Pos2. The box shows up in orange
5. Type `/bedrockbreaker automate on`

Done. The mod now runs the whole process on its own. `/bedrockbreaker automate off` stops it at any time and `/bedrockbreaker automate status` prints what step it is on. A status panel in the top left shows progress, ETA, speed, item counts and pickaxe durability the entire time.

What you need in your inventory:

- An Efficiency V diamond or netherite pickaxe, ideally with spares, the mod rotates them before they break
- Pistons, redstone torches and slime blocks (more concurrency burns them faster)
- A Haste II beacon covering the area
- Some food such as golden carrots if you keep auto eat on

## NEW in this fork

Everything in this list did not exist in the original mod:

- **Full automation command** `/bedrockbreaker automate on|off|status`. The complete cycle runs alone: break everything in reach, walk to the next spot, grab dropped items on the way, repeat until the box is clean, then shut off and report
- **Pathfinding.** A real A* pathfinder plans every walk. It goes around obstacles, takes single block steps, drops off ledges safely, never corner clips and never attempts impossible jumps. The player also turns head and body toward where it walks so it looks natural
- **Smart item collection.** Mining always has priority. Drops are picked up while walking between spots, and a dedicated pickup run only happens when supplies get low, a drop nears its 5 minute despawn timer, or as a final sweep. Stranded pistons and torches from failed attempts get mined back and recovered, even just outside the box edge
- **Clear-all mode.** Every breakable block inside the box that is not bedrock is mined normally with your pickaxe so the area ends up completely empty. Bedrock keeps using the piston method. Liquids and vines are ignored. Toggleable
- **Tool protection.** Set a durability floor (default 150) and the mod will never swing a pickaxe at or below it. It swaps to another healthy Efficiency V pickaxe automatically, shows "Swapping pickaxe" on screen when it does, and stops with a clear warning if no usable pickaxe is left
- **Auto eat.** At 3 hunger bars it pauses, lets running piston setups finish, eats from your inventory (golden carrots preferred, never harmful food), then continues. Toggleable
- **Status HUD.** Current step, progress bar with percent, ETA, speed in blocks per minute, elapsed time, items collected, nearby drops with despawn warnings, queue depth, pickaxe durability and a rolling activity log
- **World overlay.** Pulsing red boxes on blocks being broken, the live piston contraption outlined piece by piece, the planned route drawn on the ground with color coded steps, waypoint markers, a radar ring showing your mining range with a rotating sweep, and a beacon pillar over the next destination. Line thickness is a slider
- **Quality of life.** Clickable Set here buttons for the box corners, sliders for concurrency (up to 20 setups at once), launch stagger, mining range and more. Keeps running with inventory or chat open or while tabbed out. A watchdog unsticks it if anything ever stalls
- Support for the current Minecraft versions, see Releases for jars

## From the original mod

The manual and semi automatic modes work exactly like upstream:

- Toggle the mod with `LEFT_ALT+B+M`, then left click a bedrock block to "mine" it with the piston trick
- The Auto Mine toggle `LEFT_ALT+B+A` mines the configured box from wherever you walk yourself, without the autopilot moving you
- Client and server allow/block lists, the area restriction feature, approach modes and all the original config options are still there

Showcase of the original technique:
https://www.youtube.com/watch?v=b8Y86yxjr_Y
https://www.bilibili.com/video/BV1Fv411P7Vc

## Dependencies

- Fabric API https://modrinth.com/mod/fabric-api
- malilib https://modrinth.com/mod/malilib
- Fabric Language Kotlin https://modrinth.com/mod/fabric-language-kotlin
- (Optional) Mod Menu https://modrinth.com/mod/modmenu

## Config overview

Open with `LEFT_ALT+B+C`.

- **Generic tab:** tool protection toggle and durability threshold, auto eat toggle, approach mode, support block, retries
- **Auto Mine tab:** Pos1/Pos2 with Set here buttons, target blocks, clear-all toggle, mining range slider, concurrency and launch delay sliders, retry cooldown, box colors and overlay line width
- **Area / Client / Server tabs:** same as upstream

## Compile

```
./gradlew :26.2:build
./gradlew :26.1.2:build
```

Jars land in `versions/<mc>/build/libs/`.

## Credits

Original mod, piston method and the multi version project scaffolding by [LXYan2333](https://github.com/LXYan2333). This fork builds on top of their work and keeps the same license.
