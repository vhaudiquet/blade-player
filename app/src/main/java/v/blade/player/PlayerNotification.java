/*
 *    Blade - Android music player
 *    Copyright (C) 2018 Valentin HAUDIQUET
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package v.blade.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import v.blade.R;
import v.blade.library.Song;
import v.blade.ui.PlayActivity;
import v.blade.ui.settings.ThemesActivity;

public class PlayerNotification
{
    public static final int NOTIFICATION_ID = 0x42;
    private static final int REQUEST_CODE = 501;
    private static final String CHANNEL_ID = "v.blade.mediachannel";

    private final PlayerService mService;

    private final NotificationManagerCompat mNotificationManager;
    //notification actions
    private final NotificationCompat.Action mPlayAction;
    private final NotificationCompat.Action mPauseAction;
    private final NotificationCompat.Action mNextAction;
    private final NotificationCompat.Action mPrevAction;

    PlayerNotification(PlayerService service)
    {
        this.mService = service;

        mNotificationManager = NotificationManagerCompat.from(service);

        mPlayAction = new NotificationCompat.Action(R.drawable.play_arrow, mService.getString(R.string.play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(mService, PlaybackStateCompat.ACTION_PLAY));
        mPauseAction = new NotificationCompat.Action(R.drawable.pause_notif, mService.getString(R.string.pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(mService, PlaybackStateCompat.ACTION_PAUSE));
        mNextAction = new NotificationCompat.Action(R.drawable.next_arrow_notif, mService.getString(R.string.next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(mService, PlaybackStateCompat.ACTION_SKIP_TO_NEXT));
        mPrevAction = new NotificationCompat.Action(R.drawable.prev_arrow_notif, mService.getString(R.string.prev),
                MediaButtonReceiver.buildMediaButtonPendingIntent(mService, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS));

        mNotificationManager.cancelAll();
    }

    public NotificationManagerCompat getNotificationManager() {return mNotificationManager;}

    public Notification getNotification(Song song, int playerState, MediaSessionCompat.Token token)
    {
        NotificationCompat.Builder builder = buildNotification(playerState, token, song);
        return builder.build();
    }

    private NotificationCompat.Builder buildNotification(int playerState, MediaSessionCompat.Token token, Song playing)
    {
        if(Build.VERSION.SDK_INT >= 26) createChannel();

        boolean isPlaying = (playerState == PlayerMediaPlayer.PLAYER_STATE_PLAYING);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(mService, CHANNEL_ID);

        Intent openUI = new Intent(mService, PlayActivity.class);
        openUI.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(mService, REQUEST_CODE, openUI, PendingIntent.FLAG_CANCEL_CURRENT);

        MediaStyle style = new MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(mService, PlaybackStateCompat.ACTION_STOP));

        builder.setStyle(style)
                .setWhen(0) //remove 'now' text on notification top
                //.setSubText("") //text between 'Blade' and 'Now' (notification top)
                .setColor(ContextCompat.getColor(mService, ThemesActivity.currentColorPrimary))
                .setSmallIcon(R.drawable.app_icon_notif) //icon that will be displayed in status bar
                .setContentIntent(contentIntent) //intent that will be sent on notification click
                .setLargeIcon(mService.getCurrentArt() == null ? BitmapFactory.decodeResource(mService.getResources(), R.drawable.ic_albums) : mService.getCurrentArt())
                .setContentTitle(playing.getTitle())
                .setContentText(playing.getArtist().getName() + " - " + playing.getAlbum().getName())
                .setDeleteIntent(null) //intent on notification slide
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying); //disable swipe delete if playing

        builder.addAction(mPrevAction);
        builder.addAction(isPlaying ? mPauseAction : mPlayAction);
        builder.addAction(mNextAction);

        return builder;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createChannel()
    {
        NotificationManager mNotificationManager = (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);
        // The id of the channel.
        // The user-visible name of the channel.
        CharSequence name = "Media playback";
        // The user-visible description of the channel.
        String description = "Media playback controls";
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel mChannel = new NotificationChannel(CHANNEL_ID, name, importance);
        // Configure the notification channel.
        mChannel.setDescription(description);
        mChannel.setShowBadge(false);
        mChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        mNotificationManager.createNotificationChannel(mChannel);
    }
}
