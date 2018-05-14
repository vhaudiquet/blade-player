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
package v.blade.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;
import com.mobeta.android.dslv.DragSortController;
import com.mobeta.android.dslv.DragSortListView;
import v.blade.R;
import v.blade.library.Song;
import v.blade.library.UserLibrary;
import v.blade.ui.adapters.LibraryObjectAdapter;
import v.blade.ui.settings.SettingsActivity;

import java.util.ArrayList;

public class PlayActivity extends AppCompatActivity
{
    boolean isDisplayingAlbumArt = true;
    /* activity components */
    private ImageView albumView;
    private TextView songTitle;
    private TextView songArtistAlbum;
    private TextView playlistPosition;
    private TextView songDuration;
    private TextView songCurrentPosition;
    private ImageView playAction;
    private ImageView playlistAction;
    private ImageView moreAction;
    private ImageView shuffleAction;
    private ImageView prevAction;
    private ImageView nextAction;
    private ImageView repeatAction;
    private SeekBar seekBar;
    private DragSortListView playlistView;
    private LibraryObjectAdapter playlistAdapter;
    private ListView.OnItemClickListener playlistViewListener = new ListView.OnItemClickListener()
    {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id)
        {
            PlayerConnection.musicPlayer.setCurrentPosition(position);
        }
    };
    private DragSortController playlistDragController;
    private DragSortListView.DropListener playlistDropListener = new DragSortListView.DropListener()
    {
        @Override
        public void drop(int from, int to)
        {
            ArrayList<Song> playList = PlayerConnection.musicPlayer.getCurrentPlaylist();

            Song toSwap = playList.get(from);
            playList.remove(from);
            playList.add(to, toSwap);

            int selectedPos = PlayerConnection.musicPlayer.getCurrentPosition();
            if(selectedPos == from)
            {
                PlayerConnection.musicPlayer.updatePosition(to);
                playlistView.setItemChecked(to, true);
                playlistAdapter.setSelectedPosition(to);
            }
            else
            {
                int modifier = 0;
                if(to >= selectedPos && from < selectedPos) modifier = -1;
                else if(to <= selectedPos && from > selectedPos) modifier = +1;

                PlayerConnection.musicPlayer.updatePosition(PlayerConnection.musicPlayer.getCurrentPosition()+modifier);
                playlistView.setItemChecked(PlayerConnection.musicPlayer.getCurrentPosition(), true);
                playlistAdapter.setSelectedPosition(PlayerConnection.musicPlayer.getCurrentPosition());
            }

            UserLibrary.currentCallback.onLibraryChange();
        }
    };

    /* music player callbacks (UI refresh) */
    private MediaControllerCompat.Callback musicCallbacks = new MediaControllerCompat.Callback()
    {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state)
        {
            refreshState(state);
        }

        /*
        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata)
        {
            super.onMetadataChanged(metadata);
        }

        @Override
        public void onRepeatModeChanged(int repeatMode)
        {
            super.onRepeatModeChanged(repeatMode);
        }

        @Override
        public void onShuffleModeChanged(boolean enabled)
        {
            super.onShuffleModeChanged(enabled);
        }
        */
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        //get all components
        albumView = (ImageView) findViewById(R.id.album_display);
        songTitle = (TextView) findViewById(R.id.textview_title);
        songArtistAlbum = (TextView) findViewById(R.id.textview_subtitle);
        playlistPosition = (TextView) findViewById(R.id.textview_playlist_pos);
        songDuration = (TextView) findViewById(R.id.song_duration);
        songCurrentPosition = (TextView) findViewById(R.id.song_position);
        playAction = (ImageView) findViewById(R.id.play_button);
        playlistAction = (ImageView) findViewById(R.id.playlist_edit);
        moreAction = (ImageView) findViewById(R.id.more);
        shuffleAction = (ImageView) findViewById(R.id.shuffle_button);
        prevAction = (ImageView) findViewById(R.id.prev_button);
        nextAction = (ImageView) findViewById(R.id.next_button);
        repeatAction = (ImageView) findViewById(R.id.repeat_button);
        seekBar = (SeekBar) findViewById(R.id.seek_bar);
        playlistView = (DragSortListView) findViewById(R.id.playlist_view);
        playlistView.setOnItemClickListener(playlistViewListener);
        playlistDragController = new DragSortController(playlistView);
        playlistView.setFloatViewManager(playlistDragController);
        playlistView.setOnTouchListener(playlistDragController);
        playlistDragController.setDragHandleId(R.id.element_more);
        playlistView.setDropListener(playlistDropListener);

        /*
        * We do not call PlayerConnection.init because we assume connection was already established
        * (as we are already playing)
        */
        PlayerConnection.musicController.registerCallback(musicCallbacks);
        refreshState(PlayerConnection.musicPlayer.getPlayerState());

        //setup handler that will keep seekBar and playTime in sync
        final Handler handler = new Handler();
        this.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                int pos = PlayerConnection.musicPlayer.resolveCurrentSongPosition();
                int posMns = (pos / 60000) % 60000;
                int posScs = pos % 60000 / 1000;
                String songPos = String.format("%02d:%02d",  posMns, posScs);
                songCurrentPosition.setText(songPos);

                seekBar.setProgress(pos);

                handler.postDelayed(this, 200);
            }
        });
        //setup listener that will update time on seekbar clicked
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                if(fromUser) PlayerConnection.musicPlayer.seekTo(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.play, menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        int id = item.getItemId();

        if (id == R.id.action_settings)
        {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void refreshState(PlaybackStateCompat state)
    {
        Song currentSong = PlayerConnection.musicPlayer.getCurrentSong();

        //set album view / playlistView
        if(currentSong.getAlbum().hasAlbumArt()) albumView.setImageBitmap(PlayerConnection.musicPlayer.getCurrentArt());
        else albumView.setImageResource(R.drawable.ic_albums);

        if(playlistAdapter == null)
        {
            playlistAdapter = new LibraryObjectAdapter(this, PlayerConnection.musicPlayer.getCurrentPlaylist());
            playlistAdapter.setMoreImage(R.drawable.ic_action_move_black);
            playlistAdapter.repaintSongBackground();
            playlistView.setAdapter(playlistAdapter);
            playlistAdapter.setSelectedPosition(PlayerConnection.musicPlayer.getCurrentPosition());
        }
        else
        {
            playlistAdapter.setSelectedPosition(PlayerConnection.musicPlayer.getCurrentPosition());
            playlistAdapter.notifyDataSetChanged();
        }
        //playlistView.setSelection(PlayerConnection.musicPlayer.getCurrentPosition());
        playlistView.setItemChecked(PlayerConnection.musicPlayer.getCurrentPosition(), true);

        //set song info
        songTitle.setText(currentSong.getTitle());
        songArtistAlbum.setText(currentSong.getArtist().getName() + " - " + currentSong.getAlbum().getName());
        playlistPosition.setText((PlayerConnection.musicPlayer.getCurrentPosition()+1) + "/" + PlayerConnection.musicPlayer.getCurrentPlaylist().size());

        int dur = PlayerConnection.musicPlayer.resolveCurrentSongDuration();
        int durMns = (dur / 60000) % 60000;
        int durScs = dur % 60000 / 1000;
        String songDur = String.format("%02d:%02d",  durMns, durScs);
        songDuration.setText(songDur);
        seekBar.setMax(dur);

        //set play button icon
        if(PlayerConnection.musicPlayer.isPlaying()) playAction.setImageResource(R.drawable.ic_action_pause);
        else playAction.setImageResource(R.drawable.ic_play_action);

        //set shuffle button icon
        if(PlayerConnection.musicPlayer.isShuffleEnabled()) shuffleAction.setImageResource(R.drawable.ic_action_shuffle_enabled);
        else shuffleAction.setImageResource(R.drawable.ic_action_shuffle_white);

        //set repeat button icon
        int repeatMode = PlayerConnection.musicPlayer.getRepeatMode();
        if(repeatMode == PlaybackStateCompat.REPEAT_MODE_NONE) repeatAction.setImageResource(R.drawable.ic_action_repeat_white);
        else if(repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) repeatAction.setImageResource(R.drawable.ic_action_repeat_one);
        else repeatAction.setImageResource(R.drawable.ic_action_repeat_enabled);
    }

    /* button actions */
    public void onPlayClicked(View v)
    {
        if(PlayerConnection.musicPlayer.isPlaying()) PlayerConnection.musicController.getTransportControls().pause();
        else PlayerConnection.musicController.getTransportControls().play();
    }
    public void onPrevClicked(View v)
    {
        PlayerConnection.musicController.getTransportControls().skipToPrevious();
    }
    public void onNextClicked(View v)
    {
        PlayerConnection.musicController.getTransportControls().skipToNext();
    }
    public void onRepeatClicked(View v)
    {
        int currentRepeatMode = PlayerConnection.musicPlayer.getRepeatMode();

        if(currentRepeatMode == PlaybackStateCompat.REPEAT_MODE_NONE) currentRepeatMode = PlaybackStateCompat.REPEAT_MODE_ONE;
        else if(currentRepeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) currentRepeatMode = PlaybackStateCompat.REPEAT_MODE_ALL;
        else if(currentRepeatMode == PlaybackStateCompat.REPEAT_MODE_ALL) currentRepeatMode = PlaybackStateCompat.REPEAT_MODE_NONE;

        PlayerConnection.musicController.getTransportControls().setRepeatMode(currentRepeatMode);

        /* manually refresh UI */
        if(currentRepeatMode == PlaybackStateCompat.REPEAT_MODE_NONE) repeatAction.setImageResource(R.drawable.ic_action_repeat_white);
        else if(currentRepeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) repeatAction.setImageResource(R.drawable.ic_action_repeat_one);
        else repeatAction.setImageResource(R.drawable.ic_action_repeat_enabled);
    }
    public void onShuffleClicked(View v)
    {
        boolean shuffle = !PlayerConnection.musicPlayer.isShuffleEnabled();
        PlayerConnection.musicController.getTransportControls().setShuffleMode(0);

        /* manually refresh UI */
        if(shuffle) shuffleAction.setImageResource(R.drawable.ic_action_shuffle_enabled);
        else shuffleAction.setImageResource(R.drawable.ic_action_shuffle_white);
    }
    public void onPlaylistClicked(View v)
    {
        isDisplayingAlbumArt = !isDisplayingAlbumArt;

        if(isDisplayingAlbumArt)
        {
            playlistView.setVisibility(View.GONE);
            albumView.setVisibility(View.VISIBLE);
        }
        else
        {
            albumView.setVisibility(View.GONE);
            playlistView.setSelection(PlayerConnection.musicPlayer.getCurrentPosition());
            playlistView.setVisibility(View.VISIBLE);
        }
    }
}
