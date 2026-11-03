package com.rogervendrell.depthwindow;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

final class DepthWindowCommands {
	private DepthWindowCommands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("depthwindow")
			.executes(ctx -> {
				for (String line : DepthWindowHud.statusLines()) {
					ctx.getSource().sendFeedback(Component.literal(line));
				}
				return 1;
			})
			.then(literal("menu").executes(ctx -> {
				DepthWindowClient.openMenuSoon();
				return 1;
			}))
			.then(literal("toggle").executes(ctx -> {
				DepthWindowClient.toggle();
				return 1;
			}))
			.then(literal("recenter").executes(ctx -> {
				ctx.getSource().sendFeedback(DepthWindowClient.recenter());
				return 1;
			}))
			.then(literal("reload").executes(ctx -> {
				DepthWindowClient.reloadConfig();
				ctx.getSource().sendFeedback(Component.literal("Depth Window: config reloaded"));
				return 1;
			}))
			.then(literal("reset").executes(ctx -> {
				DepthWindowClient.resetConfig();
				ctx.getSource().sendFeedback(Component.literal("Depth Window: config reset to defaults"));
				return 1;
			}))
			.then(literal("get").executes(ctx -> {
				DepthWindowConfig cfg = DepthWindowClient.config();
				for (String name : DepthWindowConfig.optionNames()) {
					ctx.getSource().sendFeedback(Component.literal(name + " = ").append(Component.literal(cfg.get(name)).withStyle(ChatFormatting.AQUA)));
				}
				return 1;
			}))
			.then(literal("set")
				.then(argument("option", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DepthWindowConfig.optionNames(), builder))
					.then(argument("value", StringArgumentType.greedyString())
						.executes(ctx -> {
							String option = StringArgumentType.getString(ctx, "option");
							String value = StringArgumentType.getString(ctx, "value");
							DepthWindowConfig cfg = DepthWindowClient.config();
							try {
								cfg.set(option, value);
							} catch (IllegalArgumentException e) {
								ctx.getSource().sendError(Component.literal(e.getMessage()));
								return 0;
							}
							cfg.save();
							if (option.equals("udpPort") || option.equals("udpBindAddress")) {
								DepthWindowClient.restartReceiver();
							}
							ctx.getSource().sendFeedback(Component.literal("Depth Window: " + option + " = " + cfg.get(option)));
							return 1;
						})))));
	}
}
