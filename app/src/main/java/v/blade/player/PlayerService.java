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
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import v.blade.library.Song;

import java.util.ArrayList;
import java.util.Random;

public class PlayerService extends Service
{
    private static final String TAG = PlayerService.class.getSimpleName();
    private final IBinder binder = new PlayerBinder();
    private boolean mServiceStarted = false;

    private ArrayList<Song> currentPlaylist;
    private int currentPosition;
    private int repeatMode = PlaybackStateCompat.REPEAT_MODE_NONE;
    private boolean shuffleMode = false;

    private PlayerNotification mNotificationManager;
    private Notification mNotification;
    private PlayerMediaPlayer mPlayer;
    private final PlayerMediaPlayer.MediaPlayerListener mPlayerListener = new PlayerMediaPlayer.MediaPlayerListener()
    {
        @Override
        public void onStateChange()
        {
            /* we reached song end ; go to new song */
            if(mPlayer.getCurrentState() == PlayerMediaPlayer.PLAYER_STATE_SONGEND)
            {
                if(repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE)
                {
                    mSessionCallback.onPlay();
                }
                else if((repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL) || (currentPosition != currentPlaylist.size()-1))
                {
                    mSessionCallback.onSkipToNext();
                    return;
                }
                else mPlayer.setPlaylistEnded();
            }

            /* send current playbackstate to mediasession */
            mSession.setPlaybackState(mPlayer.getPlaybackState());

            MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentPlaylist.get(currentPosition).getAlbum().getAlbumArt());
            MediaMetadataCompat metadata = builder.build();
            mSession.setMetadata(metadata);

            /* update notification */
            if(mNotification == null)
            {
                if(!mServiceStarted)
                {
                    if(Build.VERSION.SDK_INT >= 26) startForegroundService(new Intent(PlayerService.this, PlayerService.class));
                    else startService(new Intent(PlayerService.this, PlayerService.class));
                    mServiceStarted = true;
                }
                mNotification = mNotificationManager.getNotification(getCurrentSong(), mPlayer.getCurrentState(), mSession.getSessionToken());
                startForeground(PlayerNotification.NOTIFICATION_ID, mNotification);
            }
            else
            {
                stopForeground(false);
                mNotification = mNotificationManager.getNotification(getCurrentSong(), mPlayer.getCurrentState(), mSession.getSessionToken());
                mNotificationManager.getNotificationManager().notify(PlayerNotification.NOTIFICATION_ID, mNotification);
            }
        }
    };

    /* MediaSession */
    private MediaSessionCompat mSession;
    private MediaSessionCompat.Callback mSessionCallback = new MediaSessionCompat.Callback()
    {
        @Override
        public void onPlay()
        {
            if(mPlayer.getCurrentState() == PlayerMediaPlayer.PLAYER_STATE_DO_NOTHING
                    || mPlayer.getCurrentState() == PlayerMediaPlayer.PLAYER_STATE_STOPPED)
            {
                currentPosition = 0;
                mPlayer.playSong(currentPlaylist.get(currentPosition));
            }
            else mPlayer.play();
        }

        @Override
        public void onSkipToQueueItem(long id)
        {
            currentPosition = (int) id;
        }

        @Override
        public void onPause()
        {
            mPlayer.pause();
        }

        @Override
        public void onSkipToNext()
        {
            if(shuffleMode) currentPosition = new Random().nextInt(currentPlaylist.size()-1);
            else currentPosition = (++currentPosition % currentPlaylist.size());
            mPlayer.playSong(currentPlaylist.get(currentPosition));
        }

        @Override
        public void onSkipToPrevious()
        {
            currentPosition = currentPosition > 0 ? currentPosition-1 : currentPlaylist.size()-1;
            mPlayer.playSong(currentPlaylist.get(currentPosition));
        }

        @Override
        public void onRewind()
        {
            super.onRewind();
        }

        @Override
        public void onStop()
        {
            mPlayer.mediaPlayer.stop();
            mSession.setActive(false);
        }

        @Override
        public void onSeekTo(long pos)
        {
            mPlayer.mediaPlayer.seekTo((int) pos);
        }

        @Override
        public void onSetRepeatMode(int repeatMode) {PlayerService.this.repeatMode = repeatMode;}

        @Override
        public void onSetShuffleMode(int shuffleMode)
        {
            PlayerService.this.shuffleMode = !PlayerService.this.shuffleMode;
        }
    };

    @Override
    public void onCreate()
    {
        super.onCreate();

        // init media session
        mSession = new MediaSessionCompat(this, TAG);
        mSession.setCallback(mSessionCallback);
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        // init notification
        mNotificationManager = new PlayerNotification(this);

        // init media player
        mPlayer = new PlayerMediaPlayer(this, mPlayerListener);
    }

    /* service binding */
    public class PlayerBinder extends Binder
    {
        public PlayerService getService() {return PlayerService.this;}
    }
    @Override
    public IBinder onBind(Intent intent)
    {
        return binder;
    }

    /* interactions with activities */
    public void setCurrentPlaylist(ArrayList<Song> playlist, int position)
    {currentPlaylist = playlist; currentPosition = position;mPlayer.playSong(currentPlaylist.get(currentPosition));}
    public void addToPlaylist(ArrayList<Song> toAdd) {currentPlaylist.addAll(toAdd);}
    public void addNextToPlaylist(ArrayList<Song> toAdd) {currentPlaylist.addAll(currentPosition+1, toAdd);}
    public void setCurrentPosition(int position)
    {currentPosition = position; mPlayer.playSong(currentPlaylist.get(currentPosition));}
    public ArrayList<Song> getCurrentPlaylist() {return currentPlaylist;}
    public int getCurrentPosition() {return currentPosition;}
    public Song getCurrentSong() {return currentPlaylist.get(currentPosition);}
    public boolean isPlaying() {return mPlayer.mediaPlayer.isPlaying();}
    public boolean isShuffleEnabled() {return shuffleMode;}
    public int getRepeatMode() {return repeatMode;}
    public PlaybackStateCompat getPlayerState() {return mPlayer.getPlaybackState();}
    public MediaSessionCompat.Token getSessionToken() {return mSession.getSessionToken();}
    public int resolveCurrentSongDuration() {return mPlayer.mediaPlayer.getDuration();}
    public int resolveCurrentSongPosition() {return mPlayer.mediaPlayer.getCurrentPosition();}
    public void seekTo(int position) {mPlayer.mediaPlayer.seekTo(position);}
    public void updatePosition(int position) {this.currentPosition = position;}

    /* handling MediaSession intents */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        MediaButtonReceiver.handleIntent(mSession, intent);
        return super.onStartCommand(intent, flags, startId);
    }
}
