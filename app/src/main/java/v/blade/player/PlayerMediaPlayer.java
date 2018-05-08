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

import android.content.*;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.media.session.PlaybackStateCompat;
import android.widget.Toast;
import com.spotify.sdk.android.player.*;
import com.spotify.sdk.android.player.Error;
import kaaes.spotify.webapi.android.SpotifyService;
import v.blade.library.Song;
import v.blade.library.UserLibrary;

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
    private int currentState = PLAYER_STATE_NONE;
    private final MediaPlayerListener listener;

    private static final int NO_PLAYER_ACTIVE = 0;
    private static final int LOCAL_PLAYER_ACTIVE = 1;
    private static final int SPOTIFY_PLAYER_ACTIVE = 2;
    private int currentActivePlayer = NO_PLAYER_ACTIVE;

    private Song currentSong;
    private final Context context;

    /* local MediaPlayer */
    private final MediaPlayer mediaPlayer;
    private final MediaPlayer.OnCompletionListener mediaPlayerCompletionListener = new MediaPlayer.OnCompletionListener()
    {
        @Override
        public void onCompletion(MediaPlayer mp)
        {
            currentState = PLAYER_STATE_SONGEND;
            listener.onStateChange();
        }
    };

    /* Spotify media player */
    private Player spotifyPlayer;
    private static final int SPOTIFY_ERROR_NONE = 0;
    private static final int SPOTIFY_ERROR_LOGIN = 1;
    private static final int SPOTIFY_ERROR_OTHER = 2;
    private int spotifyPlayerError;

    private static final IntentFilter AUDIO_NOISY_INTENT_FILTER = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    private final BroadcastReceiver mAudioNoisyReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()))
            {
                if (mediaPlayer.isPlaying()) pause();
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
                    if(playOnAudioFocus && !mediaPlayer.isPlaying()) play();
                    else if(mediaPlayer.isPlaying()) mediaPlayer.setVolume(MEDIA_VOLUME_DEFAULT, MEDIA_VOLUME_DEFAULT);
                    playOnAudioFocus = PLAY_ON_AUDIOFOCUS;
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    mediaPlayer.setVolume(MEDIA_VOLUME_DUCK, MEDIA_VOLUME_DUCK);
                    break;

                /* We only lost audiofocus for a small ammount of time, relaunch player just after */
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if(mediaPlayer.isPlaying()) {playOnAudioFocus = true; pause();}
                    break;

                /* We lost audiofocus definetely ; maybe another player was started or ... */
                case AudioManager.AUDIOFOCUS_LOSS:
                    if(mediaPlayer.isPlaying()) {pause();}
                    break;
            }
        }
    };
    private boolean playOnAudioFocus = PLAY_ON_AUDIOFOCUS;

    public PlayerMediaPlayer(@NonNull Context context, MediaPlayerListener listener)
    {
        this.context = context;

        /* init local media player */
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(mediaPlayerCompletionListener);
        context.registerReceiver(mAudioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        /* init spotify media player */
        Config playerConfig = new Config(context, UserLibrary.SPOTIFY_USER_TOKEN, UserLibrary.SPOTIFY_CLIENT_ID);
        Spotify.getPlayer(playerConfig, context, new SpotifyPlayer.InitializationObserver()
        {
            @Override
            public void onInitialized(final SpotifyPlayer spotifyPlayer)
            {
                spotifyPlayer.addConnectionStateCallback(new ConnectionStateCallback() {
                    @Override
                    public void onLoggedIn()
                    {
                        PlayerMediaPlayer.this.spotifyPlayer = spotifyPlayer;
                    }
                    @Override
                    public void onLoggedOut() {}
                    @Override
                    public void onLoginFailed(Error error)
                    {
                        spotifyPlayerError = SPOTIFY_ERROR_LOGIN;
                    }
                    @Override
                    public void onTemporaryError() {}
                    @Override
                    public void onConnectionMessage(String s) {}
                });
            }

            @Override
            public void onError(Throwable throwable)
            {
                System.err.println("Spotify player error : " + throwable.getLocalizedMessage());
                spotifyPlayerError = SPOTIFY_ERROR_OTHER;
            }
        });

        this.listener = listener;
    }

    /* player operations */
    public void play()
    {
        System.out.println("PLAY()");
        if(requestAudioFocus())
        {
            if(currentActivePlayer == LOCAL_PLAYER_ACTIVE)
                mediaPlayer.start();
            else if(currentActivePlayer == SPOTIFY_PLAYER_ACTIVE)
                spotifyPlayer.resume(null);

            currentState = PLAYER_STATE_PLAYING;
            listener.onStateChange();
        }
    }
    public void pause()
    {
        System.out.println("PAUSE()");
        if(!playOnAudioFocus) audioManager.abandonAudioFocus(audioFocusChangeListener);

        if(currentActivePlayer == LOCAL_PLAYER_ACTIVE)
            mediaPlayer.pause();
        else if(currentActivePlayer == SPOTIFY_PLAYER_ACTIVE)
            spotifyPlayer.pause(null);

        currentState = PLAYER_STATE_PAUSED;
        listener.onStateChange();
    }
    public void stop()
    {
        audioManager.abandonAudioFocus(audioFocusChangeListener);

        if(currentActivePlayer == LOCAL_PLAYER_ACTIVE)
            mediaPlayer.stop();
        else if(currentActivePlayer == SPOTIFY_PLAYER_ACTIVE)
            spotifyPlayer.destroy();

        currentState = PLAYER_STATE_STOPPED;
        listener.onStateChange();
    }
    public void seekTo(int msec)
    {
        if(currentActivePlayer == LOCAL_PLAYER_ACTIVE) mediaPlayer.seekTo(msec);
        else if(currentActivePlayer == SPOTIFY_PLAYER_ACTIVE) spotifyPlayer.seekToPosition(null, msec);
    }
    public int getCurrentPosition()
    {
        if(currentActivePlayer == LOCAL_PLAYER_ACTIVE) return mediaPlayer.getCurrentPosition();
        else if(currentActivePlayer == SPOTIFY_PLAYER_ACTIVE) return ((int) spotifyPlayer.getPlaybackState().positionMs);

        return 0;
    }
    public boolean isPlaying()
    {
        if(currentActivePlayer == LOCAL_PLAYER_ACTIVE) return mediaPlayer.isPlaying();
        else if(currentActivePlayer == SPOTIFY_PLAYER_ACTIVE) return currentState == PLAYER_STATE_PLAYING;
        return false;
    }
    public int getDuration()
    {
        if(currentActivePlayer == LOCAL_PLAYER_ACTIVE) return mediaPlayer.getDuration();
        else if(currentActivePlayer == SPOTIFY_PLAYER_ACTIVE) return ((int) currentSong.getDuration());
        return 0;
    }


    public void playSong(final Song song)
    {
        currentSong = song;

        /* stop/reset current playback */
        if(currentActivePlayer == LOCAL_PLAYER_ACTIVE) mediaPlayer.reset();
        else if(currentActivePlayer == SPOTIFY_PLAYER_ACTIVE) spotifyPlayer.pause(null);

        /* select appropriate mediaplayer and start playback */
        if(song.getSource() == UserLibrary.SOURCE_LOCAL_LIB)
        {
            currentActivePlayer = LOCAL_PLAYER_ACTIVE;

            Uri songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, (long) song.getId());

            try
            {
                mediaPlayer.setDataSource(context, songUri);
                mediaPlayer.prepare();
                play();
            }
            catch(Exception e) {} //ignored.
        }
        else if(song.getSource() == UserLibrary.SOURCE_SPOTIFY)
        {
            currentActivePlayer = SPOTIFY_PLAYER_ACTIVE;
            if(spotifyPlayer != null)
            {
                spotifyPlayer.playUri(null, "spotify:track:" + song.getId(), 0, 0);
                currentState = PLAYER_STATE_PLAYING;
                listener.onStateChange();
            }
            else
            {
                Toast.makeText(context, "Erreur " +
                        (spotifyPlayerError == SPOTIFY_ERROR_LOGIN ? " de connection (login/mot de passe erronés, compte non-premium)" : "inconnue")
                        + " du lecteur Spotify", Toast.LENGTH_SHORT).show();
                currentActivePlayer = NO_PLAYER_ACTIVE;
                currentState = PLAYER_STATE_STOPPED;
                listener.onStateChange();
            }
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
