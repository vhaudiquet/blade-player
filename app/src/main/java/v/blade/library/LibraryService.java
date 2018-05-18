package v.blade.library;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.util.LongSparseArray;
import com.deezer.sdk.network.connect.DeezerConnect;
import com.deezer.sdk.network.connect.SessionStore;
import com.deezer.sdk.network.request.DeezerRequest;
import com.deezer.sdk.network.request.DeezerRequestFactory;
import com.deezer.sdk.network.request.JsonUtils;
import kaaes.spotify.webapi.android.SpotifyApi;
import kaaes.spotify.webapi.android.SpotifyError;
import kaaes.spotify.webapi.android.SpotifyService;
import kaaes.spotify.webapi.android.models.*;
import retrofit.RetrofitError;
import v.blade.ui.settings.SettingsActivity;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

/*
* LibraryService is the service that handles library caching/restore and synchronyzation
* It is a background service, but during operations such as restoring/synchronization it appears as a foreground service
 */
public class LibraryService extends Service
{
    private static final String CACHE_SEPARATOR = "##";

    /* user preferences */
    public static boolean SAVE_PLAYLISTS_TO_LIBRARY;
    public static boolean REGISTER_SONGS_BETTER_SOURCES;

    /* library */
    private static List<Artist> artists = Collections.synchronizedList(new ArrayList<Artist>());
    private static List<Album> albums = Collections.synchronizedList(new ArrayList<Album>());
    private static List<Song> songs = Collections.synchronizedList(new ArrayList<Song>());
    private static List<Playlist> playlists = Collections.synchronizedList(new ArrayList<Playlist>());

    //song handles, that are not part of library but playable (from web sources)
    private static List<Song> handles = Collections.synchronizedList(new ArrayList<Song>());

    private static HashMap<String, ArrayList<Song>> songsByName = new HashMap<>();
    /* spotify specific */
    public static final String SPOTIFY_CLIENT_ID = "2f95bc7168584e7aa67697418a684bae";
    public static final String SPOTIFY_REDIRECT_URI = "http://valou3433.fr/";
    public static String SPOTIFY_USER_TOKEN;
    public static String SPOTIFY_REFRESH_TOKEN;
    public static final SpotifyApi spotifyApi = new SpotifyApi();

    /* deezer specific */
    public static final String DEEZER_CLIENT_ID = "279742";
    public static final SessionStore DEEZER_USER_SESSION = new SessionStore();
    public static DeezerConnect deezerApi;

    /* list callbacks */
    public interface UserLibraryCallback{void onLibraryChange();}
    public static UserLibraryCallback currentCallback;

    private static File artCacheDir;
    private static File spotifyCacheFile;
    private static File spotifyPlaylistsCache;
    private static File deezerCacheFile;
    private static File deezerPlaylistsCache;

    public static List<Artist> getArtists() {return artists;}
    public static List<Album> getAlbums() {return albums;}
    public static List<Song> getSongs() {return songs;}
    public static List<Playlist> getPlaylists() {return playlists;}

    //TEMP
    private static Context serviceContext;

    @Override
    public void onCreate()
    {
        System.out.println("[BLADE] LibraryService onCreate");
        super.onCreate();

        serviceContext = this;
        configureLibrary();

        Thread loader = new Thread()
        {
            @Override
            public void run()
            {
                // get local library from ContentProvider
                registerLocalSongs();
                System.out.println("[BLADE-DEBUG] Local songs registered.");

                // get library from disk (if cached)
                registerCachedSongs();
                System.out.println("[BLADE-DEBUG] Cached songs registered.");

                sortLibrary();
            }
        };
        loader.setName("LIBRARY_LOADER");
        loader.start();
    }
    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    /*
    * Register local library with android ContentProvider
    * Called at every service start
     */
    public static void registerLocalSongs()
    {
        //empty lists
        artists.clear(); albums.clear(); songs.clear(); playlists.clear(); songsByName.clear();

        /* get content resolver and init temp sorted arrays */
        final ContentResolver musicResolver = serviceContext.getContentResolver();
        LongSparseArray<Album> idsorted_albums = new LongSparseArray<>();
        LongSparseArray<Song> idsorted_songs = new LongSparseArray<>();

        /* let's get all music files of the user, and register them and their attributes */
        Cursor musicCursor = musicResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null);
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

                Song s = registerSong(thisArtist, artistId, thisAlbum, albumId, albumTrack, thisDuration, thisTitle, new SongSources.SongSource(thisId, SongSources.SOURCE_LOCAL_LIB));
                s.setFormat(musicCursor.getString(formatColumn));
                idsorted_songs.put(thisId, s);
                if(idsorted_albums.get(albumId) == null) idsorted_albums.put(albumId, s.getAlbum());
            }
            while (musicCursor.moveToNext());
            musicCursor.close();
        }

        /* we also need to get playlists on device */
        Cursor playlistCursor = musicResolver.query(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, null, null, null, null);
        if(playlistCursor!=null && playlistCursor.moveToFirst())
        {
            int idColumn = playlistCursor.getColumnIndex(MediaStore.Audio.Playlists._ID);
            int nameColumn = playlistCursor.getColumnIndex(MediaStore.Audio.Playlists.NAME);

            do
            {
                long thisId = playlistCursor.getLong(idColumn);
                String thisName = playlistCursor.getString(nameColumn);

                //now we have to resolve the content of this playlist
                ArrayList<Song> thisList = new ArrayList<>();
                Cursor thisPlaylistCursor = musicResolver.query(MediaStore.Audio.Playlists.Members.getContentUri("external", thisId), null, null, null, null);
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
                list.getSources().addSource(new SongSources.SongSource(thisId, SongSources.SOURCE_LOCAL_LIB));
                playlists.add(list);
                if(currentCallback != null) currentCallback.onLibraryChange();
            } while(playlistCursor.moveToNext());
            playlistCursor.close();
        }

        /* now let's get all albumarts */
        Cursor albumCursor = musicResolver.query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, null, null, null, null, null);
        if(albumCursor!=null && albumCursor.moveToFirst())
        {
            int idCol = albumCursor.getColumnIndex(MediaStore.Audio.Albums._ID);
            int artCol = albumCursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART);

            do
            {
                long thisId = albumCursor.getLong(idCol);
                String path = albumCursor.getString(artCol);

                Album a = idsorted_albums.get(thisId);
                if(a != null)
                {
                    loadAlbumArt(a, path, true);
                }
            } while (albumCursor.moveToNext());
            albumCursor.close();
        }
    }

    /*
    * Register web libraries that were previously cached
    * Called at every service start
     */
    public void registerCachedSongs()
    {
        try
        {
            if(spotifyCacheFile.exists())
            {
                //spotify library
                BufferedReader spr = new BufferedReader(new FileReader(spotifyCacheFile));
                while(spr.ready())
                {
                    String[] tp = spr.readLine().split(CACHE_SEPARATOR);
                    Song song = registerSong(tp[2], 0, tp[1], 0,
                    Integer.parseInt(tp[4]), Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(tp[6], SongSources.SOURCE_SPOTIFY));
                    song.setFormat(tp[3]);

                    if(!song.getAlbum().hasAlbumArt())
                    {
                        //the image is supposed to be cached locally, so no need to provide URL
                        loadAlbumArt(song.getAlbum(), "", false);
                    }
                }
                spr.close();

                //spotify playlists
                for(File f : spotifyPlaylistsCache.listFiles())
                {
                    ArrayList<Song> thisList = new ArrayList<>();
                    BufferedReader sppr = new BufferedReader(new FileReader(f));
                    while(sppr.ready())
                    {
                        String[] tp = sppr.readLine().split(CACHE_SEPARATOR);
                        Song song = SAVE_PLAYLISTS_TO_LIBRARY ?
                                registerSong(tp[2], 0, tp[1], 0, Integer.parseInt(tp[4]),
                                        Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(tp[6], SongSources.SOURCE_SPOTIFY))
                                : getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                new SongSources.SongSource(tp[6], SongSources.SOURCE_SPOTIFY), Integer.parseInt(tp[4]));
                        song.setFormat(tp[3]);

                        thisList.add(song);
                        if(!song.getAlbum().hasAlbumArt())
                        {
                            //the image is supposed to be cached locally, so no need to provide URL
                            loadAlbumArt(song.getAlbum(), "", false);
                        }
                    }
                    sppr.close();

                    Playlist p = new Playlist(f.getName(), thisList);
                    p.getSources().addSource(new SongSources.SongSource(0, SongSources.SOURCE_SPOTIFY));
                    playlists.add(p);
                }
            }

            if(deezerCacheFile.exists())
            {
                //deezer library
                BufferedReader spr = new BufferedReader(new FileReader(deezerCacheFile));
                while(spr.ready())
                {
                    String[] tp = spr.readLine().split(CACHE_SEPARATOR);
                    Song song = registerSong(tp[2], 0, tp[1], 0,
                            Integer.parseInt(tp[4]), Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(Long.parseLong(tp[6]), SongSources.SOURCE_DEEZER));
                    song.setFormat(tp[3]);

                    if(!song.getAlbum().hasAlbumArt())
                    {
                        //the image is supposed to be cached locally, so no need to provide URL
                        loadAlbumArt(song.getAlbum(), "", false);
                    }
                }
                spr.close();

                //deezer playlists
                for(File f : deezerPlaylistsCache.listFiles())
                {
                    ArrayList<Song> thisList = new ArrayList<>();
                    BufferedReader sppr = new BufferedReader(new FileReader(f));
                    while(sppr.ready())
                    {
                        String[] tp = sppr.readLine().split(CACHE_SEPARATOR);

                        Song song = SAVE_PLAYLISTS_TO_LIBRARY ?
                                registerSong(tp[2], 0, tp[1], 0, Integer.parseInt(tp[4]),
                                        Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(Long.parseLong(tp[6]), SongSources.SOURCE_DEEZER))
                                : getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                new SongSources.SongSource(Long.parseLong(tp[6]), SongSources.SOURCE_DEEZER), Integer.parseInt(tp[4]));
                        song.setFormat(tp[3]);
                        thisList.add(song);

                        if(!song.getAlbum().hasAlbumArt())
                        {
                            //the image is supposed to be cached locally, so no need to provide URL
                            loadAlbumArt(song.getAlbum(), "", false);
                        }
                    }
                    sppr.close();

                    Playlist p = new Playlist(f.getName(), thisList);
                    p.getSources().addSource(new SongSources.SongSource(0, SongSources.SOURCE_DEEZER));
                    playlists.add(p);
                }
            }
        }
        catch(IOException e)
        {
            Log.println(Log.ERROR, "[BLADE-CACHE]", "Cache restore : IOException");
            e.printStackTrace();
        }
    }

    /*
     * Configure the APIs, the preferences, ...
     */
    private void configureLibrary()
    {
        /* init the lists (to make sure they are empty) */
        if(songs.size() > 0) return;

        //init cache dirs
        artCacheDir = new File(getCacheDir().getAbsolutePath() + "/albumArts");
        if(!artCacheDir.exists()) artCacheDir.mkdir();
        spotifyCacheFile = new File(getCacheDir().getAbsolutePath() + "/spotify.cached");
        spotifyPlaylistsCache = new File(getCacheDir().getAbsolutePath() + "/spotifyPlaylists/");
        if(!spotifyPlaylistsCache.exists()) spotifyPlaylistsCache.mkdir();
        deezerCacheFile = new File(getCacheDir().getAbsolutePath() + "/deezer.cached");
        deezerPlaylistsCache = new File(getCacheDir().getAbsolutePath() + "/deezerPlaylists/");
        if(!deezerPlaylistsCache.exists()) deezerPlaylistsCache.mkdir();

        //get preferences
        SharedPreferences accountsPrefs = getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
        SharedPreferences generalPrefs = getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);

        SAVE_PLAYLISTS_TO_LIBRARY = generalPrefs.getBoolean("save_playlist_to_library", false);
        REGISTER_SONGS_BETTER_SOURCES = generalPrefs.getBoolean("register_better_sources", true);
        SongSources.SOURCE_LOCAL_LIB.setPriority(999);
        SongSources.SOURCE_SPOTIFY.setPriority(accountsPrefs.getInt("spotify_prior", 0));
        SongSources.SOURCE_DEEZER.setPriority(accountsPrefs.getInt("deezer_prior", 0));

        //setup spotify api
        if(SPOTIFY_USER_TOKEN == null)
        {
            SPOTIFY_USER_TOKEN = accountsPrefs.getString("spotify_token", null);
            SPOTIFY_REFRESH_TOKEN = accountsPrefs.getString("spotify_refresh_token", null);
        }
        if(SPOTIFY_USER_TOKEN != null)
        {
            spotifyApi.setAccessToken(SPOTIFY_USER_TOKEN);
            SongSources.SOURCE_SPOTIFY.setAvailable(true);
        }

        //setup deezer api
        deezerApi = new DeezerConnect(this.getApplicationContext(), DEEZER_CLIENT_ID);
        if(DEEZER_USER_SESSION.restore(deezerApi, this.getApplicationContext()))
        {
            SongSources.SOURCE_DEEZER.setAvailable(true);
        }

        /*
        Thread webLoaderThread = new Thread()
        {
            @Override
            public void run()
            {
                Looper.prepare();

                //load source priority 1 library
                registerDeezerSongs();
                System.out.println("[BLADE-DEBUG] Deezer songs registered.");

                //load source priority 2 library
                registerSpotifySongs();
                System.out.println("[BLADE-DEBUG] Spotify songs registered.");

                sortLibrary();

                registerSongBetterSources();
            }
        };
        webLoaderThread.setName("webLoaderThread");
        webLoaderThread.setDaemon(true);
        webLoaderThread.start();
        */
    }

    /*
     * Registers a song in user library
     */
    private static Song registerSong(String artist, long artistId, String album, long albumId,
                                     int albumTrack, long duration, String name, SongSources.SongSource source)
    {
        //check if the song is already registered
        ArrayList<Song> snames = songsByName.get(name.toLowerCase());
        if(snames != null)
        {
            for(Song s : snames)
            {
                if(s.getArtist().getName().equalsIgnoreCase(artist) && s.getAlbum().getName().equalsIgnoreCase(album))
                {
                    System.out.println("[REGISTER] Found song " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName() + " SOURCE " + source.getSource());
                    s.getSources().addSource(source);
                    s.getAlbum().getSources().addSource(source);
                    s.getArtist().getSources().addSource(source);
                    return s;
                }
            }
        }

        //check if the song is already handled
        for(Song s : handles)
        {
            if(s.getTitle().equalsIgnoreCase(name) && s.getArtist().getName().equalsIgnoreCase(artist) && s.getAlbum().getName().equalsIgnoreCase(album))
            {
                s.getSources().addSource(source);
                if(s.getAlbum().isHandled()) {albums.add(s.getAlbum()); s.getAlbum().setHandled(false);}
                if(s.getArtist().isHandled()) {artists.add(s.getArtist()); s.getArtist().setHandled(false);}
                s.setHandled(false);
                handles.remove(s);
                System.out.println("[REGISTER] Found handled song " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName() + " SOURCE " + source.getSource());
                return s;
            }
        }

        Artist songArtist = null;
        synchronized (artists)
        {for (Artist art : artists) if (art.getName().equalsIgnoreCase(artist)) songArtist = art;}
        if(songArtist == null)
        {
            songArtist = new Artist(artist);
            artists.add(songArtist);
        }
        songArtist.getSources().addSource(source);

        Album songAlbum = null;
        synchronized (songArtist.getAlbums())
        {
            for(int i = 0;i<songArtist.getAlbums().size();i++)
                if(songArtist.getAlbums().get(i).getName().equalsIgnoreCase(album)) songAlbum = songArtist.getAlbums().get(i);
        }

        if(songAlbum == null)
        {
            songAlbum = new Album(album, songArtist);
            albums.add(songAlbum);
            songArtist.addAlbum(songAlbum);
        }
        songAlbum.getSources().addSource(source);

        Song song = new Song(name, songArtist, songAlbum, albumTrack, duration);
        song.getSources().addSource(source);
        songAlbum.addSong(song);
        songs.add(song);

        if(currentCallback != null) currentCallback.onLibraryChange();

        //register song by name
        if(snames != null) snames.add(song);
        else {ArrayList<Song> sn = new ArrayList<>(); sn.add(song); songsByName.put(name.toLowerCase(), sn);}

        System.out.println("[REGISTER] Registered : " + name + " - " + songAlbum.getName() + " - " + songArtist.getName() + " - SOURCE " + source.getSource());
        return song;
    }

    public static void registerSpotifySongs()
    {
        if(SPOTIFY_USER_TOKEN == null) return;

        // list used for spotify cache
        ArrayList<Song> spotifySongs = new ArrayList<>();
        ArrayList<Playlist> spotifyPlaylists = new ArrayList<>();

        SpotifyService service = spotifyApi.getService();
        try
        {
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
                    Song s = registerSong(t.artists.get(0).name, 0, t.album.name, 0,
                            t.track_number, t.duration_ms, t.name, new SongSources.SongSource(t.id, SongSources.SOURCE_SPOTIFY));
                    spotifySongs.add(s);
                    if(!s.getAlbum().hasAlbumArt())
                    {
                        if(t.album.images != null && t.album.images.size() >= 1)
                        {
                            Image albumImage = t.album.images.get(0);
                            loadAlbumArt(s.getAlbum(), albumImage.url, false);
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
                        Song s = registerSong(t.artists.get(0).name, 0, alb.name, 0,
                                t.track_number, t.duration_ms, t.name, new SongSources.SongSource(t.id, SongSources.SOURCE_SPOTIFY));
                        spotifySongs.add(s);
                        if (savedAlbum == null) savedAlbum = s.getAlbum();
                    }

                    if(!savedAlbum.hasAlbumArt())
                    {
                        if(alb.images != null && alb.images.size() >= 1)
                        {
                            Image albumImage = alb.images.get(0);
                            loadAlbumArt(savedAlbum, albumImage.url, false);
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
                    map.put("fields", "total");
                    int trackNbr = service.getPlaylistTracks(playlistBase.owner.id, playlistBase.id, map).total;
                    map.remove("fields");

                    int poffset = 0;
                    while(trackNbr > 0)
                    {
                        map.put("offset", poffset);
                        Pager<PlaylistTrack> tracks = service.getPlaylistTracks(playlistBase.owner.id, playlistBase.id, map);

                        for(PlaylistTrack pt : tracks.items)
                        {
                            Track t = pt.track;
                            Song s;
                            if(SAVE_PLAYLISTS_TO_LIBRARY)
                                s = registerSong(t.artists.get(0).name, 0, t.album.name, 0,
                                        t.track_number, t.duration_ms, t.name, new SongSources.SongSource(t.id, SongSources.SOURCE_SPOTIFY));
                            else
                                s = getSongHandle(t.name, t.album.name, t.artists.get(0).name, t.duration_ms, new SongSources.SongSource(t.id, SongSources.SOURCE_SPOTIFY),
                                        t.track_number);

                            //get albumart for this song
                            if(!s.getAlbum().hasAlbumArt())
                            {
                                if(t.album.images != null && t.album.images.size() >= 1)
                                {
                                    Image albumImage = t.album.images.get(0);
                                    loadAlbumArt(s.getAlbum(), albumImage.url, false);
                                }
                            }

                            thisList.add(s);
                        }

                        poffset+=100;
                        trackNbr-=100;
                    }

                    Playlist list = new Playlist(playlistBase.name, thisList);
                    list.getSources().addSource(new SongSources.SongSource(playlistBase.id, SongSources.SOURCE_SPOTIFY));
                    spotifyPlaylists.add(list);
                    playlists.add(list);
                    if(currentCallback != null) currentCallback.onLibraryChange();
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
                for(File f : spotifyPlaylistsCache.listFiles()) f.delete();
                for(Playlist p : spotifyPlaylists)
                {
                    File thisPlaylist = new File(spotifyPlaylistsCache.getAbsolutePath() + "/" + p.getName());
                    thisPlaylist.createNewFile();
                    BufferedWriter pwriter = new BufferedWriter(new FileWriter(thisPlaylist));
                    for(Song song : p.getContent())
                    {
                        pwriter.write(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                                + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getSpotify().getId()
                                + CACHE_SEPARATOR);
                        pwriter.newLine();
                    }
                    pwriter.close();
                }
            }
            catch(IOException e)
            {
                Log.println(Log.ERROR, "[BLADE-SPOTIFY]", "Error while writing cache !");
            }
        }
        catch (RetrofitError error)
        {
            if(error.getResponse() == null) return;
            if(error.getResponse().getStatus() == 401)
            {
                Log.println(Log.INFO, "[BLADE-SPOTIFY]", "Actualizing token.");
                refreshSpotifyToken();
                registerSpotifySongs();
                return;
            }

            error.printStackTrace();
            System.err.println("ERROR BODY : " + error.getBody());
            SpotifyError spotifyError = SpotifyError.fromRetrofitError(error);
            spotifyError.printStackTrace();
            System.err.println("SPOTIFY ERROR DETAILS : " + spotifyError.getErrorDetails());
        }
    }
    public static void registerDeezerSongs()
    {
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
                Song s = registerSong(t.getArtist().getName(), 0, t.getAlbum().getTitle(), 0,
                        t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SongSources.SOURCE_DEEZER));
                deezerSongs.add(s);
                if(!s.getAlbum().hasAlbumArt())
                {
                    loadAlbumArt(s.getAlbum(), t.getAlbum().getBigImageUrl(), false);
                }
            }

            List<com.deezer.sdk.model.Album> albums = (List<com.deezer.sdk.model.Album>) JsonUtils.deserializeJson(deezerApi.requestSync(requestAlbums));
            for(com.deezer.sdk.model.Album album : albums)
            {
                Album alb = null;
                List<com.deezer.sdk.model.Track> albTracks = (List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(DeezerRequestFactory.requestAlbumTracks(album.getId())));
                for(com.deezer.sdk.model.Track t : albTracks)
                {
                    Song s = registerSong(t.getArtist().getName(), 0, album.getTitle(), 0,
                            t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SongSources.SOURCE_DEEZER));
                    deezerSongs.add(s);
                    if(alb == null) alb = s.getAlbum();
                }

                if(!alb.hasAlbumArt())
                {
                    loadAlbumArt(alb, album.getBigImageUrl(), false);
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
                        Song s = registerSong(t.getArtist().getName(), 0, album.getTitle(), 0,
                                t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SongSources.SOURCE_DEEZER));
                        deezerSongs.add(s);
                        if(alb == null) alb = s.getAlbum();
                    }

                    if(!alb.hasAlbumArt())
                    {
                        loadAlbumArt(alb, album.getBigImageUrl(), false);
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
                        if(SAVE_PLAYLISTS_TO_LIBRARY)
                            s = registerSong(t.getArtist().getName(), 0, t.getAlbum().getTitle(), 0,
                                    t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SongSources.SOURCE_DEEZER));
                        else
                            s = getSongHandle(t.getTitle(), t.getAlbum().getTitle(), t.getArtist().getName(),
                                    t.getDuration()*1000, new SongSources.SongSource(t.getId(), SongSources.SOURCE_DEEZER), t.getTrackPosition());

                        //get albumart for this song
                        if(!s.getAlbum().hasAlbumArt())
                        {
                            loadAlbumArt(s.getAlbum(), t.getAlbum().getBigImageUrl(), false);
                        }

                        thisList.add(s);
                    }
                }

                Playlist list = new Playlist(playlist.getTitle(), thisList);
                list.getSources().addSource(new SongSources.SongSource(playlist.getId(), SongSources.SOURCE_DEEZER));
                LibraryService.playlists.add(list);
                deezerPlaylists.add(list);
                if(currentCallback != null) currentCallback.onLibraryChange();
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
                    File thisPlaylist = new File(deezerPlaylistsCache.getAbsolutePath() + "/" + p.getName());
                    System.out.println("Caching in " + thisPlaylist.getAbsolutePath());
                    thisPlaylist.createNewFile();
                    BufferedWriter pwriter = new BufferedWriter(new FileWriter(thisPlaylist));
                    for(Song song : p.getContent())
                    {
                        pwriter.write(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                                + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getDeezer().getId()
                                + CACHE_SEPARATOR);
                        pwriter.newLine();
                    }
                    pwriter.close();
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

    /*
     * If the option is enabled, Blade will try to find a better source for all the songs added by WebService
     * Example : you added a spotify album, but deezer prior > spotify ; Blade will load that album from Deezer
     */
    public static void registerSongBetterSources()
    {
        if(!REGISTER_SONGS_BETTER_SOURCES) return;

        SongSources.Source bestSource = SongSources.SOURCE_DEEZER.getPriority() > SongSources.SOURCE_SPOTIFY.getPriority() ? SongSources.SOURCE_DEEZER : SongSources.SOURCE_SPOTIFY;
        if(!bestSource.isAvailable()) return;

        if(bestSource == SongSources.SOURCE_SPOTIFY)
        {
            //search all deezer songs on spotify
            ArrayList<Song> spotifySongs = new ArrayList<>();
            for(Song s : songs)
            {
                if(s.getSources().getSourceByPriority(0).getSource() == SongSources.SOURCE_DEEZER)
                {
                    //query spotify for this song
                    HashMap<String, Object> args = new HashMap<>();
                    args.put("limit", 1);
                    List<Track> t = spotifyApi.getService().searchTracks(s.getTitle() + " album:" + s.getAlbum().getName() + " artist:" + s.getArtist().getName()).tracks.items;
                    if(t != null && t.size() > 0 && t.get(0) != null)
                    {
                        SongSources.SongSource source = new SongSources.SongSource(t.get(0).id, SongSources.SOURCE_SPOTIFY);
                        s.getSources().addSource(source);
                        s.getArtist().getSources().addSource(source);
                        s.getAlbum().getSources().addSource(source);
                        spotifySongs.add(s);
                    }
                }
            }
            //also search for songs in playlist
            if(!SAVE_PLAYLISTS_TO_LIBRARY)
            {
                for(Playlist p : playlists)
                {
                    for(Song s : p.getContent())
                    {
                        if(s.getSources().getSourceByPriority(0).getSource() == SongSources.SOURCE_SPOTIFY)
                        {
                            //query spotify for this song
                            HashMap<String, Object> args = new HashMap<>();
                            args.put("limit", 1);
                            List<Track> t = spotifyApi.getService().searchTracks(s.getTitle() + " album:" + s.getAlbum().getName() + " artist:" + s.getArtist().getName()).tracks.items;
                            if(t != null && t.size() > 0 && t.get(0) != null)
                            {
                                SongSources.SongSource source = new SongSources.SongSource(t.get(0).id, SongSources.SOURCE_SPOTIFY);
                                s.getSources().addSource(source);
                                s.getArtist().getSources().addSource(source);
                                s.getAlbum().getSources().addSource(source);
                                spotifySongs.add(s);
                            }
                        }
                    }
                }
            }

            //cache theses
            try
            {
                BufferedWriter spw = new BufferedWriter(new FileWriter(spotifyCacheFile));
                for(Song song : spotifySongs)
                {
                    spw.append(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                            + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getDeezer().getId()
                            + CACHE_SEPARATOR + "\n");
                }
                spw.close();
            }
            catch(IOException e) {e.printStackTrace();}

        }
        else if(bestSource == SongSources.SOURCE_DEEZER)
        {
            ArrayList<Song> deezerSongs = new ArrayList<>();

            //search all spotify songs on deezer
            synchronized (songs)
            {
                for (Song s : songs)
                {
                    if (s.getSources().getSourceByPriority(0).getSource() == SongSources.SOURCE_SPOTIFY)
                    {
                        //query deezer for this song
                        DeezerRequest search = DeezerRequestFactory.requestSearchTracks("track:\"" + s.getTitle() + "\" album:\"" + s.getAlbum().getName() + "\" artist:\"" + s.getArtist().getName() + "\"");
                        search.addParam("limit", "1");
                        try
                        {
                            com.deezer.sdk.model.Track t = ((List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(search))).get(0);
                            if (t != null)
                            {
                                //System.out.println("Found better source for : " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName());
                                SongSources.SongSource source = new SongSources.SongSource(t.getId(), SongSources.SOURCE_DEEZER);
                                s.getSources().addSource(source);
                                s.getAlbum().getSources().addSource(source);
                                s.getArtist().getSources().addSource(source);
                                deezerSongs.add(s);
                            }
                        }
                        catch (Exception e) {} //ignored
                    }
                }
            }
            //also search for songs in playlist
            if(!SAVE_PLAYLISTS_TO_LIBRARY)
            {
                synchronized (playlists)
                {
                    for(Playlist p : playlists)
                    {
                        synchronized (p.getContent())
                        {
                            for(Song s : p.getContent())
                            {
                                if(s.getSources().getSourceByPriority(0).getSource() == SongSources.SOURCE_SPOTIFY)
                                {
                                    //query deezer for this song
                                    DeezerRequest search = DeezerRequestFactory.requestSearchTracks("track:\"" + s.getTitle() + "\" album:\"" + s.getAlbum().getName() + "\" artist:\"" + s.getArtist().getName() + "\"");
                                    try
                                    {
                                        com.deezer.sdk.model.Track t = ((List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(search))).get(0);
                                        if (t != null)
                                        {
                                            //System.out.println("Found better source for : " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName());
                                            SongSources.SongSource source = new SongSources.SongSource(t.getId(), SongSources.SOURCE_DEEZER);
                                            s.getSources().addSource(source);
                                            s.getAlbum().getSources().addSource(source);
                                            s.getArtist().getSources().addSource(source);
                                            deezerSongs.add(s);
                                        }
                                    }
                                    catch(Exception e) {} //ignored
                                }
                            }
                        }
                    }
                }
            }

            //cache theses
            try
            {
                BufferedWriter spw = new BufferedWriter(new FileWriter(deezerCacheFile));
                for(Song song : deezerSongs)
                {
                    spw.append(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                            + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getDeezer().getId()
                            + CACHE_SEPARATOR + "\n");
                }
                spw.close();
            }
            catch(IOException e) {e.printStackTrace();}
        }

        if(currentCallback != null) currentCallback.onLibraryChange();
    }

    public static void sortLibrary()
    {
        /* sort collection by alphabetical order */

        synchronized (songs)
        {Collections.sort(songs, new Comparator<Song>(){
            public int compare(Song a, Song b){ return a.getTitle().compareTo(b.getTitle()); }
        });}
        synchronized (albums)
        {Collections.sort(albums, new Comparator<Album>(){
            public int compare(Album a, Album b){ return a.getName().compareTo(b.getName());
            }
        });}
        synchronized (artists)
        {Collections.sort(artists, new Comparator<Artist>(){
            public int compare(Artist a, Artist b){ return a.getName().compareTo(b.getName());
            }
        });}
        synchronized (playlists)
        {Collections.sort(playlists, new Comparator<Playlist>(){
            public int compare(Playlist a, Playlist b){ return a.getName().compareTo(b.getName());
            }
        });}
        if(currentCallback != null) currentCallback.onLibraryChange();

        /* sort each album per tracks */
        synchronized (albums)
        {
            for(Album alb : albums)
            {
                synchronized (alb.getSongs())
                {Collections.sort(alb.getSongs(), new Comparator<Song>() {
                    @Override
                    public int compare(Song o1, Song o2) {return o1.getTrackNumber() - o2.getTrackNumber();}
                });}
            }
        }

        if(currentCallback != null) currentCallback.onLibraryChange();
    }

    /*
     * Query the library for objects
     */
    public static ArrayList<LibraryObject> query(String s)
    {
        ArrayList<LibraryObject> tr = new ArrayList<>();
        String q = s.toLowerCase();

        for(Song song : songs)
            if(song.getTitle().toLowerCase().contains(q))
                tr.add(song);
        for(Album alb : albums)
            if(alb.getName().toLowerCase().contains(q))
                tr.add(alb);
        for(Artist artist : artists)
            if(artist.getName().toLowerCase().contains(q))
                tr.add(artist);
        for(Playlist playlist : playlists)
            if(playlist.getName().toLowerCase().contains(q))
                tr.add(playlist);

        return tr;
    }

    private static Song getSongHandle(String name, String album, String artist, long duration, SongSources.SongSource source, int track)
    {
        //if song is already registered, return song from library
        ArrayList<Song> snames = songsByName.get(name.toLowerCase());
        if(snames != null)
        {
            //check if the song is already registered
            for(Song s : snames)
            {
                if(s.getArtist().getName().equalsIgnoreCase(artist) && s.getAlbum().getName().equalsIgnoreCase(album))
                {
                    s.getSources().addSource(source);
                    s.getAlbum().getSources().addSource(source);
                    s.getArtist().getSources().addSource(source);
                    System.out.println("[HANDLE] Found registered song : " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName() + " - SOURCE " + source.getSource());
                    return s;
                }
            }
        }
        for(Song s : handles)
            if(s.getTitle().equalsIgnoreCase(name) && s.getArtist().getName().equalsIgnoreCase(artist) && s.getAlbum().getName().equalsIgnoreCase(album))
            {
                s.getSources().addSource(source);
                System.out.println("[HANDLE] Found handled song : " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName() + " SOURCE" + source.getSource());
                return s;
            }

        //else create song object
        Artist songArtist = null;
        synchronized (artists)
        {for(Artist art : artists) if(art.getName().equalsIgnoreCase(artist)) songArtist = art;}
        if(songArtist == null) {songArtist = new Artist(artist); songArtist.setHandled(true);}
        songArtist.getSources().addSource(source);

        Album songAlbum = null;
        synchronized (albums)
        {for(Album alb : albums) if(alb.getName().equalsIgnoreCase(album)) songAlbum = alb;}
        if(songAlbum == null) {songAlbum = new Album(album, songArtist); songAlbum.setHandled(true);}
        songAlbum.getSources().addSource(source);

        Song s = new Song(name, songArtist, songAlbum, track, duration);
        s.getSources().addSource(source);
        s.setHandled(true);
        handles.add(s);
        System.out.println("[HANDLE] Handled : " + name + " - " + songAlbum.getName() + " - " + songArtist.getName() + " SOURCE " + source.getSource());
        return s;
    }

    private static void loadAlbumArt(Album alb, String path, boolean local)
    {
        if(local)
        {
            BitmapFactory.Options options = new BitmapFactory.Options();

            //decode file bounds
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            //calculate resize to do
            int inSampleSize = calculateSampleSize(options, Album.minatureSize, Album.minatureSize);

            //load miniature
            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            Bitmap toSet = BitmapFactory.decodeFile(path, options);

            if(toSet != null) alb.setAlbumArt(path, toSet);
        }
        else
        {
            File toSave = new File(artCacheDir.getAbsolutePath() + "/" + alb.getName() + ".png");
            if(!toSave.exists())
            {
                try
                {
                    URLConnection connection = new URL(path).openConnection();
                    connection.setUseCaches(true);
                    OutputStream fos = new FileOutputStream(toSave);
                    InputStream nis = connection.getInputStream();

                    //copy stream
                    byte[] buffer = new byte[4096];
                    while(true)
                    {
                        int count = nis.read(buffer, 0, 4096);
                        if(count == -1) break;
                        fos.write(buffer, 0, count);
                    }

                    connection.getInputStream().close();
                }
                catch(Exception e)
                {
                    Log.println(Log.WARN, "[BLADE]", "Exception on decoding album image for album " + alb.getName() + " : " + path);
                    return;
                }
            }
            loadAlbumArt(alb, toSave.getPath(), true);
        }
    }
    private static int calculateSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight)
    {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if(height > reqHeight || width > reqWidth)
        {
            final int halfHeight = height/2;
            final int halfWidth = width/2;

            while((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth)
                inSampleSize*=2;
        }

        return inSampleSize;
    }

    private static void refreshSpotifyToken()
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
                    SharedPreferences pref = serviceContext.getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
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
}
