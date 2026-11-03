package com.rogervendrell.minecraftparallax;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

final class ParallaxCommands {
	private ParallaxCommands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("parallax")
			.executes(ctx -> {
				for (String line : ParallaxHud.statusLines()) {
					ctx.getSource().sendFeedback(Component.literal(line));
				}
				return 1;
			})
			.then(literal("menu").executes(ctx -> {
				ParallaxClient.openMenuSoon();
				return 1;
			}))
			.then(literal("toggle").executes(ctx -> {
				ParallaxClient.toggle();
				return 1;
			}))
			.then(literal("recenter").executes(ctx -> {
				ctx.getSource().sendFeedback(ParallaxClient.recenter());
				return 1;
			}))
			.then(literal("reload").executes(ctx -> {
				ParallaxClient.reloadConfig();
				ctx.getSource().sendFeedback(Component.literal("Minecraft Parallax: config reloaded"));
				return 1;
			}))
			.then(literal("reset").executes(ctx -> {
				ParallaxClient.resetConfig();
				ctx.getSource().sendFeedback(Component.literal("Minecraft Parallax: config reset to defaults"));
				return 1;
			}))
			.then(literal("get").executes(ctx -> {
				ParallaxConfig cfg = ParallaxClient.config();
				for (String name : ParallaxConfig.optionNames()) {
					ctx.getSource().sendFeedback(Component.literal(name + " = ").append(Component.literal(cfg.get(name)).withStyle(ChatFormatting.AQUA)));
				}
				return 1;
			}))
			.then(literal("set")
				.then(argument("option", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ParallaxConfig.optionNames(), builder))
					.then(argument("value", StringArgumentType.greedyString())
						.executes(ctx -> {
							String option = StringArgumentType.getString(ctx, "option");
							String value = StringArgumentType.getString(ctx, "value");
							ParallaxConfig cfg = ParallaxClient.config();
							try {
								cfg.set(option, value);
							} catch (IllegalArgumentException e) {
								ctx.getSource().sendError(Component.literal(e.getMessage()));
								return 0;
							}
							cfg.save();
							if (option.equals("udpPort") || option.equals("udpBindAddress")) {
								ParallaxClient.restartReceiver();
							}
							ctx.getSource().sendFeedback(Component.literal("Minecraft Parallax: " + option + " = " + cfg.get(option)));
							return 1;
						})))));
	}
}
