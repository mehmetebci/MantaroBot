/*
 * Copyright (C) 2016 Kodehawa
 *
 * Mantaro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Mantaro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 *
 */

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.currency.item.ItemHelper;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.command.text.TextCommand;
import net.kodehawa.mantarobot.core.command.text.TextContext;
import net.kodehawa.mantarobot.core.command.argument.Parsers;
import net.kodehawa.mantarobot.core.command.meta.Category;
import net.kodehawa.mantarobot.core.command.meta.Help;
import net.kodehawa.mantarobot.core.command.meta.Name;
import net.kodehawa.mantarobot.core.command.meta.Permission;
import net.kodehawa.mantarobot.core.listeners.operations.InteractiveOperations;
import net.kodehawa.mantarobot.core.listeners.operations.core.Operation;
import net.kodehawa.mantarobot.core.command.meta.Module;
import net.kodehawa.mantarobot.core.command.helpers.CommandCategory;
import net.kodehawa.mantarobot.core.command.helpers.CommandPermission;
import net.kodehawa.mantarobot.core.command.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.MantaroObject;
import net.kodehawa.mantarobot.db.entities.Player;
import net.kodehawa.mantarobot.db.entities.PremiumKey;
import net.kodehawa.mantarobot.utils.APIUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.data.JsonDataManager;
import net.kodehawa.mantarobot.utils.eval.JavaEvaluator;
import net.kodehawa.mantarobot.utils.eval.MavenDependencies;

import java.awt.Color;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

@Module
public class OwnerCmd {
    @Subscribe
    public void register(CommandRegistry cr) {
        cr.register(TransferPlayer.class);
        cr.register(GiveItem.class);
        cr.register(RestoreStreak.class);
        cr.register(DataRequest.class);
        cr.register(AddBadge.class);
        cr.register(RemoveBadge.class);
        cr.register(RefreshPledges.class);
        cr.register(AddOwnerPremium.class);
        cr.register(Blacklist.class);
        cr.register(Link.class);
        cr.register(CreateKey.class);
        cr.register(InvalidateKey.class);
        cr.register(Eval.class);
    }

    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class RestoreStreak extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            var id = ctx.argument(Parsers.strictLong()
                    .map(String::valueOf), "Invalid id");
            var amount = ctx.argument(Parsers.strictLong(), "Invalid amount");

            var u = ctx.retrieveUserById(id);

            if (u == null) {
                ctx.send("Can't find user");
                return;
            }

            var p = MantaroData.db().getPlayer(id);
            p.lastDailyAt(System.currentTimeMillis());
            p.dailyStreak(amount);

            p.updateAllChanged();

            ctx.send("Done, new streak is " + amount);
        }
    }

    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class DataRequest extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            var db = MantaroData.db();
            var id = ctx.argument(Parsers.strictLong()
                    .map(String::valueOf), "Invalid id");

            var user = ctx.retrieveUserById(id);

            if (user == null) {
                ctx.send("Can't find user");
                return;
            }

            var player = db.getPlayer(user);
            var dbUser = db.getUser(user);

            try {
                var jsonPlayer = JsonDataManager.toJson(player);
                var jsonUser = JsonDataManager.toJson(dbUser);

                var total = "Player:%n%s%n ---- %nUser:%n%s%n".formatted(jsonPlayer, jsonUser);
                byte[] bytes = total.getBytes(StandardCharsets.UTF_8);

                if (bytes.length > 7_800_000) {
                    ctx.send("Result too big!");
                } else {
                    ctx.sendFile(bytes, "result.json");
                }
            } catch (Exception e) {
                ctx.send("Error. Check logs. " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class GiveItem extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            var itemString = ctx.argument(Parsers.delimitedBy('"', false), "Invalid item");
            int amount = ctx.argument(Parsers.strictInt(), "Invalid item amount");

            var item = ItemHelper.fromAnyNoId(itemString, new I18nContext()).orElse(null);

            if (item == null) {
                ctx.send(EmoteReference.ERROR + "I didn't find that item.");
                return;
            }

            var player = ctx.getPlayer();

            if (player.fitsItemAmount(item, amount)) {
                player.processItem(item, amount);
            } else {
                ctx.send(EmoteReference.ERROR + "Too many of this item already.");
                return;
            }

            player.updateAllChanged();
            ctx.send("Gave you %s (x%,d)".formatted(item, amount));
        }
    }


    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class TransferPlayer extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            var transferred = ctx.argument(Parsers.strictLong().map(String::valueOf),
                    "Invalid user (transferring from)"
            );

            var transferTo = ctx.argument(Parsers.strictLong().map(String::valueOf),
                    "Invalid user (to transfer to)"
            );

            ctx.send("%sYou're about to transfer all the player information from %s to %s are you sure you want to continue?".formatted(
                    EmoteReference.WARNING, transferred, transferTo
            ));

            InteractiveOperations.create(ctx.getChannel(), ctx.getAuthor().getIdLong(), 30, e -> {
                if (e.getAuthor().getIdLong() != ctx.getAuthor().getIdLong()) {
                    return Operation.IGNORED;
                }

                var transferredPlayer = ctx.getPlayer(transferred);
                var transferToPlayer = ctx.getPlayer(transferTo);

                if (e.getMessage().getContentRaw().equalsIgnoreCase("yes")) {
                    transferToPlayer.currentMoney(transferredPlayer.getCurrentMoney());
                    transferToPlayer.level(transferredPlayer.getLevel());
                    transferToPlayer.reputation(transferredPlayer.getReputation());
                    transferToPlayer.mergeInventory(transferredPlayer.getInventoryList());

                    transferToPlayer.setExperience(transferredPlayer.getExperience());
                    transferToPlayer.setBadges(transferredPlayer.getBadges());
                    transferToPlayer.showBadge(transferredPlayer.isShowBadge());
                    transferToPlayer.marketUsed(transferredPlayer.getMarketUsed());
                    transferToPlayer.mainBadge(transferredPlayer.getMainBadge());
                    transferToPlayer.gamesWon(transferredPlayer.getGamesWon());
                    transferToPlayer.setMiningExperience(transferredPlayer.getMiningExperience());
                    transferToPlayer.sharksCaught(transferredPlayer.getSharksCaught());
                    transferToPlayer.setFishingExperience(transferredPlayer.getFishingExperience());
                    transferToPlayer.cratesOpened(transferredPlayer.getCratesOpened());
                    transferToPlayer.timesMopped(transferredPlayer.getTimesMopped());
                    transferToPlayer.dailyStreak(transferredPlayer.getDailyStreak());
                    transferToPlayer.lastDailyAt(transferredPlayer.getLastDailyAt());
                    transferToPlayer.setPet(transferredPlayer.getPet());
                    transferToPlayer.petChoice(transferredPlayer.getPetChoice());

                    transferToPlayer.insertOrReplace();

                    var reset = Player.of(transferred);
                    reset.insertOrReplace();

                    ctx.send("%sTransfer from %s %s completed.".formatted(
                            EmoteReference.CORRECT, transferred, transferTo
                    ));

                    return Operation.COMPLETED;
                }

                if (e.getMessage().getContentRaw().equalsIgnoreCase("no")) {
                    ctx.send(EmoteReference.CORRECT + "Cancelled.");
                    return Operation.COMPLETED;
                }

                return Operation.IGNORED;
            });
        }
    }

    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class AddBadge extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            final var toAdd = ctx.argument(Parsers.string(),
                    "Wrong or no badge specified."
            );

            final var user = ctx.argument(Parsers.strictLong()
                            .map(String::valueOf)
                            .map(ctx::retrieveUserById),
                    "Invalid user (to give)"
            );

            var badge = Badge.lookupFromString(toAdd);
            if (badge == null) {
                ctx.send(EmoteReference.ERROR + "No badge with that enum name! Valid badges: " +
                        Arrays.stream(Badge.values()).map(b1 -> "`" + b1.toString() + "`").collect(Collectors.joining(" ,")));
                return;
            }

            var player = ctx.getPlayer(user);
            player.addBadgeIfAbsent(badge);
            player.updateAllChanged();

            ctx.send("%sAdded badge %s %s to %s (ID: %s)".formatted(
                    EmoteReference.CORRECT.getUnicode(), badge.icon, badge.display,
                    Utils.getTagOrDisplay(user), user.getId()
            ));

        }
    }

    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class RemoveBadge extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            final var toRemove = ctx.argument(Parsers.string(),
                    "Wrong or no badge specified."
            );

            final var user = ctx.argument(Parsers.strictLong()
                            .map(String::valueOf)
                            .map(ctx::retrieveUserById),
                    "Invalid user (to remove)"
            );

            var badge = Badge.lookupFromString(toRemove);
            if (badge == null) {
                ctx.send(EmoteReference.ERROR + "No badge with that enum name! Valid badges: " +
                        Arrays.stream(Badge.values()).map(b1 -> "`" + b1.name() + "`").collect(Collectors.joining(" ,")));
                return;
            }

            Player player = MantaroData.db().getPlayer(user);
            if (player.removeBadge(badge)) {
                ctx.send("%sRemoved badge %s from %s (%s)".formatted(
                        EmoteReference.CORRECT, badge,
                        Utils.getTagOrDisplay(user), user.getId())
                );
                player.updateAllChanged();
            } else {
                ctx.send("Player didn't have badge?");
            }

        }
    }

    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class RefreshPledges extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            try {
                APIUtils.getFrom("/mantaroapi/bot/patreon/refresh");
                ctx.send("Refreshed Patreon pledges successfully.");
            } catch (Exception e) {
                ctx.send("Somehow this failed. Pretty sure that just always returned ok...");
                e.printStackTrace();
            }
        }
    }

    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class AddOwnerPremium extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            final var guild = ctx.argument(Parsers.strictLong()
                            .map(String::valueOf), "Invalid guild"
            );

            final var days = ctx.argument(Parsers.strictLong(),
                    "Invalid day amount"
            );

            var guildObject = MantaroBot.getInstance().getShardManager().getGuildById(guild);
            if (guildObject == null) {
                ctx.send("Invalid guild.");
                return;
            }

            var dbGuild = MantaroData.db().getGuild(guild);
            dbGuild.incrementPremium(TimeUnit.DAYS.toMillis(days));
            dbGuild.updateAllChanged();

            ctx.send("%sThe premium feature for guild %s (%s) was extended for %s days".formatted(
                    EmoteReference.CORRECT, guild, guildObject.getName(), days
            ));
        }
    }

    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    @Help(
            description = "Blacklists a user (user argument) or a guild (guild argument) by id.\n" +
                "Examples: `~>blacklist user add/remove 293884638101897216`, `~>blacklist guild add/remove 305408763915927552`",
            parameters = {
                    @Help.Parameter(name = "type", description = "Type of entity to (un)blacklist. Valid options are `guild` and `user`"),
                    @Help.Parameter(name = "action", description = "Action to perform. Valid options are `add` and `remove`"),
                    @Help.Parameter(name = "target", description = "ID of the entity to be (un)blacklisted")
            }
    )
    public static class Blacklist extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            ctx.send(EmoteReference.ERROR + "Invalid type. (Valid: guild, user)");
        }

        @SuppressWarnings("unused")
        private abstract static class BlacklistCommand<T> extends TextCommand {
            private final String type;
            private final Function<MantaroObject, List<String>> dbGetter;
            private final BiFunction<ShardManager, String, T> entityGetter;
            private final Function<T, String> formatter;

            private BlacklistCommand(String type, Function<MantaroObject, List<String>> dbGetter,
                                     BiFunction<ShardManager, String, T> entityGetter, Function<T, String> formatter) {
                this.type = type;
                this.dbGetter = dbGetter;
                this.entityGetter = entityGetter;
                this.formatter = formatter;
            }

            @Override
            protected void process(TextContext ctx) {
                var action = ctx.argument(Parsers.string());
                var target = ctx.argument(Parsers.string());
                var obj = MantaroData.db().getMantaroData();
                var shardManager = ctx.getShardManager();
                switch(action) {
                    case "add" -> {
                        var entity = entityGetter.apply(shardManager, target);
                        if(entity == null) {
                            ctx.send(EmoteReference.ERROR + type + ": I can't find this.");
                            return;
                        }

                        var list = dbGetter.apply(obj);
                        if (list.contains(target)) {
                            ctx.send(EmoteReference.ERROR + " " + "This person is already blacklisted.");
                            return;
                        }

                        list.add(target);
                        ctx.send(EmoteReference.CORRECT + "Blacklisted " + type + ": " + formatter.apply(entity));
                        obj.insertOrReplace();
                    }
                    case "remove" -> {
                        var list = dbGetter.apply(obj);
                        if (!list.contains(target)) {
                            ctx.send(EmoteReference.ERROR + type + " is not blacklisted?");
                            return;
                        }

                        // Somehow there's dupes.
                        var toRemove = list.stream().filter(s -> s.equals(target)).toList();
                        toRemove.forEach(list::remove);
                        ctx.send(EmoteReference.CORRECT + "Un-blacklisted " + type + ": " + target);
                        obj.insertOrReplace();
                    }
                    default -> ctx.send("Invalid scope. (Valid: add, remove)");
                }
            }
        }

        @SuppressWarnings("unused")
        public static class Guild extends BlacklistCommand<net.dv8tion.jda.api.entities.Guild> {
            public Guild() {
                super("Guild",
                        MantaroObject::getBlackListedGuilds,
                        ShardManager::getGuildById,
                        Objects::toString
                );
            }
        }

        @SuppressWarnings("unused")
        public static class User extends BlacklistCommand<net.dv8tion.jda.api.entities.User> {
            public User() {
                super("User",
                        MantaroObject::getBlackListedUsers,
                        (manager, str) -> manager.retrieveUserById(str).complete(),
                        user -> Utils.getTagOrDisplay(user) + " - " + user.getIdLong()
                );
            }
        }
    }

    @Name("eval")
    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class Eval extends TextCommand {
        MavenDependencies deps = new MavenDependencies(Path.of("eval_deps"))
                .addRepository("https://jcenter.bintray.com");
        JavaEvaluator evaluator = new JavaEvaluator(deps);
        Evaluator eval = (ctx, code) -> {
            var result = evaluator.compile("Eval",
                    """
                    import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
                    import net.kodehawa.mantarobot.core.modules.commands.base.Context;
                    import net.kodehawa.mantarobot.*;
                    import net.kodehawa.mantarobot.core.listeners.operations.*;
                    import net.kodehawa.mantarobot.data.*;
                    import net.kodehawa.mantarobot.db.*;
                    import net.kodehawa.mantarobot.db.entities.*;
                    import net.kodehawa.mantarobot.commands.*;
                    import net.kodehawa.mantarobot.commands.currency.*;
                    import net.kodehawa.mantarobot.commands.currency.item.*;
                    import net.kodehawa.mantarobot.commands.currency.item.special.*;
                    import net.kodehawa.mantarobot.commands.music.*;
                    import net.kodehawa.mantarobot.commands.custom.*;
                    import net.kodehawa.mantarobot.utils.*;
                    import net.kodehawa.mantarobot.utils.eval.*;
                    import net.dv8tion.jda.api.entities.*;
                    import net.dv8tion.jda.api.entities.channel.*;
                    import net.dv8tion.jda.api.entities.channel.attribute.*;
                    import net.dv8tion.jda.api.entities.channel.middleman.*;
                    import net.dv8tion.jda.api.entities.channel.concrete.*;
                    import java.util.*;
                    import java.util.stream.*;
                    import java.util.function.*;
                    import java.lang.reflect.*;
                    import java.lang.management.*;

                    public class Eval {
                        public static Object run(Context ctx, MavenDependencies maven) throws Throwable {
                            try {
                                return null;
                            } finally {
                                """ + (code + ";").replaceAll(";{2,}", ";") + """
                            }
                        }
                    }
                    """
            );

            if(!result.isSuccessful()) {
                var sb = new StringBuilder("\n");
                if(result.output() != null) {
                    sb.append(result.output()).append("\n");
                }

                for(var diag : result.diagnostics()) {
                    sb.append(diag).append("\n");
                }

                return new Error(sb.toString()) {
                    @Override
                    public String toString() {
                        return getMessage();
                    }
                };
            }

            try {
                return result.resultingClass().getMethod("run", TextContext.class, MavenDependencies.class)
                        .invoke(null, ctx, deps);
            } catch(InvocationTargetException e) {
                return e.getCause();
            } catch(Exception e) {
                return e;
            }
        };

        @Override
        protected void process(TextContext ctx) {
            var content = ctx.argument(Parsers.remainingContent(), "Give me something to eval.", "Failed to parse eval string.").trim();
            // eval.eval, yes
            var result = eval.eval(ctx, content);
            var errored = result instanceof Throwable;

            ctx.send(new EmbedBuilder()
                    .setAuthor(
                            "Evaluated " + (errored ? "and errored" : "with success"),
                            null,
                            ctx.getAuthor().getAvatarUrl()
                    )
                    .setColor(errored ? Color.RED : Color.GREEN)
                    .setDescription(result == null ?
                            "Executed successfully with no objects returned" :
                            ("Executed " + (errored ? "and errored: " : "successfully and returned: ") +
                                    // We need to codeblock this as the compiler output expects a monospace font
                                    // More ternary hell, but it's w/e
                                    (errored ? "```\n%s```".formatted(result.toString()) : result.toString())
                            )
                    ).setFooter(
                            "Asked by: " + ctx.getAuthor().getName(),
                            null
                    ).build()
            );
        }
    }

    @Name("link")
    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class Link extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            final var config = ctx.getConfig();
            if (!config.isPremiumBot()) {
                ctx.send("This command can only be ran in MP, as it'll link a guild to an MP holder.");
                return;
            }

            var userId = ctx.argument(Parsers.strictLong(), "Missing user id.", "Couldn't parse user id.");
            var guildId = ctx.argument(Parsers.strictLong(), "Missing guild id.", "Couldn't parse guild id.");
            var user = ctx.retrieveUserById(userId);
            var guild = MantaroBot.getInstance().getShardManager().getGuildById(guildId);

            if (guild == null || user == null) {
                ctx.send("User or guild not found.");
                return;
            }

            final var dbGuild = MantaroData.db().getGuild(String.valueOf(guildId));

            var unlink = ctx.tryArgument(Parsers.matching("^-u$"));
            if (unlink.isPresent()) {
                dbGuild.mpLinkedTo(null);
                dbGuild.updateAllChanged();

                ctx.sendFormat("Un-linked MP for guild %s (%s).", guild.getName(), guild.getId());
                return;
            }

            var pledgeInfo = APIUtils.getFullPledgeInformation(user.getId());

            // Guaranteed to be an integer
            if (pledgeInfo == null || !pledgeInfo.isActive() || pledgeInfo.getReward().getKeyAmount() < 3) {
                ctx.send("Pledge not found, pledge amount not enough or pledge was cancelled.");
                return;
            }

            //Guild assignment.
            dbGuild.mpLinkedTo(String.valueOf(userId)); // Patreon check will run from this user.
            dbGuild.updateAllChanged();

            ctx.sendFormat("Linked MP for guild %s (%s) to user %s (%s). Including this guild in pledge check (id -> user -> pledge). User tier: %s",
                    guild.getName(), guild.getId(), user.getName(), user.getId(), pledgeInfo.getReward()
            );

        }
    }

    @Name("invalidatekey")
    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class InvalidateKey extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            var keyString = ctx.argument(Parsers.string(), "Give me a key to invalidate.", "Couldn't parse key.");
            var key = MantaroData.db().getPremiumKey(keyString);
            if (key == null) {
                ctx.send("Invalid key.");
                return;
            }

            var dbUser = MantaroData.db().getUser(key.getOwner());
            dbUser.removeKeyClaimed(dbUser.getUserIdFromKeyId(key.getId()));
            dbUser.updateAllChanged();
            key.delete();

            ctx.send("Invalidated key " + keyString);
        }
    }

    @Name("createkey")
    @Permission(CommandPermission.OWNER)
    @Category(CommandCategory.OWNER)
    public static class CreateKey extends TextCommand {
        @Override
        protected void process(TextContext ctx) {
            var scope = ctx.argument(Parsers.toEnum(PremiumKey.Type.class), "Missing scope (Valid ones are: `user` or `guild`)", "Invalid scope (Valid ones are: `user` or `guild`)");
            var owner = ctx.argument(Parsers.strictLong(), "Missing owner id.", "Failed to parse owner.");
            var linked = ctx.argument(Parsers.bool(), "Missing linked.", "Failed to parse linked.");
            var mobile = ctx.tryArgument(Parsers.matching("^mobile$"));

            //This method generates a premium key AND saves it on the database! Please use this result!
            var generated = PremiumKey.generatePremiumKey(String.valueOf(owner), scope, linked);
            if (mobile.isPresent()) {
                ctx.send(generated.getId());
            } else {
                ctx.send(EmoteReference.CORRECT + String.format("Generated: `%s` (S: %s) **[NOT ACTIVATED]** (Linked: %s)",
                        generated.getId(), generated.getParsedType(), linked));
            }
        }
    }

    private interface Evaluator {
        Object eval(TextContext ctx, String code);
    }
}
