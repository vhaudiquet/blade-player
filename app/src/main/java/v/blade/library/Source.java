package v.blade.library;

import android.app.Application;
import android.content.*;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.LongSparseArray;
import android.widget.Toast;
import com.deezer.sdk.model.User;
import com.deezer.sdk.network.connect.DeezerConnect;
import com.deezer.sdk.network.connect.SessionStore;
import com.deezer.sdk.network.request.DeezerRequest;
import com.deezer.sdk.network.request.DeezerRequestFactory;
import com.deezer.sdk.network.request.JsonUtils;
import com.deezer.sdk.player.TrackPlayer;
import com.deezer.sdk.player.event.OnPlayerStateChangeListener;
import com.deezer.sdk.player.event.PlayerState;
import com.deezer.sdk.player.networkcheck.WifiAndMobileNetworkStateChecker;
import kaaes.spotify.webapi.android.SpotifyApi;
import kaaes.spotify.webapi.android.SpotifyError;
import kaaes.spotify.webapi.android.SpotifyService;
import kaaes.spotify.webapi.android.models.*;
import retrofit.RetrofitError;
import v.blade.R;
import v.blade.player.PlayerMediaPlayer;
import v.blade.ui.settings.SettingsActivity;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static v.blade.library.LibraryService.CACHE_SEPARATOR;

public abstract class Source
{
    private final int iconImage;
    private final int logoImage;
    private int priority;
    private boolean available;
    private String name;

    private Source(int iconImage, int logoImage, String name) {this.iconImage = iconImage; this.logoImage = logoImage; this.name = name;}
    public int getIconImage() {return iconImage;}
    public int getLogoImage() {return logoImage;}
    public int getPriority() {return priority;}
    public void setPriority(int priority) {this.priority = priority;}
    public void setAvailable(boolean available) {this.available = available;}
    public boolean isAvailable() {return this.available;}
    public String getName() {return name;}
    @Override public String toString() {return name;}

    //source settings/init/player...
    public abstract void initConfig(SharedPreferences accountsPrefs);
    public abstract void disconnect();
    public abstract String getUserName();
    public abstract SourcePlayer getPlayer();

    //source songs registering
    public abstract void registerCachedSongs();
    public abstract void loadCachedArts();
    public abstract void registerSongs();

    //source querying
    public abstract List<LibraryObject> query(String query);
    public abstract boolean searchForSong(Song song);

    /* operations */
    public interface OperationCallback {void onSucess(LibraryObject result); void onFailure();}

    //playlist management operations
    public abstract void addSongsToPlaylist(List<Song> songs, Playlist list, OperationCallback callback);
    public abstract void removeSongFromPlaylist(Song song, Playlist list, OperationCallback callback);
    public abstract void addPlaylist(String name, OperationCallback callback, boolean isPublic, boolean isCollaborative);
    public abstract void removePlaylist(Playlist playlist, OperationCallback callback);

    //library management operations
    public abstract void addSongToLibrary(Song song, OperationCallback callback);
    public abstract void removeSongFromLibrary(Song song, OperationCallback callback);
    public abstract void addAlbumToLibrary(Album album, OperationCallback callback);
    public abstract void removeAlbumFromLibrary(Album album, OperationCallback callback);

    public static Source SOURCE_LOCAL_LIB = new Source(R.drawable.ic_local, 0, "Local")
    {
        private LongSparseArray<Album> idsorted_albums = null;

        private SourcePlayer player = new SourcePlayer()
        {
            MediaPlayer mediaPlayer;
            int duration = 1000; //getDuration may throw if unprepared...

            @Override
            public void init()
            {
                mediaPlayer = new MediaPlayer();
                setListener(PlayerMediaPlayer.playerListener);
            }

            @Override
            public void setListener(PlayerListener listener)
            {
                if(mediaPlayer == null) return;

                mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener()
                {
                    @Override
                    public void onCompletion(MediaPlayer mp)
                    {
                        listener.onSongCompletion();
                    }
                });
            }

            @Override
            public void play(PlayerCallback callback)
            {
                if(mediaPlayer == null) {if(callback != null) callback.onFailure(player); return;}

                mediaPlayer.start();
                if(callback != null) callback.onSucess(player);
            }

            @Override
            public void pause(PlayerCallback callback)
            {
                if(mediaPlayer == null) {if(callback != null) callback.onFailure(player); return;}

                mediaPlayer.pause();
                if(callback != null) callback.onSucess(player);
            }

            @Override
            public void playSong(Song song, PlayerCallback callback)
            {
                SongSources.SongSource local = song.getSources().getLocal();
                if(local == null) {if(callback != null) callback.onFailure(player); return;}
                if(mediaPlayer == null) {if(callback != null) callback.onFailure(player); return;}

                if(song.getFormat().equals("audio/x-ms-wma"))
                {
                    Toast.makeText(LibraryService.appContext, LibraryService.appContext.getString(R.string.format_unsupported), Toast.LENGTH_SHORT).show();
                    callback.onFailure(player);
                    return;
                }
                Uri songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, (long) song.getSources().getLocal().getId());

                try
                {
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(LibraryService.appContext, songUri);
                    mediaPlayer.prepareAsync();
                    mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener()
                    {
                        @Override
                        public void onPrepared(MediaPlayer mp) {duration = mediaPlayer.getDuration(); play(callback);}
                    });
                }
                catch(Exception e) {if(callback != null) callback.onFailure(player);}
            }

            @Override
            public void seekTo(int msec)
            {
                mediaPlayer.seekTo(msec);
            }

            @Override
            public int getCurrentPosition()
            {
                return mediaPlayer.getCurrentPosition();
            }

            @Override
            public int getDuration()
            {
                return duration;
            }
        };

        @Override
        public String getUserName() {return "";}
        @Override
        public SourcePlayer getPlayer() {return player;}
        @Override
        public List<LibraryObject> query(String query) {return new ArrayList<>();}

        @Override
        public void initConfig(SharedPreferences accountsPrefs)
        {
            setPriority(999);
            player.init();
        }

        @Override
        public void registerCachedSongs()
        {
            registerSongs();
        }

        @Override
        public void registerSongs()
        {
            if(!LibraryService.configured) return;

            System.out.println("[BLADE-LOCAL] Registering songs...");

            //empty lists
            LibraryService.getArtists().clear();
            LibraryService.getAlbums().clear();
            LibraryService.getSongs().clear();
            LibraryService.getPlaylists().clear();
            LibraryService.songsByName.clear();

            /* get content resolver and init temp sorted arrays */
            final ContentResolver musicResolver = LibraryService.appContext.getContentResolver();
            boolean loadAlbumArts = true;
            if(idsorted_albums != null) //load of album art is not finished but resync was called
                loadAlbumArts = false;

            if(loadAlbumArts) idsorted_albums = new LongSparseArray<>();
            LongSparseArray<Song> idsorted_songs = new LongSparseArray<>();

            /* let's get all music files of the user, and register them and their attributes */
            android.database.Cursor musicCursor = musicResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null);
            if(musicCursor!=null && musicCursor.moveToFirst())
            {
                //get columns
                int titleColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int idColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media._ID);
                int artistColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int artistIdColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID);
                int albumColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int albumIdColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                int albumTrackColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.TRACK);
                int songDurationColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                int formatColumn = musicCursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
                int fileColumn = musicCursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);

                //add songs to list
                do
                {
                    long thisId = musicCursor.getLong(idColumn);
                    long artistId = musicCursor.getLong(artistIdColumn);
                    long albumId = musicCursor.getLong(albumIdColumn);
                    int albumTrack = musicCursor.getInt(albumTrackColumn);
                    String thisTitle = musicCursor.getString(titleColumn);
                    String thisArtist = musicCursor.getString(artistColumn);
                    String thisAlbum = musicCursor.getString(albumColumn);
                    long thisDuration = musicCursor.getLong(songDurationColumn);
                    String thisPath = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI + "/" + musicCursor.getString(fileColumn);

                    //resolve null artist name
                    if(thisArtist == null || thisArtist.equals("<unknown>"))
                        thisArtist = LibraryService.appContext.getString(R.string.unknown_artist);

                    //resolve null album name (should not happen)
                    if(thisAlbum == null || thisAlbum.equals("<unknown>"))
                        thisAlbum = LibraryService.appContext.getString(R.string.unknown_album);

                    //set to empty string to avoid crashes (NullPointer), should definitely not happen but who knows
                    if(thisTitle == null) thisTitle = "";

                    Song s = LibraryService.registerSong(thisArtist, thisAlbum, albumTrack, thisDuration, thisTitle, new SongSources.SongSource(thisId, SOURCE_LOCAL_LIB));
                    s.setFormat(musicCursor.getString(formatColumn));
                    s.setPath(thisPath);
                    idsorted_songs.put(thisId, s);
                    if(loadAlbumArts)
                        if(idsorted_albums.get(albumId) == null) idsorted_albums.put(albumId, s.getAlbum());
                }
                while (musicCursor.moveToNext());
                musicCursor.close();
            }

            /* we also need to get playlists on device */
            android.database.Cursor playlistCursor = musicResolver.query(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, null, null, null, null);
            if(playlistCursor!=null && playlistCursor.moveToFirst())
            {
                int idColumn = playlistCursor.getColumnIndex(MediaStore.Audio.Playlists._ID);
                int nameColumn = playlistCursor.getColumnIndex(MediaStore.Audio.Playlists.NAME);
                int fileColumn = playlistCursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);

                do
                {
                    long thisId = playlistCursor.getLong(idColumn);
                    String thisName = playlistCursor.getString(nameColumn);
                    String thisPath = "";
                    if(fileColumn != -1)
                        thisPath = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI + "/" + playlistCursor.getString(fileColumn);

                    //now we have to resolve the content of this playlist
                    ArrayList<Song> thisList = new ArrayList<>();
                    android.database.Cursor thisPlaylistCursor = musicResolver.query(MediaStore.Audio.Playlists.Members.getContentUri("external", thisId), null, null, null, null);
                    if(thisPlaylistCursor!=null && thisPlaylistCursor.moveToFirst())
                    {
                        int audioIdColumn = thisPlaylistCursor.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID);

                        do
                        {
                            thisList.add(idsorted_songs.get(thisPlaylistCursor.getLong(audioIdColumn)));
                        } while(thisPlaylistCursor.moveToNext());
                        thisPlaylistCursor.close();
                    }

                    Playlist list = new Playlist(thisName, thisList);
                    list.getSources().addSource(new SongSources.SongSource(thisId, SOURCE_LOCAL_LIB));
                    list.setPath(thisPath);
                    LibraryService.getPlaylists().add(list);
                    if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                    //TODO : generate image for the playlist


                } while(playlistCursor.moveToNext());
                playlistCursor.close();
            }

            System.out.println("[BLADE-LOCAL] Songs registered");
        }

        @Override
        public void loadCachedArts()
        {
            if(idsorted_albums == null) return;
            LongSparseArray<Album> thisArray = idsorted_albums.clone(); //avoid sync problems

            Cursor albumCursor = LibraryService.appContext.getContentResolver().
                    query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, null, null, null, null, null);
            if(albumCursor!=null && albumCursor.moveToFirst())
            {
                int idCol = albumCursor.getColumnIndex(MediaStore.Audio.Albums._ID);
                int artCol = albumCursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART);

                do
                {
                    long thisId = albumCursor.getLong(idCol);
                    String path = albumCursor.getString(artCol);

                    Album a = thisArray.get(thisId);
                    if(a != null)
                    {
                        LibraryService.loadArt(a, path, true);
                    }
                } while (albumCursor.moveToNext());
                albumCursor.close();
            }

            thisArray = null;
        }

        @Override
        public void addSongsToPlaylist(List<Song> songs, Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_LOCAL_LIB) {callback.onFailure(); return;}

            int count = list.getContent().size();
            ContentValues[] values = new ContentValues[songs.size()];
            for (int i = 0; i < songs.size(); i++)
            {
                SongSources.SongSource local = songs.get(i).getSources().getLocal();
                if(local == null) {callback.onFailure(); return;}

                values[i] = new ContentValues();
                values[i].put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, i + count + 1);
                values[i].put(MediaStore.Audio.Playlists.Members.AUDIO_ID, (long) songs.get(i).getSources().getLocal().getId());
            }
            Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", (long) list.getSources().getLocal().getId());
            ContentResolver resolver = LibraryService.appContext.getContentResolver();
            int num = resolver.bulkInsert(uri, values);
            resolver.notifyChange(Uri.parse("content://media"), null);

            list.getContent().addAll(songs);

            callback.onSucess(null);
        }
        @Override
        public void removeSongFromPlaylist(Song song, Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_LOCAL_LIB) {callback.onFailure(); return;}

            ContentResolver resolver = LibraryService.appContext.getContentResolver();
            try
            {
                Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", (long) list.getSources().getLocal().getId());
                int countDel = resolver.delete(uri, MediaStore.Audio.Playlists.Members.AUDIO_ID + " = ? ",
                        new String[]{Long.toString((long) song.getSources().getLocal().getId())});
                if(countDel >= 1)
                {
                    callback.onSucess(null);
                    list.getContent().remove(song);
                    if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();
                }
                else callback.onFailure();
            }
            catch (Exception e)
            {
                callback.onFailure();
                e.printStackTrace();
            }
        }

        @Override
        public boolean searchForSong(Song song) {return false;}
        @Override
        public void disconnect() {}

        @Override
        public void addPlaylist(String name, OperationCallback callback, boolean isPublic, boolean isCollaborative)
        {
            ContentResolver contentResolver = LibraryService.appContext.getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.Audio.Playlists.NAME, name);
            contentValues.put(MediaStore.Audio.Playlists.DATE_ADDED, System.currentTimeMillis());
            contentValues.put(MediaStore.Audio.Playlists.DATE_MODIFIED, System.currentTimeMillis());
            Uri uri = contentResolver.insert(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, contentValues);

            if(uri != null)
            {
                long mPlaylistId = Long.parseLong(uri.getLastPathSegment());

                //add playlist in RAM
                Playlist list = new Playlist(name, new ArrayList<>());
                list.getSources().addSource(new SongSources.SongSource(mPlaylistId, SOURCE_LOCAL_LIB));
                LibraryService.getPlaylists().add(list);
                if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                callback.onSucess(list);
            }
            else callback.onFailure();
        }
        @Override
        public void removePlaylist(Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_LOCAL_LIB) {callback.onFailure(); return;}

            if(!list.getPath().equals("")) new File(list.getPath()).delete();

            ContentResolver contentResolver = LibraryService.appContext.getContentResolver();
            String where = MediaStore.Audio.Playlists._ID + "=?";
            String[] whereVal = {Long.toString((long) list.getSources().getLocal().getId())};
            int deleteCount = contentResolver.delete(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, where, whereVal);
            if(deleteCount >= 1)
            {
                //remove playlist from ram
                LibraryService.getPlaylists().remove(list);
                if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                callback.onSucess(null);
            }
            else callback.onFailure();
        }

        public void addSongToLibrary(Song song, OperationCallback callback)
        {callback.onFailure();}
        public void removeSongFromLibrary(Song song, OperationCallback callback)
        {
            SongSources.SongSource local = song.getSources().getLocal();
            if(local == null) {if(callback != null) callback.onFailure(); return;}

            new File(song.getPath()).delete();

            ContentResolver resolver = LibraryService.appContext.getContentResolver();
            int count = resolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Audio.Media._ID + " = " + local.getId(),
                    null);

            if(count >= 1)
            {
                //unregister song from library
                LibraryService.unregisterSong(song, local);

                if(callback != null) callback.onSucess(null);
            }
            else if(callback != null) callback.onFailure();
        }
        public void addAlbumToLibrary(Album album, OperationCallback callback)
        {callback.onFailure();}
        public void removeAlbumFromLibrary(Album album, OperationCallback callback)
        {
            //TODO : handle errors
            for(Song s : album.getSongs())
            {
                removeSongFromLibrary(s, null);
            }

            callback.onSucess(null);

            //TODO : should i delete album entry from content resolver ? (++artist entry if last album)
        }
    };
    public static Spotify SOURCE_SPOTIFY = new Spotify();
    public static Deezer SOURCE_DEEZER = new Deezer();

    public static Source SOURCES[] = new Source[]{SOURCE_LOCAL_LIB, SOURCE_SPOTIFY, SOURCE_DEEZER};

    public static class Spotify extends Source
    {
        public final String SPOTIFY_CLIENT_ID = "2f95bc7168584e7aa67697418a684bae";
        public final String SPOTIFY_REDIRECT_URI = "http://valou3433.fr/";
        public String SPOTIFY_USER_TOKEN;
        public String SPOTIFY_REFRESH_TOKEN;
        public final SpotifyApi spotifyApi = new SpotifyApi();
        public UserPrivate mePrivate;
        private File spotifyCacheFile;
        private File spotifyPlaylistsCache;

        private ArrayList<LibraryObject> spotifyCachedToLoadArt;

        public SourcePlayer player = new SourcePlayer()
        {
            SpotifyPlayer spotifyPlayer;

            @Override
            public void init()
            {
                Config playerConfig = new Config(LibraryService.appContext, SPOTIFY_USER_TOKEN, SPOTIFY_CLIENT_ID);
                com.spotify.sdk.android.player.Spotify.getPlayer(playerConfig, LibraryService.appContext, new SpotifyPlayer.InitializationObserver()
                {
                    @Override
                    public void onInitialized(final SpotifyPlayer p)
                    {
                        spotifyPlayer = p;
                        setListener(PlayerMediaPlayer.playerListener);
                        spotifyPlayer.addConnectionStateCallback(new ConnectionStateCallback()
                        {
                            @Override
                            public void onLoggedIn() {}
                            @Override
                            public void onLoggedOut() {}
                            @Override
                            public void onLoginFailed(Error error)
                            {
                                Toast.makeText(LibraryService.appContext, LibraryService.appContext.getString(R.string.player_login_error) + " Spotify", Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(LibraryService.appContext, LibraryService.appContext.getString(R.string.player_unknown_error) + " Spotify", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void setListener(PlayerListener listener)
            {
                spotifyPlayer.addNotificationCallback(new Player.NotificationCallback()
                {
                    @Override
                    public void onPlaybackEvent(PlayerEvent playerEvent)
                    {
                        if(playerEvent.equals(PlayerEvent.kSpPlaybackNotifyAudioDeliveryDone))
                        {
                            listener.onSongCompletion();
                        }
                    }

                    @Override
                    public void onPlaybackError(Error error)
                    {
                        listener.onPlaybackError(player, error.name());
                    }
                });
            }

            @Override
            public void play(PlayerCallback callback)
            {
                if(spotifyPlayer == null) {callback.onFailure(this); return;}

                spotifyPlayer.resume(new Player.OperationCallback()
                {
                    @Override
                    public void onSuccess() {callback.onSucess(player);}
                    @Override
                    public void onError(Error error) {callback.onFailure(player);}
                });
            }

            @Override
            public void pause(PlayerCallback callback)
            {
                if(spotifyPlayer == null) {if(callback != null) callback.onFailure(player); return;}

                spotifyPlayer.pause(new Player.OperationCallback()
                {
                    @Override
                    public void onSuccess() {if(callback != null) callback.onSucess(player);}
                    @Override
                    public void onError(Error error) {if(callback != null) callback.onFailure(player);}
                });
            }

            @Override
            public void playSong(Song song, PlayerCallback callback)
            {
                SongSources.SongSource spot = song.getSources().getSpotify();
                if(spot == null) {if(callback != null) callback.onFailure(player); return;}

                if(spotifyPlayer == null)
                {
                    if(callback != null) callback.onFailure(player);
                    return;
                }

                spotifyPlayer.playUri(new Player.OperationCallback()
                {
                    @Override
                    public void onSuccess() {if(callback != null) callback.onSucess(player);}

                    @Override
                    public void onError(Error error) {if(callback != null) callback.onFailure(player);}
                }, "spotify:track:" + spot.getId(), 0, 0);
            }

            @Override
            public void seekTo(int msec)
            {
                if(spotifyPlayer != null) spotifyPlayer.seekToPosition(null, msec);
            }

            @Override
            public int getCurrentPosition()
            {
                return spotifyPlayer == null ? 0 : (int) spotifyPlayer.getPlaybackState().positionMs;
            }
        };

        Spotify()
        {
            super(R.drawable.ic_spotify, R.drawable.ic_spotify_logo, "Spotify");
        }

        @Override
        public SourcePlayer getPlayer() {return player;}

        @Override
        public String getUserName() {return mePrivate == null ? "" : (mePrivate.display_name == null ? mePrivate.id : mePrivate.display_name);}

        @Override
        public void initConfig(SharedPreferences accountsPrefs)
        {
            spotifyCacheFile = new File(LibraryService.appContext.getCacheDir().getAbsolutePath() + "/spotify.cached");
            spotifyPlaylistsCache = new File(LibraryService.appContext.getCacheDir().getAbsolutePath() + "/spotifyPlaylists/");
            if(!spotifyPlaylistsCache.exists()) spotifyPlaylistsCache.mkdir();

            setPriority(accountsPrefs.getInt("spotify_prior", 0));

            //setup spotify api
            if(SPOTIFY_USER_TOKEN == null)
            {
                SPOTIFY_USER_TOKEN = accountsPrefs.getString("spotify_token", null);
                SPOTIFY_REFRESH_TOKEN = accountsPrefs.getString("spotify_refresh_token", null);
            }
            if(SPOTIFY_USER_TOKEN != null)
            {
                spotifyApi.setAccessToken(SPOTIFY_USER_TOKEN);

                //check for token validity
                new Thread()
                {
                    public void run()
                    {
                        try
                        {
                            mePrivate = spotifyApi.getService().getMe();
                        }
                        catch(RetrofitError e)
                        {
                            if(e.getResponse() != null && e.getResponse().getStatus() == 401)
                            {
                                Log.println(Log.INFO, "[BLADE-SPOTIFY]", "Actualizing token.");
                                refreshSpotifyToken();
                                mePrivate = spotifyApi.getService().getMe();
                            }
                        }

                        player.init();
                    }
                }.start();

                setAvailable(true);
            }
        }

        @Override
        public void disconnect()
        {
            setAvailable(false);
            setPriority(0);
            SPOTIFY_USER_TOKEN = null;
            SPOTIFY_REFRESH_TOKEN = null;
            mePrivate = null;

            SharedPreferences accountsPrefs = LibraryService.appContext.getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = accountsPrefs.edit();
            editor.remove("spotify_token");
            editor.remove("spotify_refresh_token");
            editor.remove("spotify_prior");
            editor.apply();

            //remove cache
            spotifyCacheFile.delete();
            if(spotifyPlaylistsCache.exists())
            {
                for(File f : spotifyPlaylistsCache.listFiles()) f.delete();
                spotifyPlaylistsCache.delete();
            }

            //wait for resync
        }

        @Override
        public void registerCachedSongs()
        {
            if(!LibraryService.configured) return;

            spotifyCachedToLoadArt = new ArrayList<>();
            System.out.println("[BLADE-SPOTIFY] Registering cached songs...");
            try
            {
                if(spotifyCacheFile.exists())
                {
                    //spotify library
                    BufferedReader spr = new BufferedReader(new FileReader(spotifyCacheFile));
                    while(spr.ready())
                    {
                        String[] tp = spr.readLine().split(CACHE_SEPARATOR);
                        Song song = LibraryService.registerSong(tp[2],  tp[1],
                                Integer.parseInt(tp[4]), Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(tp[6], SOURCE_SPOTIFY));
                        song.setFormat(tp[3]);

                        if(!song.getAlbum().hasArt() && !song.getAlbum().getArtLoading())
                        {
                            //the image is supposed to be cached locally, so no need to provide URL
                            spotifyCachedToLoadArt.add(song.getAlbum());
                            song.getAlbum().setArtLoading();
                        }
                    }
                    spr.close();

                    //spotify playlists
                    for(File f : spotifyPlaylistsCache.listFiles())
                    {
                        ArrayList<Song> thisList = new ArrayList<>();
                        BufferedReader sppr = new BufferedReader(new FileReader(f));
                        String id = sppr.readLine();
                        boolean isMine = Boolean.parseBoolean(sppr.readLine());
                        String owner = null; String ownerID = null;
                        if(!isMine) {owner = sppr.readLine(); ownerID = sppr.readLine();}
                        boolean isCollab = Boolean.parseBoolean(sppr.readLine());
                        while(sppr.ready())
                        {
                            String[] tp = sppr.readLine().split(CACHE_SEPARATOR);
                            Song song = LibraryService.SAVE_PLAYLISTS_TO_LIBRARY ?
                                    LibraryService.registerSong(tp[2],  tp[1],  Integer.parseInt(tp[4]),
                                            Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(tp[6], SOURCE_SPOTIFY))
                                    : LibraryService.getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                    new SongSources.SongSource(tp[6], SOURCE_SPOTIFY), Integer.parseInt(tp[4]));
                            song.setFormat(tp[3]);
                            thisList.add(song);

                            if(!song.getAlbum().hasArt() && !song.getAlbum().getArtLoading())
                            {
                                //the image is supposed to be cached locally, so no need to provide URL
                                spotifyCachedToLoadArt.add(song.getAlbum());
                                song.getAlbum().setArtLoading();
                            }
                        }
                        sppr.close();

                        Playlist p = new Playlist(f.getName(), thisList);
                        if(!isMine) p.setOwner(owner, ownerID);
                        if(isCollab) p.setCollaborative();
                        spotifyCachedToLoadArt.add(p);
                        p.setArtLoading();
                        p.getSources().addSource(new SongSources.SongSource(id, SOURCE_SPOTIFY));
                        LibraryService.getPlaylists().add(p);
                    }
                }
            }
            catch(IOException e)
            {
                Log.println(Log.ERROR, "[BLADE-SPOTIFY]", "Cache restore : IOException");
                e.printStackTrace();
            }
            System.out.println("[BLADE-SPOTIFY] Cached songs registered.");
        }

        @Override
        public void loadCachedArts()
        {
            if(spotifyCachedToLoadArt == null) return;

            for(LibraryObject alb : spotifyCachedToLoadArt)
            {
                LibraryService.loadArt(alb, "", false);
            }

            spotifyCachedToLoadArt = null;
        }

        @Override
        public void registerSongs()
        {
            if(!LibraryService.configured) return;
            if(SPOTIFY_USER_TOKEN == null) return;

            // list used for spotify cache
            ArrayList<Song> spotifySongs = new ArrayList<>();
            ArrayList<Playlist> spotifyPlaylists = new ArrayList<>();

            SpotifyService service = spotifyApi.getService();
            try
            {
                if(mePrivate == null) mePrivate = service.getMe();

                //requests
                HashMap<String, Object> params = new HashMap<>();
                params.put("limit", 50);
                Pager<SavedTrack> userTracks = service.getMySavedTracks(params);

                //parse user tracks request response
                int count = userTracks.total;
                int offset = 0;
                while(true)
                {
                    for (SavedTrack track : userTracks.items)
                    {
                        Track t = track.track;
                        Song s = LibraryService.registerSong(t.artists.get(0).name,  t.album.name,
                                t.track_number, t.duration_ms, t.name, new SongSources.SongSource(t.id, SOURCE_SPOTIFY));
                        spotifySongs.add(s);
                        if(!s.getAlbum().hasArt())
                        {
                            if(t.album.images != null && t.album.images.size() >= 1)
                            {
                                Image albumImage = t.album.images.get(0);
                                LibraryService.loadArt(s.getAlbum(), albumImage.url, false);
                            }
                        }
                    }
                    count -= 50;
                    if(count <= 0) break;
                    else
                    {
                        offset += 50;
                        params.put("offset", offset);
                        userTracks = service.getMySavedTracks(params);
                    }
                }

                params.put("limit", 50);
                params.put("offset", 0);
                Pager<SavedAlbum> userAlbums = service.getMySavedAlbums(params);
                offset = 0;
                count = userAlbums.total;
                while(true)
                {
                    //parse user albums request response
                    for(SavedAlbum album : userAlbums.items)
                    {
                        Album savedAlbum = null;
                        kaaes.spotify.webapi.android.models.Album alb = album.album;
                        Pager<Track> tracks = service.getAlbumTracks(alb.id);
                        for(Track t : tracks.items)
                        {
                            Song s = LibraryService.registerSong(t.artists.get(0).name,  alb.name,
                                    t.track_number, t.duration_ms, t.name, new SongSources.SongSource(t.id, SOURCE_SPOTIFY));
                            spotifySongs.add(s);
                            if (savedAlbum == null) savedAlbum = s.getAlbum();
                        }

                        if(!savedAlbum.hasArt())
                        {
                            if(alb.images != null && alb.images.size() >= 1)
                            {
                                Image albumImage = alb.images.get(0);
                                LibraryService.loadArt(savedAlbum, albumImage.url, false);
                            }
                        }
                    }
                    count-=50;
                    if(count <= 0) break;
                    else
                    {
                        offset += 50;
                        params.put("offset", offset);
                        userAlbums = service.getMySavedAlbums(params);
                    }
                }

                params.put("limit", 20);
                params.put("offset", 0);
                Pager<PlaylistSimple> userPlaylists = service.getMyPlaylists();
                offset = 0;
                count = userPlaylists.total;
                while(true)
                {
                    //parse user playlists request response
                    for(PlaylistSimple playlistBase : userPlaylists.items)
                    {
                        ArrayList<Song> thisList = new ArrayList<>();
                        HashMap<String, Object> map = new HashMap<>();
                        int trackNbr = playlistBase.tracks.total;

                        int poffset = 0;
                        while(trackNbr > 0)
                        {
                            map.put("offset", poffset);
                            Pager<PlaylistTrack> tracks = service.getPlaylistTracks(playlistBase.owner.id, playlistBase.id, map);

                            for(PlaylistTrack pt : tracks.items)
                            {
                                Track t = pt.track;
                                if(t == null) continue;

                                Song s;
                                if(LibraryService.SAVE_PLAYLISTS_TO_LIBRARY)
                                    s = LibraryService.registerSong(t.artists.get(0).name,  t.album.name,
                                            t.track_number, t.duration_ms, t.name, new SongSources.SongSource(t.id, SOURCE_SPOTIFY));
                                else
                                    s = LibraryService.getSongHandle(t.name, t.album.name, t.artists.get(0).name, t.duration_ms, new SongSources.SongSource(t.id, SOURCE_SPOTIFY),
                                            t.track_number);

                                //get albumart for this song
                                if(!s.getAlbum().hasArt())
                                {
                                    if(t.album.images != null && t.album.images.size() >= 1)
                                    {
                                        Image albumImage = t.album.images.get(0);
                                        LibraryService.loadArt(s.getAlbum(), albumImage.url, false);
                                    }
                                }

                                thisList.add(s);
                            }

                            poffset+=100;
                            trackNbr-=100;
                        }

                        Playlist list = new Playlist(playlistBase.name, thisList);
                        list.getSources().addSource(new SongSources.SongSource(playlistBase.id, SOURCE_SPOTIFY));
                        if(playlistBase.collaborative) list.setCollaborative();
                        if(playlistBase.owner != null) if(!playlistBase.owner.id.equals(mePrivate.id)) list.setOwner(playlistBase.owner.display_name == null ? playlistBase.owner.id : playlistBase.owner.display_name, playlistBase.owner.id);
                        spotifyPlaylists.add(list);
                        LibraryService.getPlaylists().add(list);
                        if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();
                        if(playlistBase.images != null && playlistBase.images.size() >= 1)
                            LibraryService.loadArt(list, playlistBase.images.get(0).url, false);
                    }
                    count -= 20;
                    if(count <= 0) break;
                    else
                    {
                        offset += 20;
                        params.put("offset", offset);
                        userPlaylists = service.getMyPlaylists(params);
                    }
                }

                // cache all spotifySongs and spotifyPlaylists
                try
                {
                    //library songs
                    BufferedWriter bw = new BufferedWriter(new FileWriter((spotifyCacheFile)));
                    for(Song song : spotifySongs)
                    {
                        bw.write(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                                + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getSpotify().getId()
                                + CACHE_SEPARATOR);
                        bw.newLine();
                    }
                    bw.close();

                    //playlists
                    if(spotifyPlaylistsCache.exists() && spotifyPlaylistsCache.listFiles() != null)
                        for(File f : spotifyPlaylistsCache.listFiles()) f.delete();
                    for(Playlist p : spotifyPlaylists)
                    {
                        cachePlaylist(p);
                    }
                }
                catch(IOException e)
                {
                    Log.println(Log.ERROR, "[BLADE-SPOTIFY]", "Error while writing cache !");
                }
            }
            catch (RetrofitError error)
            {
                error.printStackTrace();

                if(error.getResponse() == null) return;
                if(error.getResponse().getStatus() == 401)
                {
                    Log.println(Log.INFO, "[BLADE-SPOTIFY]", "Actualizing token.");
                    refreshSpotifyToken();
                    registerSongs();
                    return;
                }

                System.err.println("ERROR BODY : " + error.getBody());
                SpotifyError spotifyError = SpotifyError.fromRetrofitError(error);
                spotifyError.printStackTrace();
                System.err.println("SPOTIFY ERROR DETAILS : " + spotifyError.getErrorDetails());
            }
        }

        @Override
        public List<LibraryObject> query(String query)
        {
            ArrayList<LibraryObject> tr = new ArrayList<>();
            HashMap<Album, String> urls = new HashMap<>();

            try
            {
                if(isAvailable())
                {
                    //request from spotify
                    TracksPager tracks = spotifyApi.getService().searchTracks(query);
                    AlbumsPager albums = spotifyApi.getService().searchAlbums(query);
                    //ArtistsPager artists = spotifyApi.getService().searchArtists(query);

                    //handle returned data
                    for(Track t : tracks.tracks.items)
                    {
                        Song song = LibraryService.getSongHandle(t.name, t.album.name, t.artists.get(0).name, t.duration_ms, new SongSources.SongSource(t.id, SOURCE_SPOTIFY), t.track_number);
                        tr.add(song);

                        if(!song.getAlbum().hasArt())
                        {
                            if(t.album.images.get(0) != null)
                                urls.put(song.getAlbum(), t.album.images.get(0).url);
                        }
                    }
                    for(kaaes.spotify.webapi.android.models.AlbumSimple a : albums.albums.items)
                    {
                        Album album = null;
                        Pager<Track> albumTracks = spotifyApi.getService().getAlbumTracks(a.id);
                        for(Track t : tracks.tracks.items)
                        {
                            Song currentSong = LibraryService.getSongHandle(t.name, t.album.name, t.artists.get(0).name, t.duration_ms, new SongSources.SongSource(t.id, SOURCE_SPOTIFY), t.track_number);
                            if(album == null) album = currentSong.getAlbum();
                        }

                        if(!album.hasArt())
                        {
                            if(a.images != null && a.images.size() >= 1)
                            {
                                Image albumImage = a.images.get(0);
                                if(albumImage != null)
                                    urls.put(album, a.images.get(0).url);
                            }
                        }
                    }
                }
            }
            catch(RetrofitError e)
            {
                if(e.getResponse() != null)
                {
                    if(e.getResponse().getStatus() == 401)
                    {
                        refreshSpotifyToken();
                        return query(query);
                    }
                }
                e.printStackTrace();
            }

            new Thread()
            {
                public void run()
                {
                    Looper.prepare();

                    for(Album a : urls.keySet())
                    {
                        LibraryService.loadArt(a, urls.get(a), false);
                    }
                }
            }.start();
            return tr;
        }

        @Override
        public void addSongsToPlaylist(List<Song> songs, Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_SPOTIFY) {callback.onFailure(); return;}
            if(!list.isMine() && !list.isCollaborative()) {callback.onFailure(); return;}

            new Thread()
            {
                public void run()
                {
                    Looper.prepare();

                    HashMap<String, Object> parameters = new HashMap<>();
                    String sSongs = "";
                    for(Song s : songs)
                    {
                        SongSources.SongSource spot = s.getSources().getSpotify();

                        if(spot == null)
                        {
                            if(SOURCE_SPOTIFY.searchForSong(s))
                                spot = s.getSources().getSpotify();
                            else {songs.remove(s); continue;}
                        }

                        sSongs += ("spotify:track:" + spot.getId() + ",");
                    }
                    if(sSongs.length() == 0) {callback.onFailure(); return;}
                    sSongs = sSongs.substring(0, sSongs.length()-1);
                    parameters.put("uris", sSongs);

                    try
                    {
                        spotifyApi.getService().addTracksToPlaylist((list.isCollaborative() ? (String) list.getOwnerID() : mePrivate.id), (String) list.getSources().getSpotify().getId(), parameters,
                                new HashMap<>());

                        //add song to RAM list
                        list.getContent().addAll(songs);

                        //add song to cached list
                        cachePlaylist(list);

                        callback.onSucess(null);
                    }
                    catch(RetrofitError error)
                    {
                        if(error.getResponse() == null) {callback.onFailure(); return;}
                        if(error.getResponse().getStatus() == 401)
                        {
                            refreshSpotifyToken();
                            addSongsToPlaylist(songs, list, callback);
                        }
                        else callback.onFailure();
                    }
                }
            }.start();
        }
        @Override
        public void removeSongFromPlaylist(Song song, Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_SPOTIFY) {callback.onFailure(); return;}
            if(!list.isMine() && !list.isCollaborative()) {callback.onFailure(); return;}

            TracksToRemove tracksToRemove = new TracksToRemove();
            tracksToRemove.tracks = new ArrayList<>();
            TrackToRemove trackToRemove = new TrackToRemove();
            trackToRemove.uri = "spotify:track:" + song.getSources().getSpotify().getId();
            tracksToRemove.tracks.add(trackToRemove);

            new Thread()
            {
                public void run()
                {
                    Looper.prepare();
                    try
                    {
                        spotifyApi.getService().removeTracksFromPlaylist((list.isCollaborative() ? (String) list.getOwnerID() : mePrivate.id), (String) list.getSources().getSpotify().getId(), tracksToRemove);

                        //remove song from RAM list and notify change
                        list.getContent().remove(song);
                        if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                        //remove song from cached list (by rewriting the whole list)
                        cachePlaylist(list);

                        callback.onSucess(null);
                    }
                    catch(RetrofitError error)
                    {
                        if(error.getResponse() == null) {callback.onFailure(); return;}
                        if(error.getResponse().getStatus() == 401)
                        {
                            refreshSpotifyToken();
                            removeSongFromPlaylist(song, list, callback);
                        }
                        else callback.onFailure();
                    }
                }
            }.start();
        }

        public void checkAndRefreshSpotifyToken()
        {
            //check for token validity
            try
            {
                mePrivate = spotifyApi.getService().getMe();
            }
            catch(RetrofitError e)
            {
                if(e.getResponse() != null && e.getResponse().getStatus() == 401)
                {
                    Log.println(Log.INFO, "[BLADE-SPOTIFY]", "Actualizing token.");
                    refreshSpotifyToken();
                    mePrivate = spotifyApi.getService().getMe();
                }
            }
        }
        private void refreshSpotifyToken()
        {
            try
            {
                URL apiUrl = new URL("https://accounts.spotify.com/api/token");
                HttpsURLConnection urlConnection = (HttpsURLConnection) apiUrl.openConnection();
                urlConnection.setDoInput(true);
                urlConnection.setDoOutput(true);
                urlConnection.setRequestMethod("POST");

                //write POST parameters
                OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
                BufferedWriter writer = new BufferedWriter (new OutputStreamWriter(out, "UTF-8"));
                writer.write("grant_type=refresh_token&");
                writer.write("refresh_token=" + SPOTIFY_REFRESH_TOKEN + "&");
                writer.write("client_id=" + SPOTIFY_CLIENT_ID + "&");
                writer.write("client_secret=" + "3166d3b40ff74582b03cb23d6701c297");
                writer.flush();
                writer.close();
                out.close();

                urlConnection.connect();

                System.out.println("[BLADE] [AUTH-REFRESH] Result : " + urlConnection.getResponseCode() + " " + urlConnection.getResponseMessage());

                BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                String result = reader.readLine();
                reader.close();
                result = result.substring(1);
                result = result.substring(0, result.length()-1);
                String[] results = result.split(",");
                for(String param : results)
                {
                    if(param.startsWith("\"access_token\":\""))
                    {
                        param = param.replaceFirst("\"access_token\":\"", "");
                        param = param.replaceFirst("\"", "");
                        SPOTIFY_USER_TOKEN = param;
                        spotifyApi.setAccessToken(SPOTIFY_USER_TOKEN);
                        SharedPreferences pref = LibraryService.appContext.getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = pref.edit();
                        editor.putString("spotify_token", SPOTIFY_USER_TOKEN);
                        editor.commit();
                    }
                }
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        }

        @Override
        public boolean searchForSong(Song s)
        {
            if(s.getSources().getSpotify() != null) return true;

            try
            {
                HashMap<String, Object> args = new HashMap<>();
                args.put("limit", 1);
                List<Track> t = Source.SOURCE_SPOTIFY.spotifyApi.getService().searchTracks(s.getTitle() + " album:" + s.getAlbum().getName() + " artist:" + s.getArtist().getName(), args).tracks.items;
                if(t != null && t.size() > 0 && t.get(0) != null)
                {
                    SongSources.SongSource source = new SongSources.SongSource(t.get(0).id, Source.SOURCE_SPOTIFY);
                    s.getSources().addSource(source);
                    s.getArtist().getSources().addSource(source);
                    s.getAlbum().getSources().addSource(source);
                    return true;
                }
            }
            catch(RetrofitError e) {} //ignored

            return false;
        }

        private void cachePlaylist(Playlist p)
        {
            try
            {
                File thisPlaylist = new File(spotifyPlaylistsCache.getAbsolutePath() + "/" + p.getName());
                thisPlaylist.createNewFile();
                BufferedWriter pwriter = new BufferedWriter(new FileWriter(thisPlaylist));
                pwriter.write((String) p.getSources().getSpotify().getId()); pwriter.newLine();
                pwriter.write(String.valueOf(p.isMine())); pwriter.newLine();
                if(!p.isMine())
                {
                    pwriter.write(p.getOwner()); pwriter.newLine();
                    pwriter.write((String) p.getOwnerID()); pwriter.newLine();
                }
                pwriter.write(String.valueOf(p.isCollaborative())); pwriter.newLine();
                for(Song song : p.getContent())
                {
                    pwriter.write(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                            + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getSpotify().getId()
                            + CACHE_SEPARATOR);
                    pwriter.newLine();
                }
                pwriter.close();
            }
            catch (IOException e) {e.printStackTrace();}
        }

        @Override
        public void addPlaylist(String name, OperationCallback callback, boolean isPublic, boolean isCollaborative)
        {
            HashMap<String, Object> params = new HashMap<>();
            params.put("name", name);
            params.put("public", isPublic);
            params.put("collaborative", isCollaborative);
            //params.put("description", desc);

            new Thread()
            {
                public void run()
                {
                    try
                    {
                        kaaes.spotify.webapi.android.models.Playlist p = spotifyApi.getService().createPlaylist(mePrivate.id, params);

                        //add playlist to RAM
                        Playlist playlist = new Playlist(name, new ArrayList<>());
                        if(isCollaborative) playlist.setCollaborative();
                        playlist.getSources().addSource(new SongSources.SongSource(p.id, SOURCE_SPOTIFY));
                        LibraryService.getPlaylists().add(playlist);
                        if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                        //add playlist to cache
                        cachePlaylist(playlist);

                        callback.onSucess(playlist);
                    }
                    catch(RetrofitError error)
                    {
                        if(error.getResponse() == null) {callback.onFailure(); return;}
                        if(error.getResponse().getStatus() == 401)
                        {
                            refreshSpotifyToken();
                            addPlaylist(name, callback, isPublic, isCollaborative);
                        }
                        else callback.onFailure();
                    }
                }
            }.start();
        }
        @Override
        public void removePlaylist(Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_SPOTIFY) {callback.onFailure(); return;}

            new Thread()
            {
                public void run()
                {
                    try
                    {
                        spotifyApi.getService().unfollowPlaylist(list.isMine() ? mePrivate.id : (String) list.getOwnerID(), (String) list.getSources().getSpotify().getId());

                        //remove playlist from RAM
                        LibraryService.getPlaylists().remove(list);
                        if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                        //remove playlist from cache
                        File thisPlaylist = new File(spotifyPlaylistsCache.getAbsolutePath() + "/" + list.getName());
                        thisPlaylist.delete();

                        callback.onSucess(null);
                    }
                    catch(RetrofitError error)
                    {
                        if(error.getResponse() == null) {callback.onFailure(); return;}
                        if(error.getResponse().getStatus() == 401)
                        {
                            refreshSpotifyToken();
                            removePlaylist(list, callback);
                        }
                        else callback.onFailure();
                    }
                }
            }.start();
        }

        public void addSongToLibrary(Song song, OperationCallback callback)
        {
            new Thread()
            {
                public void run()
                {
                    try
                    {
                        SongSources.SongSource spot = song.getSources().getSpotify();
                        if(spot == null)
                        {
                            if(!searchForSong(song)) {callback.onFailure(); return;}
                            spot = song.getSources().getSpotify();
                        }

                        spotifyApi.getService().addToMySavedTracks((String) spot.getId());

                        //add to library
                        if(song.isHandled())
                        {
                            //todo : find a better way (registersong is heavy) ; that is just lazyness
                            LibraryService.registerSong(song.getArtist().getName(),  song.getAlbum().getName(),
                                     song.getTrackNumber(), song.getDuration(), song.getName(), spot);
                        }

                        //add to cache
                        try
                        {
                            BufferedWriter writer = new BufferedWriter(new FileWriter(spotifyCacheFile, true));
                            writer.write(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                                    + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getSpotify().getId()
                                    + CACHE_SEPARATOR);
                            writer.newLine();
                            writer.close();
                        }
                        catch(IOException e) {}

                        callback.onSucess(null);
                    }
                    catch(RetrofitError error)
                    {
                        if(error.getResponse() == null) {callback.onFailure(); return;}
                        if(error.getResponse().getStatus() == 401)
                        {
                            refreshSpotifyToken();
                            addSongToLibrary(song, callback);
                        }
                        else callback.onFailure();
                    }
                }
            }.start();
        }
        public void removeSongFromLibrary(Song song, OperationCallback callback)
        {
            new Thread()
            {
                public void run()
                {
                    try
                    {
                        SongSources.SongSource spot = song.getSources().getSpotify();
                        if(spot == null) {callback.onFailure(); return;}
                        if(!spot.getLibrary()) {callback.onFailure(); return;}

                        spotifyApi.getService().removeFromMySavedTracks((String) spot.getId());

                        //remove from cache
                        try
                        {
                            StringBuilder newContent = new StringBuilder();

                            BufferedReader reader = new BufferedReader(new FileReader(spotifyCacheFile));
                            while(reader.ready())
                            {
                                String toAdd = (reader.readLine() + "\n");
                                if(toAdd.equals(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                                        + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getSpotify().getId()
                                        + CACHE_SEPARATOR))
                                    toAdd = "";

                                newContent.append(toAdd);
                            }
                            reader.close();

                            BufferedWriter writer = new BufferedWriter(new FileWriter(spotifyCacheFile));
                            writer.write(newContent.toString());
                            writer.close();
                        }
                        catch(IOException e) {}

                        //remove from library
                        LibraryService.unregisterSong(song, spot);

                        callback.onSucess(null);
                    }
                    catch(RetrofitError error)
                    {
                        if(error.getResponse() == null) {callback.onFailure(); return;}
                        if(error.getResponse().getStatus() == 401)
                        {
                            refreshSpotifyToken();
                            addSongToLibrary(song, callback);
                        }
                        else callback.onFailure();
                    }
                }
            }.start();
        }
        public void addAlbumToLibrary(Album album, OperationCallback callback)
        {
            callback.onFailure();
        }
        public void removeAlbumFromLibrary(Album album, OperationCallback callback)
        {
            callback.onFailure();
        }
    }
    public static class Deezer extends Source
    {
        public final String DEEZER_CLIENT_ID = "283144"; //nightly ID //release ID = "279742";
        public final SessionStore DEEZER_USER_SESSION = new SessionStore();
        public DeezerConnect deezerApi;
        private File deezerCacheFile;
        private File deezerPlaylistsCache;
        public User me;

        private ArrayList<LibraryObject> deezerCachedToLoadArt;

        public SourcePlayer player = new SourcePlayer()
        {
            TrackPlayer deezerPlayer;

            @Override
            public void init()
            {
                try
                {
                    deezerPlayer = new TrackPlayer((Application) LibraryService.appContext.getApplicationContext(), deezerApi, new WifiAndMobileNetworkStateChecker());
                    setListener(PlayerMediaPlayer.playerListener);
                }
                catch(Exception e)
                {
                    System.err.println("Deezer player error : " + e.getLocalizedMessage());
                    e.printStackTrace();
                    deezerPlayer = null;
                }
            }

            @Override
            public void setListener(PlayerListener listener)
            {
                deezerPlayer.addOnPlayerStateChangeListener(new OnPlayerStateChangeListener()
                {
                    @Override
                    public void onPlayerStateChange(PlayerState playerState, long l)
                    {
                        if(playerState.equals(PlayerState.PLAYBACK_COMPLETED))
                        {
                            listener.onSongCompletion();
                        }
                    }
                });
            }

            @Override
            public void play(PlayerCallback callback)
            {
                if(deezerPlayer == null) {if(callback != null) callback.onFailure(player); return;}
                if(deezerPlayer.getPlayerState() == PlayerState.RELEASED) {if(callback != null) callback.onFailure(player); return;}

                deezerPlayer.play();
                if(callback != null) callback.onSucess(player);
            }

            @Override
            public void pause(PlayerCallback callback)
            {
                if(deezerPlayer == null) {if(callback != null) callback.onFailure(player); return;}
                if(deezerPlayer.getPlayerState() == PlayerState.RELEASED) {if(callback != null) callback.onFailure(player); return;}

                deezerPlayer.pause();
                if(callback != null) callback.onSucess(player);
            }

            @Override
            public void playSong(Song song, PlayerCallback callback)
            {
                SongSources.SongSource deezer = song.getSources().getDeezer();
                if(deezer == null) {if(callback != null) callback.onFailure(player); return;}
                if(deezerPlayer == null) {if(callback != null) callback.onFailure(player); return;}
                if(deezerPlayer.getPlayerState() == PlayerState.RELEASED) {if(callback != null) callback.onFailure(player); return;}

                deezerPlayer.playTrack((long) deezer.getId());
                if(callback != null) callback.onSucess(player);
            }

            @Override
            public void seekTo(int msec)
            {
                if(deezerPlayer != null) deezerPlayer.seek(msec);
            }

            @Override
            public int getCurrentPosition()
            {
                return deezerPlayer == null ? 0 : (int) deezerPlayer.getPosition();
            }
        };

        Deezer()
        {
            super(R.drawable.ic_deezer, R.drawable.ic_deezer, "Deezer");
        }

        @Override
        public SourcePlayer getPlayer() {return player;}

        @Override
        public String getUserName() {return me == null ? "" : me.getName();}

        @Override
        public void initConfig(SharedPreferences accountsPrefs)
        {
            deezerCacheFile = new File(LibraryService.appContext.getCacheDir().getAbsolutePath() + "/deezer.cached");
            deezerPlaylistsCache = new File(LibraryService.appContext.getCacheDir().getAbsolutePath() + "/deezerPlaylists/");
            if(!deezerPlaylistsCache.exists()) deezerPlaylistsCache.mkdir();

            setPriority(accountsPrefs.getInt("deezer_prior", 0));

            //setup deezer api
            deezerApi = new DeezerConnect(LibraryService.appContext, DEEZER_CLIENT_ID);
            if(DEEZER_USER_SESSION.restore(deezerApi, LibraryService.appContext))
            {
                SOURCE_DEEZER.setAvailable(true);
                me = deezerApi.getCurrentUser();
                player.init();
            }
        }

        @Override
        public void disconnect()
        {
            setAvailable(false);
            setPriority(0);
            deezerApi.logout(LibraryService.appContext);
            DEEZER_USER_SESSION.clear(LibraryService.appContext);
            me = null;

            SharedPreferences accountsPrefs = LibraryService.appContext.getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = accountsPrefs.edit();
            editor.remove("deezer_prior");
            editor.apply();

            //remove cache
            deezerCacheFile.delete();
            if(deezerPlaylistsCache.exists())
            {
                for(File f : deezerPlaylistsCache.listFiles()) f.delete();
                deezerPlaylistsCache.delete();
            }

            //wait for resync
        }


        @Override
        public void registerCachedSongs()
        {
            if(!LibraryService.configured) return;

            deezerCachedToLoadArt = new ArrayList<>();
            System.out.println("[BLADE-DEEZER] Registering cached songs...");

            try
            {
                if(deezerCacheFile.exists())
                {
                    //deezer library
                    BufferedReader spr = new BufferedReader(new FileReader(deezerCacheFile));
                    while(spr.ready())
                    {
                        String[] tp = spr.readLine().split(CACHE_SEPARATOR);
                        Song song = LibraryService.registerSong(tp[2],  tp[1],
                                Integer.parseInt(tp[4]), Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(Long.parseLong(tp[6]), SOURCE_DEEZER));
                        song.setFormat(tp[3]);

                        if(!song.getAlbum().hasArt() && !song.getAlbum().getArtLoading())
                        {
                            //the image is supposed to be cached locally, so no need to provide URL
                            deezerCachedToLoadArt.add(song.getAlbum());
                            song.getAlbum().setArtLoading();
                        }
                    }
                    spr.close();

                    //deezer playlists
                    for(File f : deezerPlaylistsCache.listFiles())
                    {
                        ArrayList<Song> thisList = new ArrayList<>();
                        BufferedReader sppr = new BufferedReader(new FileReader(f));
                        Long id = Long.parseLong(sppr.readLine());
                        boolean isMine = Boolean.parseBoolean(sppr.readLine());
                        String owner = null; long ownerID = 0;
                        if(!isMine) {owner = sppr.readLine(); ownerID = Long.parseLong(sppr.readLine());}
                        boolean isCollab = Boolean.parseBoolean(sppr.readLine());
                        while(sppr.ready())
                        {
                            String[] tp = sppr.readLine().split(CACHE_SEPARATOR);

                            Song song = LibraryService.SAVE_PLAYLISTS_TO_LIBRARY ?
                                    LibraryService.registerSong(tp[2],  tp[1],  Integer.parseInt(tp[4]),
                                            Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(Long.parseLong(tp[6]), SOURCE_DEEZER))
                                    : LibraryService.getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                    new SongSources.SongSource(Long.parseLong(tp[6]), SOURCE_DEEZER), Integer.parseInt(tp[4]));
                            song.setFormat(tp[3]);
                            thisList.add(song);

                            if(!song.getAlbum().hasArt() && !song.getAlbum().getArtLoading())
                            {
                                //the image is supposed to be cached locally, so no need to provide URL
                                deezerCachedToLoadArt.add(song.getAlbum());
                                song.getAlbum().setArtLoading();
                            }
                        }
                        sppr.close();

                        Playlist p = new Playlist(f.getName(), thisList);
                        if(!isMine) p.setOwner(owner, ownerID);
                        if(isCollab) p.setCollaborative();
                        p.getSources().addSource(new SongSources.SongSource(id, SOURCE_DEEZER));
                        deezerCachedToLoadArt.add(p);
                        p.setArtLoading();
                        LibraryService.getPlaylists().add(p);
                    }
                }
            }
            catch(IOException e)
            {
                Log.println(Log.ERROR, "[BLADE-DEEZER]", "Cache restore : IOException");
                e.printStackTrace();
            }
            System.out.println("[BLADE-DEEZER] Cached songs registered.");
        }

        @Override
        public void loadCachedArts()
        {
            if(deezerCachedToLoadArt == null) return;

            for(LibraryObject alb : deezerCachedToLoadArt)
            {
                LibraryService.loadArt(alb, "", false);
            }

            deezerCachedToLoadArt = null;
        }

        @Override
        public void registerSongs()
        {
            if(!LibraryService.configured) return;
            if(!deezerApi.isSessionValid()) return;

            ArrayList<Song> deezerSongs = new ArrayList<>();
            ArrayList<Playlist> deezerPlaylists = new ArrayList<>();

            DeezerRequest requestTracks = DeezerRequestFactory.requestCurrentUserTracks();
            DeezerRequest requestAlbums = DeezerRequestFactory.requestCurrentUserAlbums();
            DeezerRequest requestArtists = DeezerRequestFactory.requestCurrentUserArtists();
            DeezerRequest requestPlaylist = DeezerRequestFactory.requestCurrentUserPlaylists();

            try
            {
                List<com.deezer.sdk.model.Track> tracks = (List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(requestTracks));
                for(com.deezer.sdk.model.Track t : tracks)
                {
                    Song s = LibraryService.registerSong(t.getArtist().getName(),  t.getAlbum().getTitle(),
                            t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
                    deezerSongs.add(s);
                    if(!s.getAlbum().hasArt())
                    {
                        LibraryService.loadArt(s.getAlbum(), t.getAlbum().getBigImageUrl(), false);
                    }
                }

                List<com.deezer.sdk.model.Album> albums = (List<com.deezer.sdk.model.Album>) JsonUtils.deserializeJson(deezerApi.requestSync(requestAlbums));
                for(com.deezer.sdk.model.Album album : albums)
                {
                    Album alb = null;
                    List<com.deezer.sdk.model.Track> albTracks = (List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(DeezerRequestFactory.requestAlbumTracks(album.getId())));
                    for(com.deezer.sdk.model.Track t : albTracks)
                    {
                        Song s = LibraryService.registerSong(t.getArtist().getName(),  album.getTitle(),
                                t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
                        deezerSongs.add(s);
                        if(alb == null) alb = s.getAlbum();
                    }

                    if(!alb.hasArt())
                    {
                        LibraryService.loadArt(alb, album.getBigImageUrl(), false);
                    }
                }

                List<com.deezer.sdk.model.Artist> artists = (List<com.deezer.sdk.model.Artist>) JsonUtils.deserializeJson(deezerApi.requestSync(requestArtists));
                for(com.deezer.sdk.model.Artist artist : artists)
                {
                    List<com.deezer.sdk.model.Album> artistAlbums = (List<com.deezer.sdk.model.Album>) JsonUtils.deserializeJson(deezerApi.requestSync(DeezerRequestFactory.requestArtistAlbums(artist.getId())));
                    for(com.deezer.sdk.model.Album album : artistAlbums)
                    {
                        Album alb = null;
                        List<com.deezer.sdk.model.Track> albTracks = (List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(DeezerRequestFactory.requestAlbumTracks(album.getId())));
                        for(com.deezer.sdk.model.Track t : albTracks)
                        {
                            Song s = LibraryService.registerSong(t.getArtist().getName(),  album.getTitle(),
                                    t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
                            deezerSongs.add(s);
                            if(alb == null) alb = s.getAlbum();
                        }

                        if(!alb.hasArt())
                        {
                            LibraryService.loadArt(alb, album.getBigImageUrl(), false);
                        }
                    }
                }

                String outJSON = deezerApi.requestSync(requestPlaylist);
                List<com.deezer.sdk.model.Playlist> playlists = (List<com.deezer.sdk.model.Playlist>) JsonUtils.deserializeJson(outJSON);

                //Parse playlists track numbers manually
                String[] outArr = outJSON.split("\"nb_tracks\":");
                ArrayList<Integer> trackNumbers = new ArrayList<>();
                for(int i = 1; i<outArr.length ; i++)
                {
                    int off = 1;
                    while(outArr[i].charAt(off) != ',') off++;
                    trackNumbers.add(Integer.parseInt(outArr[i].substring(0, off)));
                }

                for(int i = 0; i<playlists.size(); i++)
                {
                    com.deezer.sdk.model.Playlist playlist = playlists.get(i);
                    ArrayList<Song> thisList = new ArrayList<>();

                    for(int off = 0; off <= trackNumbers.get(i); off+=25)
                    {
                        DeezerRequest thispRequest = DeezerRequestFactory.requestPlaylistTracks(playlist.getId());
                        thispRequest.addParam("limit", "25");
                        thispRequest.addParam("index", String.valueOf(off));
                        List<com.deezer.sdk.model.Track> playlistTracks = (List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(thispRequest));

                        for(com.deezer.sdk.model.Track t : playlistTracks)
                        {
                            Song s;
                            if(LibraryService.SAVE_PLAYLISTS_TO_LIBRARY)
                                s = LibraryService.registerSong(t.getArtist().getName(), t.getAlbum().getTitle(),
                                        t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
                            else
                                s = LibraryService.getSongHandle(t.getTitle(), t.getAlbum().getTitle(), t.getArtist().getName(),
                                        t.getDuration()*1000, new SongSources.SongSource(t.getId(), SOURCE_DEEZER), t.getTrackPosition());

                            //get albumart for this song
                            if(!s.getAlbum().hasArt())
                            {
                                LibraryService.loadArt(s.getAlbum(), t.getAlbum().getBigImageUrl(), false);
                            }

                            thisList.add(s);
                        }
                    }

                    Playlist list = new Playlist(playlist.getTitle(), thisList);
                    if(playlist.isCollaborative()) list.setCollaborative();
                    if(!playlist.getCreator().equals(me)) list.setOwner(playlist.getCreator().getName(), playlist.getCreator().getId());
                    list.getSources().addSource(new SongSources.SongSource(playlist.getId(), SOURCE_DEEZER));
                    LibraryService.getPlaylists().add(list);
                    deezerPlaylists.add(list);
                    if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();
                    LibraryService.loadArt(list, playlist.getSmallImageUrl(), false);
                }

                //Cache deezer songs and playlists
                try
                {
                    //library songs
                    BufferedWriter bw = new BufferedWriter(new FileWriter((deezerCacheFile)));
                    for(Song song : deezerSongs)
                    {
                        bw.write(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                                + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getDeezer().getId()
                                + CACHE_SEPARATOR);
                        bw.newLine();
                    }
                    bw.close();

                    //playlists
                    for(File f : deezerPlaylistsCache.listFiles()) f.delete();
                    for(Playlist p : deezerPlaylists)
                    {
                        cachePlaylist(p);
                    }
                }
                catch(IOException e)
                {
                    Log.println(Log.ERROR, "[BLADE-DEEZER]", "Error while writing cache !");
                }
            }
            catch(Exception e)
            {
                e.printStackTrace();
                System.err.println("DEEZER ERROR MESSAGE : " + e.getLocalizedMessage());
            }
        }

        @Override
        public List<LibraryObject> query(String query)
        {
            ArrayList<LibraryObject> tr = new ArrayList<>();
            HashMap<Album, String> urls = new HashMap<>();

            if(isAvailable())
            {
                try
                {
                    //request from deezer
                    List<com.deezer.sdk.model.Track> tracks = (List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(DeezerRequestFactory.requestSearchTracks(query)));
                    List<com.deezer.sdk.model.Album> albums = (List<com.deezer.sdk.model.Album>) JsonUtils.deserializeJson(deezerApi.requestSync(DeezerRequestFactory.requestSearchAlbums(query)));
                    //List<com.deezer.sdk.model.Artist> artists = (List<com.deezer.sdk.model.Artist>) JsonUtils.deserializeJson(deezerApi.requestSync(DeezerRequestFactory.requestSearchArtists(query)));

                    //handle returned data
                    for(com.deezer.sdk.model.Track t : tracks)
                    {
                        Song currentSong = LibraryService.getSongHandle(t.getTitle(), t.getAlbum().getTitle(), t.getArtist().getName(), t.getDuration()*1000, new SongSources.SongSource(t.getId(), SOURCE_DEEZER), t.getTrackPosition());
                        tr.add(currentSong);

                        if(!currentSong.getAlbum().hasArt())
                        {
                            urls.put(currentSong.getAlbum(), t.getAlbum().getBigImageUrl());
                        }
                    }
                    for(com.deezer.sdk.model.Album alb : albums)
                    {
                        Album album = null;
                        List<com.deezer.sdk.model.Track> albTracks = (List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(DeezerRequestFactory.requestAlbumTracks(alb.getId())));
                        for(com.deezer.sdk.model.Track t : albTracks)
                        {
                            Song currentSong = LibraryService.getSongHandle(t.getTitle(), t.getAlbum().getTitle(), t.getArtist().getName(), t.getDuration()*1000, new SongSources.SongSource(t.getId(), SOURCE_DEEZER), t.getTrackPosition());
                            if(album == null) album = currentSong.getAlbum();
                        }

                        if(!album.hasArt())
                        {
                            urls.put(album, alb.getBigImageUrl());
                        }
                    }
                }
                catch(Exception e) {}
            }

            new Thread()
            {
                public void run()
                {
                    Looper.prepare();

                    for(Album a : urls.keySet())
                    {
                        LibraryService.loadArt(a, urls.get(a), false);
                    }
                }
            }.start();
            return tr;
        }

        @Override
        public void addSongsToPlaylist(List<Song> songs, Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_DEEZER) {callback.onFailure(); return;}
            if(!list.isMine() && !list.isCollaborative()) {callback.onFailure(); return;}

            new Thread()
            {
                public void run()
                {
                    try
                    {
                        Looper.prepare();

                        ArrayList<Long> ids = new ArrayList<>();
                        for(Song s : songs)
                        {
                            SongSources.SongSource deezer = s.getSources().getDeezer();
                            if(deezer == null)
                            {
                                if(SOURCE_DEEZER.searchForSong(s))
                                    deezer = s.getSources().getDeezer();
                                else {songs.remove(s); continue;}
                            }

                            ids.add((long) deezer.getId());
                        }
                        if(ids.size() == 0) {callback.onFailure(); return;}

                        DeezerRequest request = DeezerRequestFactory.requestPlaylistAddTracks((long) list.getSources().getDeezer().getId(), ids);
                        boolean b = Boolean.parseBoolean(deezerApi.requestSync(request));
                        if(!b) {callback.onFailure(); return;}

                        //add songs to RAM list
                        list.getContent().addAll(songs);

                        //add song to cached list
                        cachePlaylist(list);

                        callback.onSucess(null);
                    }
                    catch(Exception e)
                    {
                        callback.onFailure();
                        e.printStackTrace();
                    }
                }
            }.start();
        }
        @Override
        public void removeSongFromPlaylist(Song song, Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_DEEZER) {callback.onFailure(); return;}
            if(!list.isMine() && !list.isCollaborative()) {callback.onFailure(); return;}

            ArrayList<Long> ids = new ArrayList<>();
            ids.add((long) song.getSources().getDeezer().getId());

            DeezerRequest request = DeezerRequestFactory.requestPlaylistRemoveTracks((long) list.getSources().getDeezer().getId(), ids);

            new Thread()
            {
                public void run()
                {
                    try
                    {
                        Looper.prepare();

                        System.out.println(deezerApi.requestSync(request));

                        //remove song from RAM list
                        list.getContent().remove(song);
                        if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                        //remove song from cached list (by recaching it)
                        cachePlaylist(list);

                        callback.onSucess(null);
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                        callback.onFailure();
                    }
                }
            }.start();
        }

        @Override
        public boolean searchForSong(Song s)
        {
            if(s.getSources().getDeezer() != null) return true;

            DeezerRequest search = DeezerRequestFactory.requestSearchTracks("track:\"" + s.getTitle() + "\" album:\"" + s.getAlbum().getName() + "\" artist:\"" + s.getArtist().getName() + "\"");
            search.addParam("limit", "1");
            try
            {
                com.deezer.sdk.model.Track t = ((List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(Source.SOURCE_DEEZER.deezerApi.requestSync(search))).get(0);
                if (t != null)
                {
                    SongSources.SongSource source = new SongSources.SongSource(t.getId(), Source.SOURCE_DEEZER);
                    s.getSources().addSource(source);
                    s.getAlbum().getSources().addSource(source);
                    s.getArtist().getSources().addSource(source);
                    return true;
                }
            }
            catch (Exception e) {e.printStackTrace();} //ignored

            return false;
        }

        private void cachePlaylist(Playlist p)
        {
            try
            {
                File thisPlaylist = new File(deezerPlaylistsCache.getAbsolutePath() + "/" + p.getName());
                thisPlaylist.createNewFile();
                BufferedWriter pwriter = new BufferedWriter(new FileWriter(thisPlaylist));
                pwriter.write(String.valueOf((long) p.getSources().getDeezer().getId())); pwriter.newLine();
                pwriter.write(String.valueOf(p.isMine())); pwriter.newLine();
                if(!p.isMine())
                {
                    pwriter.write(p.getOwner()); pwriter.newLine();
                    pwriter.write(String.valueOf((long) p.getOwnerID())); pwriter.newLine();
                }
                pwriter.write(String.valueOf(p.isCollaborative())); pwriter.newLine();
                for(Song song : p.getContent())
                {
                    pwriter.write(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                            + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getDeezer().getId()
                            + CACHE_SEPARATOR);
                    pwriter.newLine();
                }
                pwriter.close();
            }
            catch (IOException e) {e.printStackTrace();}
        }

        @Override
        public void addPlaylist(String name, OperationCallback callback, boolean isPublic, boolean isCollaborative)
        {
            new Thread()
            {
                public void run()
                {
                    try
                    {
                        DeezerRequest request = DeezerRequestFactory.requestCurrentUserCreatePlaylist(name);
                        com.deezer.sdk.model.Playlist playlist = (com.deezer.sdk.model.Playlist) JsonUtils.deserializeJson(deezerApi.requestSync(request));
                        DeezerRequest request1 = DeezerRequestFactory.requestPlaylistUpdate(playlist.getId(), name, "", isPublic, isCollaborative);
                        deezerApi.requestSync(request1);

                        //add playlist to RAM
                        Playlist list = new Playlist(name, new ArrayList<>());
                        if(isCollaborative) list.setCollaborative();
                        list.getSources().addSource(new SongSources.SongSource(playlist.getId(), SOURCE_DEEZER));
                        LibraryService.getPlaylists().add(list);
                        if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                        //add playlist to cache
                        cachePlaylist(list);

                        callback.onSucess(list);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        callback.onFailure();
                    }
                }
            }.start();
        }
        @Override
        public void removePlaylist(Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_DEEZER) {callback.onFailure(); return;}

            new Thread()
            {
                public void run()
                {
                    try
                    {
                        deezerApi.requestSync(DeezerRequestFactory.requestPlaylistDelete((long) list.getSources().getDeezer().getId()));

                        //remove playlist from RAM
                        LibraryService.getPlaylists().remove(list);
                        if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                        //remove playlist from cache
                        File thisPlaylist = new File(deezerPlaylistsCache.getAbsolutePath() + "/" + list.getName());
                        thisPlaylist.delete();

                        callback.onSucess(null);
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                        callback.onFailure();
                    }
                }
            }.start();
        }

        public void addSongToLibrary(Song song, OperationCallback callback)
        {
            new Thread()
            {
                public void run()
                {
                    try
                    {
                        SongSources.SongSource deezer = song.getSources().getDeezer();
                        if(deezer == null)
                        {
                            if(!searchForSong(song)) {callback.onFailure(); return;}
                            deezer = song.getSources().getDeezer();
                        }

                        deezerApi.requestSync(DeezerRequestFactory.requestCurrentUserAddTrack((long) deezer.getId()));

                        //add to library
                        if(song.isHandled())
                        {
                            //todo : find a better way (registersong is heavy) ; that is just lazyness
                            LibraryService.registerSong(song.getArtist().getName(), song.getAlbum().getName(),
                                    song.getTrackNumber(), song.getDuration(), song.getName(), deezer);
                        }

                        //add to cache
                        try
                        {
                            BufferedWriter writer = new BufferedWriter(new FileWriter(deezerCacheFile, true));
                            writer.write(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                                    + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + deezer.getId()
                                    + CACHE_SEPARATOR);
                            writer.newLine();
                            writer.close();
                        }
                        catch(IOException e) {}

                        callback.onSucess(null);
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                        callback.onFailure();
                    }
                }
            }.start();
        }
        public void removeSongFromLibrary(Song song, OperationCallback callback)
        {
            new Thread()
            {
                public void run()
                {
                    try
                    {
                        SongSources.SongSource deezer = song.getSources().getDeezer();
                        if(deezer == null) {callback.onFailure(); return;}
                        if(!deezer.getLibrary()) {callback.onFailure(); return;}

                        deezerApi.requestSync(DeezerRequestFactory.requestCurrentUserRemoveTrack((long) deezer.getId()));

                        //remove from cache
                        try
                        {
                            StringBuilder newContent = new StringBuilder();

                            BufferedReader reader = new BufferedReader(new FileReader(deezerCacheFile));
                            while(reader.ready())
                            {
                                String toAdd = (reader.readLine() + "\n");
                                if(toAdd.equals(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                                        + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + deezer.getId()
                                        + CACHE_SEPARATOR))
                                    toAdd = "";

                                newContent.append(toAdd);
                            }
                            reader.close();

                            BufferedWriter writer = new BufferedWriter(new FileWriter(deezerCacheFile));
                            writer.write(newContent.toString());
                            writer.close();
                        }
                        catch(IOException e) {}

                        //remove from library
                        LibraryService.unregisterSong(song, deezer);

                        callback.onSucess(null);
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                        callback.onFailure();
                    }
                }
            }.start();
        }
        public void addAlbumToLibrary(Album album, OperationCallback callback)
        {
            callback.onFailure();
        }
        public void removeAlbumFromLibrary(Album album, OperationCallback callback)
        {
            callback.onFailure();
        }
    }
}
