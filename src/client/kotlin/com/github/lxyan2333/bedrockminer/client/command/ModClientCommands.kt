package com.github.lxyan2333.bedrockminer.client.command

import com.github.lxyan2333.bedrockminer.client.automate.AutoPilot
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands

object ModClientCommands {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("bedrockbreaker")
                    .then(
                        ClientCommands.literal("automate")
                            .then(ClientCommands.literal("on").executes { context ->
                                AutoPilot.start(context.source)
                                1
                            })
                            .then(ClientCommands.literal("off").executes { context ->
                                AutoPilot.stopByCommand(context.source)
                                1
                            })
                            .then(ClientCommands.literal("status").executes { context ->
                                AutoPilot.sendStatus(context.source)
                                1
                            })
                    )
            )
        }
    }
}
