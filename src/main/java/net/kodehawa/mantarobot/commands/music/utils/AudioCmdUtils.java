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

package net.kodehawa.mantarobot.commands.music.utils;

import dev.arbjerg.lavalink.client.Link;
import dev.arbjerg.lavalink.client.LinkState;
import dev.arbjerg.lavalink.client.protocol.Track;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.music.GuildMusicManager;
import net.kodehawa.mantarobot.core.command.slash.SlashContext;
import net.kodehawa.mantarobot.core.command.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.IntIntObjectFunction;
import net.kodehawa.mantarobot.utils.StringUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.DiscordUtils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class AudioCmdUtils {
    private static final Logger log = LoggerFactory.getLogger(AudioCmdUtils.class);
    private static final String icon = "https://apiv2.mantaro.site/image/common/musical-note.png";

    public static void embedForQueue(SlashContext ctx, GuildMusicManager musicManager, I18nContext lang) {
        final var guild = ctx.getGuild();
        final var selfMember = ctx.getSelfMember();
        final var trackScheduler = musicManager.getTrackScheduler();
        final var toSend = getQueueList(trackScheduler.getQueue());
        final var musicPlayer = trackScheduler.getMusicPlayer();
        final var player = musicPlayer.block(Duration.ofMillis(300));
        final var playingTrack = player.getTrack();

        var nowPlaying = "";
        if (playingTrack == null) {
            nowPlaying = lang.get("commands.music_general.queue.no_track_found_np");
        } else {
            Member dj = null;
            var userData = playingTrack.getUserData(TrackData.class);
            if (userData != null && userData.userId() != null) {
                try {
                    dj = guild.retrieveMemberById(userData.userId()).useCache(true).complete();
                } catch (Exception ignored) { }
            }

            nowPlaying = String.format("**%s** (%s)%n%s",
                    MarkdownSanitizer.sanitize(playingTrack.getInfo().getTitle()),
                    getDurationMinutes(playingTrack.getInfo().getLength()),
                    dj != null ? lang.get("commands.music_general.queue.dj_np") + dj.getUser().getName() : ""
            );
        }

        if (toSend.isEmpty()) {
            ctx.reply(new EmbedBuilder()
                    .setAuthor(
                            String.format(lang.get("commands.music_general.queue.header"), guild.getName()),
                            null, guild.getIconUrl()
                    )
                    .setColor(Color.CYAN)
                    .setDescription(
                            lang.get("commands.music_general.queue.nothing_playing") + "\n\n" +
                            lang.get("commands.music_general.queue.nothing_playing_2")
                    )
                    .addField(EmoteReference.SATELLITE.toHeaderString() + lang.get("commands.music_general.queue.np"), nowPlaying, false)
                    .setThumbnail(icon).build()
            );

            return;
        }

        var length = trackScheduler.getQueue().stream()
                .filter(track -> track.getInfo().getLength() != Long.MAX_VALUE)
                .mapToLong(value -> value.getInfo().getLength()).sum();
        AudioChannel voiceChannel = null;
        if (selfMember.getVoiceState() != null) {
            voiceChannel = selfMember.getVoiceState().getChannel();
        }

        var builder = new EmbedBuilder()
                .setAuthor(String.format(lang.get("commands.music_general.queue.header"),
                        guild.getName()), null, guild.getIconUrl())
                .setColor(Color.CYAN);

        // error: local variables referenced from a lambda expression must be final or effectively final
        // sob
        final var np = nowPlaying;
        AudioChannel finalVoiceChannel = voiceChannel;
        IntIntObjectFunction<EmbedBuilder> supplier = (p, total) ->{
            // Cursed, but should work?
            // Fields were getting duplicated since the supplier was called everytime
            // obviously, but we need a clean field state here.
            // So just reset it.
            builder.clearFields();

            // Build the queue embed.
            // Description is then added on DiscordUtils.list/listText, as we have to
            // split it.
            return builder.setThumbnail(icon)
                    .addField(EmoteReference.SATELLITE.toHeaderString() + lang.get("commands.music_general.queue.np"), np, false)
                    .addField(EmoteReference.CLOCK.toHeaderString() + lang.get("commands.music_general.queue.total_queue_time"),
                            Utils.formatDuration(lang, length),
                            false
                    )
                    .addField(EmoteReference.STAR.toHeaderString() + lang.get("commands.music_general.queue.total_size"),
                            String.format("%d %s",
                                    trackScheduler.getQueue().size(),
                                    lang.get("commands.music_general.queue.songs")
                            ),
                            true
                    )
                    .addField(EmoteReference.SLIDER.toHeaderString() + lang.get("commands.music_general.queue.togglers"),
                        String.format("`%s / %s`", trackScheduler.getRepeatMode() == null ? "false" :
                             trackScheduler.getRepeatMode(), player.getPaused()),
                            true
                    )
                    .addField(EmoteReference.MEGA.toHeaderString() + lang.get("commands.music_general.queue.playing_in"),
                            finalVoiceChannel == null ? lang.get("commands.music_general.queue.no_channel") : finalVoiceChannel.getName(),
                            true
                    )
                    .setFooter(String.format("Total Pages: %s | Current: %s", total, p),
                            ctx.getAuthor().getEffectiveAvatarUrl());
        };

        // Too long otherwise, so substract 800 from TEXT_MAX_LENGTH
        var split = DiscordUtils.divideString(MessageEmbed.TEXT_MAX_LENGTH - 800, toSend);
        DiscordUtils.listButtons(ctx.getUtilsContext(), 150, supplier, split);
    }

    public static CompletionStage<Boolean> openAudioConnection(SlashContext ctx, Link link,
                                                            AudioChannel userChannel, I18nContext lang) {
        final var userChannelMembers = userChannel.getMembers();
        Member selfMember = ctx.getGuild().getSelfMember();

        if (userChannel instanceof VoiceChannel vc) {
            if (vc.getUserLimit() <= userChannelMembers.size()
                    && vc.getUserLimit() > 0 && !selfMember.hasPermission(Permission.MANAGE_CHANNEL)) {
                ctx.edit("commands.music_general.connect.full_channel", EmoteReference.ERROR);
                return completedFuture(false);
            }
        }

        try {
            // Stage channel support
            if (userChannel instanceof StageChannel channel) {
                if (!selfMember.hasPermission(Permission.REQUEST_TO_SPEAK)) {
                    ctx.edit("commands.music_general.connect.missing_permissions_connect",
                            EmoteReference.ERROR, lang.get("discord_permissions.voice_connect")
                    );
                    return completedFuture(false);
                }

                var stageInstance = channel.getStageInstance();
                if (stageInstance == null) {
                    ctx.edit("commands.music_general.connect.no_stage_here", EmoteReference.ERROR);
                    return completedFuture(false);
                }

                ctx.edit("commands.music_general.connect.success_stage", EmoteReference.MEGA, userChannel.getName());
            } else {
                // Not a stage channel, so we don't need to do much.
                ctx.edit("commands.music_general.connect.success", EmoteReference.MEGA, userChannel.getName());
            }

            try {
                joinVoiceChannel(link, userChannel);
            } catch (Exception e) {
                e.printStackTrace();
                ctx.edit("commands.music_general.connect.error", EmoteReference.ERROR);
                return completedFuture(false);
            }

            return completedFuture(true);
        } catch (NullPointerException e) {
            ctx.edit("commands.music_general.connect.non_existent_channel", EmoteReference.ERROR);

            //Reset custom channel.
            var dbGuild = MantaroData.db().getGuild(ctx.getGuild());
            if (dbGuild.getMusicChannel() != null) {
                dbGuild.musicChannel(null);
                dbGuild.updateAllChanged();
            }

            // Return as false, returning exceptionally here could fail in the handling we have done
            // even if it's the right thing to do.
            return completedFuture(false);
        }
    }

    public static CompletionStage<Boolean> connectToVoiceChannel(SlashContext ctx, I18nContext lang) {
        if (ctx.getMember().getVoiceState() == null) {
            ctx.edit("commands.music_general.connect.user_no_vc", EmoteReference.ERROR);
            return completedFuture(false);
        }

        final var voiceChannel = ctx.getMember().getVoiceState().getChannel();
        final var guild = ctx.getGuild();
        final var selfMember = guild.getSelfMember();
        final var guildData = MantaroData.db().getGuild(guild);

        // I can't see you in any VC here?
        if (voiceChannel == null) {
            ctx.edit("commands.music_general.connect.user_no_vc", EmoteReference.ERROR);
            return completedFuture(false);
        }

        // Can't connect to this channel
        if (!selfMember.hasPermission(voiceChannel, Permission.VOICE_CONNECT)) {
            ctx.edit("commands.music_general.connect.missing_permissions_connect",
                    EmoteReference.ERROR, lang.get("discord_permissions.voice_connect"));
            return completedFuture(false);
        }

        // Can't speak on this channel
        if (!selfMember.hasPermission(voiceChannel, Permission.VOICE_SPEAK)) {
            ctx.edit(
                    "commands.music_general.connect.missing_permission_speak",
                    EmoteReference.ERROR, lang.get("discord_permissions.voice_speak")
            );

            return completedFuture(false);
        }

        // Set the custom guild music channel from the db value
        AudioChannel guildMusicChannel = null;
        if (guildData.getMusicChannel() != null) {
            guildMusicChannel = guild.getChannelById(AudioChannel.class, guildData.getMusicChannel());
        }

        final var link = MantaroBot.getInstance().getAudioManager().getMusicManager(guild).getLavaLink();
        final var linkState = link.getState();

        // Cursed lavalink issues tracker.
        var cursed = false;
        if (guildMusicChannel != null) {
            // If the channel is not the set one, reject this connect.
            if (!voiceChannel.equals(guildMusicChannel)) {
                ctx.edit("commands.music_general.connect.channel_locked", EmoteReference.ERROR, guildMusicChannel.getName());
                return completedFuture(false);
            }

            // If the link is not currently connected or connecting, accept connection and call openAudioConnection
            if (linkState != LinkState.CONNECTED && linkState != LinkState.CONNECTING) {
                return openAudioConnection(ctx, link, voiceChannel, lang);
            }

            // Nothing to connect to, but pass true so we can load the song (for example, it's already connected)
            return completedFuture(true);
        }

        var voiceState = selfMember.getVoiceState();
        // Assume last channel it's the one it was attempting to connect to? (on the one below this too)
        // If the link is CONNECTED and the lastChannel is not the one it's already connected to, reject connection
        if (linkState == LinkState.CONNECTED && voiceState != null && voiceState.getChannel() != null &&
                !voiceState.getChannel().getId().equals(voiceChannel.getId())) {
            var vc = guild.getChannelById(AudioChannel.class, voiceState.getChannel().getId());

            // Workaround for a bug in lavalink that gives us Link.State.CONNECTED and a channel that doesn't exist anymore.
            // This is a little cursed.
            if (vc != null) {
                ctx.edit("commands.music_general.connect.already_connected", EmoteReference.WARNING, vc.getName());
                return completedFuture(false);
            } else {
                cursed = true;
            }
        }

        // If the link is CONNECTING and the lastChannel is not the one it's already connected to, reject connection
        if (linkState == LinkState.CONNECTING && voiceState != null && voiceState.getChannel() != null &&
                !voiceState.getId().equals(voiceChannel.getId())) {
            var vc = guild.getChannelById(AudioChannel.class, voiceState.getChannel().getId());

            // Workaround for a bug in lavalink that gives us Link.State.CONNECTING and a channel that doesn't exist anymore.
            // This is a little cursed.
            if (vc != null) {
                ctx.edit("commands.music_general.connect.attempting_to_connect", EmoteReference.ERROR, vc.getName());
                return completedFuture(false);
            } else {
                cursed = true;
            }
        }

        // If the link is not currently connected or connecting, accept connection and call openAudioConnection
        if ((linkState != LinkState.CONNECTED && linkState != LinkState.CONNECTING) || cursed) {
            if (cursed) {
                log.debug("We seemed to hit a Lavalink/JDA bug? Null voice channel, but {} state.", linkState);
            }

            return openAudioConnection(ctx, link, voiceChannel, lang);
        }

        // Nothing to connect to, but pass true so we can load the song (for example, it's already connected)
        return completedFuture(true);
    }

    private static void joinVoiceChannel(Link manager, AudioChannel channel) {
        channel.getJDA().getDirectAudioController().connect(channel);
    }

    public static String getDurationMinutes(long length) {
        return String.format("%d:%02d",
                MILLISECONDS.toMinutes(length),
                MILLISECONDS.toSeconds(length) - MINUTES.toSeconds(MILLISECONDS.toMinutes(length))
        );
    }

    public static String getQueueList(Deque<Track> queue) {
        var sb = new StringBuilder();
        var num = 1;

        for (var audioTrack : queue) {
            var aDuration = audioTrack.getInfo().getLength();

            var duration = String.format("%02d:%02d",
                    MILLISECONDS.toMinutes(aDuration),
                    MILLISECONDS.toSeconds(aDuration) - MINUTES.toSeconds(MILLISECONDS.toMinutes(aDuration))
            );

            sb.append("""
                    %s**%,d.** [%s] **%s**
                    """.formatted(EmoteReference.BLUE_SMALL_MARKER,
                    num, duration,
                    formatTitle(audioTrack.getInfo().getTitle()))
            );

            num++;
        }
        return sb.toString();
    }

    private static String formatTitle(String title) {
        // Sanitizing markdown doesn't remove [ and ], and that breaks queue.
        return MarkdownSanitizer.sanitize(StringUtils.limit(title, 33))
                .replace("[", "")
                .replace("]", "")
                .strip();
    }
}
