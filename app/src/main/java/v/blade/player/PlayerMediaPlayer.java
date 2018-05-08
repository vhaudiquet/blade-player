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

    private final Context context;

    public final MediaPlayer mediaPlayer;
    private final MediaPlayer.OnCompletionListener mediaPlayerCompletionListener = new MediaPlayer.OnCompletionListener()
    {
        @Override
        public void onCompletion(MediaPlayer mp)
        {
            currentState = PLAYER_STATE_SONGEND;
            listener.onStateChange();
        }
    };

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

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(mediaPlayerCompletionListener);

        context.registerReceiver(mAudioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER);

        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        this.listener = listener;
    }

    public void play()
    {
        if(requestAudioFocus()) mediaPlayer.start();
        currentState = PLAYER_STATE_PLAYING;
        listener.onStateChange();
    }
    public void pause()
    {
        if(!playOnAudioFocus) audioManager.abandonAudioFocus(audioFocusChangeListener);
        mediaPlayer.pause();
        currentState = PLAYER_STATE_PAUSED;
        listener.onStateChange();
    }

    public void playSong(final Song song)
    {
        mediaPlayer.reset();

        if(song.getSource() == UserLibrary.SOURCE_LOCAL_LIB)
        {
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
                            System.out.println("Playing song " + song.getName());
                            spotifyPlayer.playUri(null, "spotify:track:" + song.getId(), 0, 0);
                        }
                        @Override
                        public void onLoggedOut() {}
                        @Override
                        public void onLoginFailed(Error error)
                        {
                            System.err.println("Spotify Player : login failed (" + error.name() + ")");
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
                    Toast.makeText(context, "Spotify Player error : " + throwable.getLocalizedMessage(), Toast.LENGTH_LONG).show();
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
        stateBuilder.setState(playbackState, mediaPlayer.getCurrentPosition(), 1.0f, SystemClock.elapsedRealtime());
        return stateBuilder.build();
    }
    public interface MediaPlayerListener
    {
        void onStateChange();
    }
    public void setPlaylistEnded() {currentState = PLAYER_STATE_DO_NOTHING;}
}
