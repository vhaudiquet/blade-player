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

import android.Manifest;
import android.app.SearchManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.NavigationView;
import android.support.v4.app.ActivityCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.*;
import android.widget.*;
import v.blade.R;
import v.blade.library.*;
import v.blade.ui.adapters.LibraryObjectAdapter;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener
{
    private static final int EXT_PERM_REQUEST_CODE = 0x42;

    /* music controller and callbacks */
    private boolean musicCallbacksRegistered = false;
    private MediaControllerCompat.Callback musicCallbacks = new MediaControllerCompat.Callback()
    {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state)
        {
            showCurrentPlay(PlayerConnection.musicPlayer.getCurrentSong(), PlayerConnection.musicPlayer.isPlaying());
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata)
        {
            showCurrentPlay(PlayerConnection.musicPlayer.getCurrentSong(), PlayerConnection.musicPlayer.isPlaying());
        }
    };
    private PlayerConnection.Callback connectionCallbacks = new PlayerConnection.Callback()
    {
        @Override
        public void onConnected()
        {
            if(!musicCallbacksRegistered)
            {
                PlayerConnection.musicController.registerCallback(musicCallbacks);
                musicCallbacksRegistered = true;
            }

            if(PlayerConnection.musicPlayer.isPlaying())
                showCurrentPlay(PlayerConnection.musicPlayer.getCurrentSong(), PlayerConnection.musicPlayer.isPlaying());
        }

        @Override
        public void onDisconnected()
        {

        }
    };

    /* current activity context */
    private static final int CONTEXT_NONE = 0;
    private static final int CONTEXT_ARTISTS = 1;
    private static final int CONTEXT_ALBUMS = 2;
    private static final int CONTEXT_SONGS = 3;
    private static final int CONTEXT_PLAYLISTS = 4;
    private static final int CONTEXT_SEARCH = 5;
    private int currentContext = CONTEXT_NONE;
    /* specific context (back button) handling */
    private boolean fromArtists = false; // we came to this context from the "artists" view
    private Artist artistFrom;
    private boolean fromAlbum = false; // we came to this context from an album view

    /* currently playing display */
    private RelativeLayout currentPlay;
    private TextView currentPlayTitle;
    private TextView currentPlaySubtitle;
    private ImageView currentPlayImage;
    private ImageView currentPlayAction;
    private boolean currentPlayShown = false;

    /* main list view */
    private ListView mainListView;
    private ListView.OnItemClickListener mainListViewListener = new ListView.OnItemClickListener()
    {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id)
        {
            if(view instanceof ImageView)
            {
                Toast.makeText(MainActivity.this, "imageView", Toast.LENGTH_SHORT).show();
            }

            switch(currentContext)
            {
                case CONTEXT_SONGS:
                    ArrayList<Song> songs = (ArrayList<Song>) ((LibraryObjectAdapter)mainListView.getAdapter()).getObjects().clone();
                    PlayerConnection.musicPlayer.setCurrentPlaylist(songs, position);
                    break;
                case CONTEXT_ARTISTS:
                    Artist currentArtist = (Artist) ((LibraryObjectAdapter)mainListView.getAdapter()).getObjects().get(position);
                    fromArtists = true;
                    artistFrom = currentArtist;
                    ArrayList<Album> albums = currentArtist.getAlbums();
                    setContentToAlbums(albums, currentArtist.getName());
                    break;
                case CONTEXT_ALBUMS:
                    Album currentAlbum = (Album) ((LibraryObjectAdapter)mainListView.getAdapter()).getObjects().get(position);
                    fromAlbum = true;
                    ArrayList<Song> asongs = currentAlbum.getSongs();
                    setContentToSongs(asongs, currentAlbum.getName());
                    break;
                case CONTEXT_PLAYLISTS:
                    Playlist currentPlaylist = (Playlist) ((LibraryObjectAdapter)mainListView.getAdapter()).getObjects().get(position);
                    ArrayList<Song> psongs = currentPlaylist.getContent();
                    setContentToSongs(psongs, currentPlaylist.getName());
                    break;
                case CONTEXT_SEARCH:
                    LibraryObject selected = ((LibraryObjectAdapter)mainListView.getAdapter()).getObjects().get(position);
                    if(selected instanceof Artist)
                        setContentToAlbums(((Artist) selected).getAlbums(), selected.getName());
                    else if(selected instanceof Album)
                        setContentToSongs(((Album) selected).getSongs(), selected.getName());
                    else if(selected instanceof Playlist)
                        setContentToSongs(((Playlist) selected).getContent(), selected.getName());
                    else if(selected instanceof Song)
                    {
                        ArrayList<Song> playlist = new ArrayList<Song>();
                        playlist.add((Song) selected);
                        PlayerConnection.musicPlayer.setCurrentPlaylist(playlist, 0);
                    }
                    break;
            }
        }
    };
    private ImageView.OnClickListener mainListViewMoreListener = new View.OnClickListener()
    {
        @Override
        public void onClick(View v)
        {
            final LibraryObject object = (LibraryObject) v.getTag();

            PopupMenu popupMenu = new PopupMenu(MainActivity.this, v);
            popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener()
            {
                @Override
                public boolean onMenuItemClick(MenuItem item)
                {
                    switch(item.getItemId())
                    {
                        case R.id.action_play:
                            ArrayList<Song> playlist = new ArrayList<Song>();
                            if(object instanceof Song) playlist.add((Song) object);
                            else if(object instanceof Album) playlist.addAll(((Album) object).getSongs());
                            else if(object instanceof Artist) for(Album a : ((Artist) object).getAlbums()) playlist.addAll(a.getSongs());
                            else if(object instanceof Playlist) playlist.addAll(((Playlist) object).getContent());
                            PlayerConnection.musicPlayer.setCurrentPlaylist(playlist, 0);
                            break;

                        case R.id.action_play_next:
                            ArrayList<Song> playlist1 = new ArrayList<Song>();
                            if(object instanceof Song) playlist1.add((Song) object);
                            else if(object instanceof Album) playlist1.addAll(((Album) object).getSongs());
                            else if(object instanceof Artist) for(Album a : ((Artist) object).getAlbums()) playlist1.addAll(a.getSongs());
                            else if(object instanceof Playlist) playlist1.addAll(((Playlist) object).getContent());
                            PlayerConnection.musicPlayer.addNextToPlaylist(playlist1);
                            break;

                        case R.id.action_add_to_playlist:
                            ArrayList<Song> playlist2 = new ArrayList<Song>();
                            if(object instanceof Song) playlist2.add((Song) object);
                            else if(object instanceof Album) playlist2.addAll(((Album) object).getSongs());
                            else if(object instanceof Artist) for(Album a : ((Artist) object).getAlbums()) playlist2.addAll(a.getSongs());
                            else if(object instanceof Playlist) playlist2.addAll(((Playlist) object).getContent());
                            PlayerConnection.musicPlayer.addToPlaylist(playlist2);
                            break;
                    }
                    return false;
                }
            });
            getMenuInflater().inflate(R.menu.menu_object_more, popupMenu.getMenu());
            popupMenu.show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        mainListView = (ListView) findViewById(R.id.libraryList);
        mainListView.setOnItemClickListener(mainListViewListener);

        currentPlay = (RelativeLayout) findViewById(R.id.currentPlay);
        currentPlay.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                Intent intent = new Intent(MainActivity.this, PlayActivity.class);
                startActivity(intent);
            }
        });
        currentPlayTitle = currentPlay.findViewById(R.id.element_title);
        currentPlaySubtitle = currentPlay.findViewById(R.id.element_subtitle);
        currentPlayImage = currentPlay.findViewById(R.id.element_image);
        currentPlayAction = currentPlay.findViewById(R.id.element_action);
        currentPlayAction.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if(PlayerConnection.musicPlayer.isPlaying()) PlayerConnection.musicController.getTransportControls().pause();
                else PlayerConnection.musicController.getTransportControls().play();
            }
        });

        checkPermission();

        setContentToArtists();
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        PlayerConnection.registerCallback(connectionCallbacks);

        if(PlayerConnection.musicPlayer == null) PlayerConnection.initConnection(this);
        else connectionCallbacks.onConnected();
    }

    @Override
    public void onBackPressed()
    {
        // Handle drawer close
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START))
        {
            drawer.closeDrawer(GravityCompat.START);
        }
        else if(fromAlbum)
        {
            if(fromArtists) setContentToAlbums(artistFrom.getAlbums(), artistFrom.getName());
            else setContentToAlbums(UserLibrary.getAlbums(), getResources().getString(R.string.albums));
            fromAlbum = false;
        }
        else if(fromArtists)
        {
            setContentToArtists();
            fromArtists = false;
            artistFrom = null;
        }
        else
        {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);

        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        SearchView searchView = (SearchView) menu.findItem(R.id.action_search).getActionView();
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));

        return true;
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        if(Intent.ACTION_SEARCH.equals(intent.getAction()))
        {
            String query = intent.getStringExtra(SearchManager.QUERY);
            setContentToSearch(UserLibrary.query(query));
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings)
        {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item)
    {
        // Handle navigation drawer action
        int id = item.getItemId();

        switch(id)
        {
            case R.id.nav_search:
                // Go to search activity
                break;

            case R.id.nav_artists:
                fromArtists = false; fromAlbum = false; artistFrom = null;
                // Replace current activity content with artist list
                setContentToArtists();
                break;

            case R.id.nav_albums:
                fromArtists = false; fromAlbum = false; artistFrom = null;
                // Replace current activity content with album view
                setContentToAlbums(UserLibrary.getAlbums(), getResources().getString(R.string.albums));
                break;

            case R.id.nav_songs:
                fromArtists = false; fromAlbum = false; artistFrom = null;
                // Replace current activity content with song list
                setContentToSongs(UserLibrary.getSongs(), getResources().getString(R.string.songs));
                break;

            case R.id.nav_playlists:
                fromArtists = false; fromAlbum = false; artistFrom = null;
                // Replace current activity content with playlist list
                setContentToPlaylists();
                break;
        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    /* Perform permission check and read library */
    private void checkPermission()
    {
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M)
        {
            if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!= PackageManager.PERMISSION_GRANTED)
            {
                if(shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE))
                {
                    // Show an alert dialog
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    builder.setMessage("Read external storage permission is required to read song list.");
                    builder.setTitle("Please grant permission");
                    builder.setPositiveButton("OK", new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i)
                        {
                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, EXT_PERM_REQUEST_CODE);
                        }
                    });
                    builder.setNeutralButton("Cancel",null);
                    AlertDialog dialog = builder.create();
                    dialog.show();
                }
                else
                {
                    // Request permission
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, EXT_PERM_REQUEST_CODE);
                }
            }
            else UserLibrary.registerLocalSongs(this.getApplicationContext());
        }
        else UserLibrary.registerLocalSongs(this.getApplicationContext());
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == EXT_PERM_REQUEST_CODE) UserLibrary.registerLocalSongs(this.getApplicationContext());
    }

    /* UI Change methods (Artists/Albums/Songs/Playlists...) */
    private void setContentToArtists()
    {
        this.setTitle(getResources().getString(R.string.artists));
        currentContext = CONTEXT_ARTISTS;
        LibraryObjectAdapter adapter = new LibraryObjectAdapter(this, UserLibrary.getArtists());
        adapter.registerMoreClickListener(mainListViewMoreListener);
        mainListView.setAdapter(adapter);
    }
    private void setContentToAlbums(ArrayList<Album> albums, String title)
    {
        this.setTitle(title);
        currentContext = CONTEXT_ALBUMS;
        LibraryObjectAdapter adapter = new LibraryObjectAdapter(this, albums);
        adapter.registerMoreClickListener(mainListViewMoreListener);
        mainListView.setAdapter(adapter);
    }
    private void setContentToSongs(ArrayList<Song> songs, String title)
    {
        this.setTitle(title);
        currentContext = CONTEXT_SONGS;
        LibraryObjectAdapter adapter = new LibraryObjectAdapter(this, songs);
        adapter.registerMoreClickListener(mainListViewMoreListener);
        mainListView.setAdapter(adapter);
    }
    private void setContentToPlaylists()
    {
        this.setTitle(getResources().getString(R.string.playlists));
        currentContext = CONTEXT_PLAYLISTS;
        LibraryObjectAdapter adapter = new LibraryObjectAdapter(this, UserLibrary.getPlaylists());
        adapter.registerMoreClickListener(mainListViewMoreListener);
        mainListView.setAdapter(adapter);
    }
    private void setContentToSearch(ArrayList<LibraryObject> searchResult)
    {
        fromArtists = false; fromAlbum = false; artistFrom = null;
        this.setTitle(getResources().getString(R.string.action_search));
        currentContext = CONTEXT_SEARCH;
        LibraryObjectAdapter adapter = new LibraryObjectAdapter(this, searchResult);
        adapter.registerMoreClickListener(mainListViewMoreListener);
        mainListView.setAdapter(adapter);
    }

    /* currently playing */
    private void showCurrentPlay(Song song, boolean play)
    {
        if(!currentPlayShown)
        {
            //resize list view so that currentPlay can be shown
            ViewGroup.LayoutParams params = mainListView.getLayoutParams();
            params.height = mainListView.getHeight() - 180;
            mainListView.setLayoutParams(params);

            //show
            currentPlay.setVisibility(View.VISIBLE);
            currentPlayShown = true;
        }

        // update informations
        currentPlayTitle.setText(song.getTitle());
        currentPlaySubtitle.setText(song.getArtist().getName() + " - " + song.getAlbum().getName());
        if(song.getAlbum().getAlbumArt() != null) currentPlayImage.setImageBitmap(song.getAlbum().getAlbumArt());
        else currentPlayImage.setImageResource(R.drawable.ic_albums);

        if(play) currentPlayAction.setImageResource(R.drawable.ic_action_pause);
        else currentPlayAction.setImageResource(R.drawable.ic_play_action);
    }
}
