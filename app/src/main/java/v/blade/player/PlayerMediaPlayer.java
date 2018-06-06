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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.media.session.PlaybackStateCompat;
import android.widget.Toast;
import v.blade.R;
import v.blade.library.Song;
import v.blade.library.Source;
import v.blade.library.SourcePlayer;

public class PlayerMediaPlayer
{
    private static final float MEDIA_VOLUME_DEFAULT = 1.0f;
    private static final float MEDIA_VOLUME_DUCK = 0.2f;
    private static final boolean PLAY_ON_AUDIOFOCUS = false;

    public static final int PLAYER_STATE_NONE = 0;
    public static final int PLAYER_STATE_PLAYING = 1;
    public static final int PLAYER_STATE_PAUSED = 2;
    public static final int PLAYER_STATE_SONGEND = 3;
    public static final int PLAYER_STATE_DO_NOTHING = 4;
    public static final int PLAYER_STATE_STOPPED = 5;
    private static int currentState = PLAYER_STATE_NONE;
    public static MediaPlayerListener listener;
    public static SourcePlayer.PlayerListener playerListener = new SourcePlayer.PlayerListener()
    {
        @Override
        public void onSongCompletion()
        {
            currentState = PLAYER_STATE_SONGEND;
            listener.onStateChange();
        }

        @Override
        public void onPlaybackError(String errMsg)
        {
            //TODO : find a way if error happens on already 'skipped' player
            Toast.makeText(context, context.getString(R.string.playback_error) + " : " + errMsg, Toast.LENGTH_SHORT).show();
            currentState = PLAYER_STATE_PAUSED;
            listener.onStateChange();
        }
    };

    private static SourcePlayer currentActivePlayer = null;

    private Song currentSong;
    private static Context context;

    private static final IntentFilter AUDIO_NOISY_INTENT_FILTER = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    private final BroadcastReceiver mAudioNoisyReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()))
            {
                if (isPlaying()) pause();
            }
        }
    };

    private final AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener()
    {
        @Override
        public void onAudioFocusChange(int focusChange)
        {
            switch(focusChange)
            {
                case AudioManager.AUDIOFOCUS_GAIN:
                    if(playOnAudioFocus && !isPlaying()) play();
                    else if(isPlaying()) setVolume(MEDIA_VOLUME_DEFAULT, MEDIA_VOLUME_DEFAULT);
                    playOnAudioFocus = PLAY_ON_AUDIOFOCUS;
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    //setVolume(MEDIA_VOLUME_DUCK, MEDIA_VOLUME_DUCK);
                    /* we don't want to 'duck' for now, so take the same action as AUDIOFOCUS_LOSS_TRANSIENT */
                    if(isPlaying()) {playOnAudioFocus = true; pause();}
                    break;

                /* We only lost audiofocus for a small ammount of time, relaunch player just after */
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if(isPlaying()) {playOnAudioFocus = true; pause();}
                    break;

                /* We lost audiofocus definetely ; maybe another player was started or ... */
                case AudioManager.AUDIOFOCUS_LOSS:
                    if(isPlaying()) pause();
                    break;
            }
        }
    };
    private boolean playOnAudioFocus = PLAY_ON_AUDIOFOCUS;

    public PlayerMediaPlayer(@NonNull final Context context, final MediaPlayerListener listener)
    {
        this.context = context;

        context.registerReceiver(mAudioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        this.listener = listener;
    }
    public void destroy()
    {
        context.unregisterReceiver(mAudioNoisyReceiver);
    }

    /* player operations */
    public void play()
    {
        if(requestAudioFocus())
        {
            currentActivePlayer.play(new SourcePlayer.PlayerCallback() {
                @Override
                public void onSucess()
                {
                    currentState = PLAYER_STATE_PLAYING;
                    listener.onStateChange();
                }

                @Override
                public void onFailure()
                {
                    Toast.makeText(context, context.getString(R.string.playback_error), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
    public void pause()
    {
        if(currentState == PLAYER_STATE_PAUSED) return;

        if(!playOnAudioFocus) audioManager.abandonAudioFocus(audioFocusChangeListener);

        currentActivePlayer.pause(new SourcePlayer.PlayerCallback()
        {
            @Override
            public void onSucess()
            {
                currentState = PLAYER_STATE_PAUSED;
                listener.onStateChange();
            }

            @Override
            public void onFailure()
            {
                Toast.makeText(context, context.getString(R.string.playback_pause_error), Toast.LENGTH_SHORT).show();
            }
        });
    }
    public void stop()
    {
        audioManager.abandonAudioFocus(audioFocusChangeListener);

        pause();

        //currentState = PLAYER_STATE_STOPPED;
        //listener.onStateChange();
    }
    public void seekTo(int msec)
    {
        if(currentActivePlayer != null) currentActivePlayer.seekTo(msec);
    }
    public int getCurrentPosition()
    {
        return (currentState == PLAYER_STATE_SONGEND || currentState == PLAYER_STATE_DO_NOTHING) ? getDuration() :
                currentActivePlayer == null ? 0 : currentActivePlayer.getCurrentPosition();
    }
    public boolean isPlaying()
    {
        return (currentState == PLAYER_STATE_PLAYING);
    }
    public int getDuration()
    {
        if(currentActivePlayer == Source.SOURCE_LOCAL_LIB.getPlayer())
            return currentActivePlayer.getDuration();
        return ((int) currentSong.getDuration());
    }
    public void setVolume(float left, float right)
    {
        /*
        if(currentActivePlayer == LOCAL_PLAYER_ACTIVE) mediaPlayer.setVolume(left, right);
        else if(currentActivePlayer == SPOTIFY_PLAYER_ACTIVE) return;
        else if(currentActivePlayer == DEEZER_PLAYER_ACTIVE) deezerPlayer.setStereoVolume(left, right);
        */
    }


    public void playSong(final Song song)
    {
        //oreo+ : we need to show notification as soon as first 'playSong()' is called (service start)
        listener.onStateChange();

        currentSong = song;

        if(currentActivePlayer != null && isPlaying()) currentActivePlayer.pause(null);

        /* select appropriate mediaplayer and start playback */
        currentActivePlayer = song.getSources().getSourceByPriority(0).getSource().getPlayer();

        if(requestAudioFocus())
        {
            currentActivePlayer.playSong(song, new SourcePlayer.PlayerCallback()
            {
                @Override
                public void onSucess()
                {
                    currentState = PLAYER_STATE_PLAYING;
                    listener.onStateChange();
                }

                @Override
                public void onFailure()
                {
                    Toast.makeText(context, context.getString(R.string.playback_error), Toast.LENGTH_SHORT).show();

                    currentState = PLAYER_STATE_PAUSED;
                    listener.onStateChange();
                }
            });
        }
    }

    private boolean requestAudioFocus()
    {
        final int result = audioManager.requestAudioFocus(audioFocusChangeListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /* state change listener */
    public int getCurrentState() {return currentState;}
    public void setCurrentState(int state) {currentState = state; listener.onStateChange();}
    public PlaybackStateCompat getPlaybackState()
    {
        long actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;

        int playbackState = 0;
        switch(currentState)
        {
            case PLAYER_STATE_PAUSED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_SEEK_TO;
                playbackState = PlaybackStateCompat.STATE_PAUSED;
                break;

            case PLAYER_STATE_PLAYING:
                actions |= PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_SEEK_TO;
                playbackState = PlaybackStateCompat.STATE_PLAYING;
                break;

            case PLAYER_STATE_STOPPED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE;
                playbackState = PlaybackStateCompat.STATE_STOPPED;
                break;

            case PLAYER_STATE_NONE:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE;
                playbackState = PlaybackStateCompat.STATE_STOPPED;
                break;

            case PLAYER_STATE_DO_NOTHING:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE;
                playbackState = PlaybackStateCompat.STATE_STOPPED;
        }

        final PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
        stateBuilder.setActions(actions);
        stateBuilder.setState(playbackState, getCurrentPosition(), 1.0f, SystemClock.elapsedRealtime());
        return stateBuilder.build();
    }
    public interface MediaPlayerListener
    {
        void onStateChange();
    }
    public void setPlaylistEnded() {currentState = PLAYER_STATE_DO_NOTHING;}
}
