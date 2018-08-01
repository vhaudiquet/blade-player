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
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import v.blade.library.Song;

import java.util.ArrayList;
import java.util.Collections;

public class PlayerService extends Service
{
    private static final String TAG = PlayerService.class.getSimpleName();
    private final IBinder binder = new PlayerBinder();

    private ArrayList<Song> currentPlaylist;
    private int currentPosition;
    private Bitmap currentArt;
    private int repeatMode = PlaybackStateCompat.REPEAT_MODE_NONE;
    private boolean shuffleMode = false;
    private ArrayList<Song> shufflePlaylist;

    private PlayerNotification mNotificationManager;
    private Notification mNotification;
    private PlayerMediaPlayer mPlayer;
    private final PlayerMediaPlayer.MediaPlayerListener mPlayerListener = new PlayerMediaPlayer.MediaPlayerListener()
    {
        @Override
        public void onStateChange()
        {
            if(currentPlaylist == null) return;

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

            /* send current state to mediasession */

            //send PlaybackState
            mSession.setPlaybackState(mPlayer.getPlaybackState());

            //build and send current media informations
            MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();
            Song currentSong = getCurrentSong();
            currentArt = currentSong.getAlbum().getArt();
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArt);
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArt);
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, currentSong.getAlbum().getArtUri() == null ? null : currentSong.getAlbum().getArtUri().toString());
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, currentSong.getAlbum().getArtUri() == null ? null : currentSong.getAlbum().getArtUri().toString());
            builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.getArtist().getName());
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.getAlbum().getName());
            builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.getTitle());
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentSong.getTitle());
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentSong.getArtist().getName() + " - " + currentSong.getAlbum().getName());
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mPlayer.getDuration());
            MediaMetadataCompat metadata = builder.build();
            mSession.setMetadata(metadata);

            //send shuffle/repeat infos
            mSession.setShuffleMode(shuffleMode ? PlaybackStateCompat.SHUFFLE_MODE_NONE : PlaybackStateCompat.SHUFFLE_MODE_ALL);
            mSession.setRepeatMode(repeatMode);

            //set mediasession active if it was not
            if(!mSession.isActive()) mSession.setActive(true);

            /* update notification */
            if(mNotification == null)
            {
                mNotification = mNotificationManager.getNotification(currentSong, mPlayer.getCurrentState(), mSession.getSessionToken());
                startForeground(PlayerNotification.NOTIFICATION_ID, mNotification);
            }
            else
            {
                stopForeground(false);
                mNotification = mNotificationManager.getNotification(currentSong, mPlayer.getCurrentState(), mSession.getSessionToken());
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
            if(mPlayer.getCurrentState() == PlayerMediaPlayer.PLAYER_STATE_DO_NOTHING)
            {
                currentPosition = (++currentPosition % currentPlaylist.size());
                mPlayer.playSong(shuffleMode ? shufflePlaylist.get(currentPosition) : currentPlaylist.get(currentPosition));
            }
            else if(mPlayer.getCurrentState() == PlayerMediaPlayer.PLAYER_STATE_STOPPED)
            {
                currentPosition = 0;
                mPlayer.playSong(shuffleMode ? shufflePlaylist.get(currentPosition) : currentPlaylist.get(currentPosition));
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
            currentPosition = (++currentPosition % currentPlaylist.size());
            mPlayer.playSong(shuffleMode ? shufflePlaylist.get(currentPosition) : currentPlaylist.get(currentPosition));
        }

        @Override
        public void onSkipToPrevious()
        {
            //if we are in song begin, we skip to previous
            if(mPlayer.getCurrentPosition() < 5000)
            {
                currentPosition = currentPosition > 0 ? currentPosition-1 : currentPlaylist.size()-1;
                mPlayer.playSong(shuffleMode ? shufflePlaylist.get(currentPosition) : currentPlaylist.get(currentPosition));
            }
            //else we seek to the song begin
            else
            {
                //reset state if playlist end (so that we dont go to begin)
                if(mPlayer.getCurrentState() == PlayerMediaPlayer.PLAYER_STATE_DO_NOTHING)
                    mPlayer.setCurrentState(PlayerMediaPlayer.PLAYER_STATE_PAUSED);

                mPlayer.seekTo(0);
            }
        }

        @Override
        public void onRewind()
        {
            super.onRewind();
        }

        @Override
        public void onStop()
        {
            //TODO : find a way to stop correctly (on intent reception, doesnt really stop)
            mPlayer.stop();
            mSession.setActive(false);

            //oreo+ : we need to kill all (can't let the service alive without notification)
            stopForeground(true);
            stopSelf();
        }

        @Override
        public void onSeekTo(long pos)
        {
            mPlayer.seekTo((int) pos);
        }

        @Override
        public void onSetRepeatMode(int repeatMode) {PlayerService.this.repeatMode = repeatMode;}

        @Override
        public void onSetShuffleMode(int shuffleMode)
        {
            if(!PlayerService.this.shuffleMode) // shuffle mode enabling, construct shuffle playlist
            {
                shufflePlaylist = (ArrayList<Song>) currentPlaylist.clone();
                Collections.shuffle(shufflePlaylist);
                shufflePlaylist.remove(currentPlaylist.get(currentPosition));
                shufflePlaylist.add(0, currentPlaylist.get(currentPosition));
                currentPosition = 0;
            }
            else // shuffle mode disable, retreive current position
            {
                int i;
                for(i = 0; i < currentPlaylist.size(); i++)
                {
                    if(currentPlaylist.get(i) == shufflePlaylist.get(currentPosition)) break;
                }
                currentPosition = i;
            }
            PlayerService.this.shuffleMode = !PlayerService.this.shuffleMode;
            mPlayer.listener.onStateChange();
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

    @Override
    public void onDestroy()
    {
        super.onDestroy();

        mPlayer.destroy();
        PlayerMediaPlayer.context = null;
        mSession.release();
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
    @Override
    public boolean onUnbind(Intent intent) {return true;}

    /* interactions with activities */
    public void setCurrentPlaylist(ArrayList<Song> playlist, int position)
    {currentPlaylist = playlist; currentPosition = position;mPlayer.playSong(currentPlaylist.get(currentPosition)); if(shuffleMode) shuffleMode = false;}
    public void addToPlaylist(ArrayList<Song> toAdd)
    {
        if(currentPlaylist == null)
        {
            currentPlaylist = new ArrayList<>();
            currentPlaylist.addAll(toAdd);
            mPlayer.playSong(getCurrentSong());
        }
        else currentPlaylist.addAll(toAdd);
        if(shuffleMode) shufflePlaylist.addAll(toAdd);
    }
    public void addNextToPlaylist(ArrayList<Song> toAdd)
    {
        if(currentPlaylist == null)
        {
            currentPlaylist = new ArrayList<>();
            currentPlaylist.addAll(0, toAdd);
            mPlayer.playSong(currentPlaylist.get(currentPosition));
        }
        else currentPlaylist.addAll(currentPosition+1, toAdd);
        if(shuffleMode) shufflePlaylist.addAll(currentPosition+1, toAdd);
    }
    public void setCurrentPosition(int position)
    {currentPosition = position; mPlayer.playSong(shuffleMode ? shufflePlaylist.get(currentPosition) : currentPlaylist.get(currentPosition));}
    public ArrayList<Song> getCurrentPlaylist() {return shuffleMode ? shufflePlaylist : currentPlaylist;}
    public int getCurrentPosition() {return currentPosition;}
    public Song getCurrentSong() {return currentPlaylist == null ? null : shuffleMode ? shufflePlaylist.get(currentPosition) : currentPlaylist.get(currentPosition);}
    public boolean isPlaying() {return mPlayer.isPlaying();}
    public boolean isShuffleEnabled() {return shuffleMode;}
    public int getRepeatMode() {return repeatMode;}
    public PlaybackStateCompat getPlayerState() {return mPlayer.getPlaybackState();}
    public MediaSessionCompat.Token getSessionToken() {return mSession.getSessionToken();}
    public int resolveCurrentSongDuration() {return mPlayer.getDuration();}
    public int resolveCurrentSongPosition() {return mPlayer.getCurrentPosition();}
    public void seekTo(int position) {mPlayer.seekTo(position);}
    public void updatePosition(int position) {this.currentPosition = position;}
    public Bitmap getCurrentArt() {return currentArt;}

    /* handling MediaSession / Service start intents */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        MediaButtonReceiver.handleIntent(mSession, intent);
        return START_STICKY;
    }
}
