package v.blade.library;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
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
import v.blade.R;
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
    @Override public String toString() {return name;}

    public abstract List<LibraryObject> query(String query);
    public abstract void registerCachedSongs();
    public abstract void registerSongs();
    public abstract void initConfig(SharedPreferences accountsPrefs);

    public static Source SOURCE_LOCAL_LIB = new Source(R.drawable.ic_local, 0, "LOCAL")
    {
        @Override
        public List<LibraryObject> query(String query) {return new ArrayList<>();}

        @Override
        public void initConfig(SharedPreferences accountsPrefs)
        {
            setPriority(999);
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

            //empty lists
            LibraryService.getArtists().clear();
            LibraryService.getAlbums().clear();
            LibraryService.getSongs().clear();
            LibraryService.getPlaylists().clear();
            LibraryService.songsByName.clear();

            /* get content resolver and init temp sorted arrays */
            final ContentResolver musicResolver = LibraryService.appContext.getContentResolver();
            LongSparseArray<Album> idsorted_albums = new LongSparseArray<>();
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

                    Song s = LibraryService.registerSong(thisArtist, artistId, thisAlbum, albumId, albumTrack, thisDuration, thisTitle, new SongSources.SongSource(thisId, SOURCE_LOCAL_LIB));
                    s.setFormat(musicCursor.getString(formatColumn));
                    idsorted_songs.put(thisId, s);
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

                do
                {
                    long thisId = playlistCursor.getLong(idColumn);
                    String thisName = playlistCursor.getString(nameColumn);

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
                    LibraryService.getPlaylists().add(list);
                    if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();
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
                        LibraryService.loadAlbumArt(a, path, true);
                    }
                } while (albumCursor.moveToNext());
                albumCursor.close();
            }
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
        private File spotifyCacheFile;
        private File spotifyPlaylistsCache;

        Spotify()
        {
            super(R.drawable.ic_spotify, R.drawable.ic_spotify_logo, "SPOTIFY");
        }

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
                            System.out.println("SPOTIFY : " + spotifyApi.getService().getMe().email);
                        }
                        catch(RetrofitError e)
                        {
                            if(e.getResponse() != null && e.getResponse().getStatus() == 401)
                            {
                                Log.println(Log.INFO, "[BLADE-SPOTIFY]", "Actualizing token.");
                                refreshSpotifyToken();
                            }
                        }
                    }
                }.start();
                try {Thread.sleep(100);} catch(InterruptedException e) {} // wait for token refresh (TODO : better)

                setAvailable(true);
            }
        }

        @Override
        public void registerCachedSongs()
        {
            if(!LibraryService.configured) return;

            try
            {
                if(spotifyCacheFile.exists())
                {
                    //spotify library
                    BufferedReader spr = new BufferedReader(new FileReader(spotifyCacheFile));
                    while(spr.ready())
                    {
                        String[] tp = spr.readLine().split(CACHE_SEPARATOR);
                        Song song = LibraryService.registerSong(tp[2], 0, tp[1], 0,
                                Integer.parseInt(tp[4]), Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(tp[6], SOURCE_SPOTIFY));
                        song.setFormat(tp[3]);

                        if(!song.getAlbum().hasAlbumArt())
                        {
                            //the image is supposed to be cached locally, so no need to provide URL
                            LibraryService.loadAlbumArt(song.getAlbum(), "", false);
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
                            Song song = LibraryService.SAVE_PLAYLISTS_TO_LIBRARY ?
                                    LibraryService.registerSong(tp[2], 0, tp[1], 0, Integer.parseInt(tp[4]),
                                            Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(tp[6], SOURCE_SPOTIFY))
                                    : LibraryService.getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                    new SongSources.SongSource(tp[6], SOURCE_SPOTIFY), Integer.parseInt(tp[4]));
                            song.setFormat(tp[3]);
                            thisList.add(song);

                            if(!song.getAlbum().hasAlbumArt())
                            {
                                //the image is supposed to be cached locally, so no need to provide URL
                                LibraryService.loadAlbumArt(song.getAlbum(), "", false);
                            }
                        }
                        sppr.close();

                        Playlist p = new Playlist(f.getName(), thisList);
                        p.getSources().addSource(new SongSources.SongSource(0, SOURCE_SPOTIFY));
                        LibraryService.getPlaylists().add(p);
                    }
                }
            }
            catch(IOException e)
            {
                Log.println(Log.ERROR, "[BLADE-SPOTIFY]", "Cache restore : IOException");
                e.printStackTrace();
            }
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
                        Song s = LibraryService.registerSong(t.artists.get(0).name, 0, t.album.name, 0,
                                t.track_number, t.duration_ms, t.name, new SongSources.SongSource(t.id, SOURCE_SPOTIFY));
                        spotifySongs.add(s);
                        if(!s.getAlbum().hasAlbumArt())
                        {
                            if(t.album.images != null && t.album.images.size() >= 1)
                            {
                                Image albumImage = t.album.images.get(0);
                                LibraryService.loadAlbumArt(s.getAlbum(), albumImage.url, false);
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
                            Song s = LibraryService.registerSong(t.artists.get(0).name, 0, alb.name, 0,
                                    t.track_number, t.duration_ms, t.name, new SongSources.SongSource(t.id, SOURCE_SPOTIFY));
                            spotifySongs.add(s);
                            if (savedAlbum == null) savedAlbum = s.getAlbum();
                        }

                        if(!savedAlbum.hasAlbumArt())
                        {
                            if(alb.images != null && alb.images.size() >= 1)
                            {
                                Image albumImage = alb.images.get(0);
                                LibraryService.loadAlbumArt(savedAlbum, albumImage.url, false);
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
                                if(LibraryService.SAVE_PLAYLISTS_TO_LIBRARY)
                                    s = LibraryService.registerSong(t.artists.get(0).name, 0, t.album.name, 0,
                                            t.track_number, t.duration_ms, t.name, new SongSources.SongSource(t.id, SOURCE_SPOTIFY));
                                else
                                    s = LibraryService.getSongHandle(t.name, t.album.name, t.artists.get(0).name, t.duration_ms, new SongSources.SongSource(t.id, SOURCE_SPOTIFY),
                                            t.track_number);

                                //get albumart for this song
                                if(!s.getAlbum().hasAlbumArt())
                                {
                                    if(t.album.images != null && t.album.images.size() >= 1)
                                    {
                                        Image albumImage = t.album.images.get(0);
                                        LibraryService.loadAlbumArt(s.getAlbum(), albumImage.url, false);
                                    }
                                }

                                thisList.add(s);
                            }

                            poffset+=100;
                            trackNbr-=100;
                        }

                        Playlist list = new Playlist(playlistBase.name, thisList);
                        list.getSources().addSource(new SongSources.SongSource(playlistBase.id, SOURCE_SPOTIFY));
                        spotifyPlaylists.add(list);
                        LibraryService.getPlaylists().add(list);
                        if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();
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
                    registerSongs();
                    return;
                }

                error.printStackTrace();
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

                        if(!song.getAlbum().hasAlbumArt())
                        {
                            if(t.album.images.get(0) != null)
                                LibraryService.loadAlbumArt(song.getAlbum(), t.album.images.get(0).url, false);
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

                        if(!album.hasAlbumArt())
                        {
                            if(a.images != null && a.images.size() >= 1)
                            {
                                Image albumImage = a.images.get(0);
                                if(albumImage != null)
                                    LibraryService.loadAlbumArt(album, albumImage.url, false);
                            }
                        }
                    }
                }
            }
            catch(RetrofitError e)
            {
                if(e.getResponse().getStatus() == 401)
                {
                    refreshSpotifyToken();
                    return query(query);
                }
                e.printStackTrace();
            }
            return tr;
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
    }
    public static class Deezer extends Source
    {
        public final String DEEZER_CLIENT_ID = "279742";
        public final SessionStore DEEZER_USER_SESSION = new SessionStore();
        public DeezerConnect deezerApi;
        private File deezerCacheFile;
        private File deezerPlaylistsCache;

        Deezer()
        {
            super(R.drawable.ic_deezer, R.drawable.ic_deezer, "DEEZER");
        }

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
            }
        }

        @Override
        public void registerCachedSongs()
        {
            if(!LibraryService.configured) return;

            try
            {
                if(deezerCacheFile.exists())
                {
                    //deezer library
                    BufferedReader spr = new BufferedReader(new FileReader(deezerCacheFile));
                    while(spr.ready())
                    {
                        String[] tp = spr.readLine().split(CACHE_SEPARATOR);
                        Song song = LibraryService.registerSong(tp[2], 0, tp[1], 0,
                                Integer.parseInt(tp[4]), Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(Long.parseLong(tp[6]), SOURCE_DEEZER));
                        song.setFormat(tp[3]);

                        if(!song.getAlbum().hasAlbumArt())
                        {
                            //the image is supposed to be cached locally, so no need to provide URL
                            LibraryService.loadAlbumArt(song.getAlbum(), "", false);
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

                            Song song = LibraryService.SAVE_PLAYLISTS_TO_LIBRARY ?
                                    LibraryService.registerSong(tp[2], 0, tp[1], 0, Integer.parseInt(tp[4]),
                                            Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(Long.parseLong(tp[6]), SOURCE_DEEZER))
                                    : LibraryService.getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                    new SongSources.SongSource(Long.parseLong(tp[6]), SOURCE_DEEZER), Integer.parseInt(tp[4]));
                            song.setFormat(tp[3]);
                            thisList.add(song);

                            if(!song.getAlbum().hasAlbumArt())
                            {
                                //the image is supposed to be cached locally, so no need to provide URL
                                LibraryService.loadAlbumArt(song.getAlbum(), "", false);
                            }
                        }
                        sppr.close();

                        Playlist p = new Playlist(f.getName(), thisList);
                        p.getSources().addSource(new SongSources.SongSource(0, SOURCE_DEEZER));
                        LibraryService.getPlaylists().add(p);
                    }
                }
            }
            catch(IOException e)
            {
                Log.println(Log.ERROR, "[BLADE-DEEZER]", "Cache restore : IOException");
                e.printStackTrace();
            }
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
                    Song s = LibraryService.registerSong(t.getArtist().getName(), 0, t.getAlbum().getTitle(), 0,
                            t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
                    deezerSongs.add(s);
                    if(!s.getAlbum().hasAlbumArt())
                    {
                        LibraryService.loadAlbumArt(s.getAlbum(), t.getAlbum().getBigImageUrl(), false);
                    }
                }

                List<com.deezer.sdk.model.Album> albums = (List<com.deezer.sdk.model.Album>) JsonUtils.deserializeJson(deezerApi.requestSync(requestAlbums));
                for(com.deezer.sdk.model.Album album : albums)
                {
                    Album alb = null;
                    List<com.deezer.sdk.model.Track> albTracks = (List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(DeezerRequestFactory.requestAlbumTracks(album.getId())));
                    for(com.deezer.sdk.model.Track t : albTracks)
                    {
                        Song s = LibraryService.registerSong(t.getArtist().getName(), 0, album.getTitle(), 0,
                                t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
                        deezerSongs.add(s);
                        if(alb == null) alb = s.getAlbum();
                    }

                    if(!alb.hasAlbumArt())
                    {
                        LibraryService.loadAlbumArt(alb, album.getBigImageUrl(), false);
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
                            Song s = LibraryService.registerSong(t.getArtist().getName(), 0, album.getTitle(), 0,
                                    t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
                            deezerSongs.add(s);
                            if(alb == null) alb = s.getAlbum();
                        }

                        if(!alb.hasAlbumArt())
                        {
                            LibraryService.loadAlbumArt(alb, album.getBigImageUrl(), false);
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
                                s = LibraryService.registerSong(t.getArtist().getName(), 0, t.getAlbum().getTitle(), 0,
                                        t.getTrackPosition(), t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
                            else
                                s = LibraryService.getSongHandle(t.getTitle(), t.getAlbum().getTitle(), t.getArtist().getName(),
                                        t.getDuration()*1000, new SongSources.SongSource(t.getId(), SOURCE_DEEZER), t.getTrackPosition());

                            //get albumart for this song
                            if(!s.getAlbum().hasAlbumArt())
                            {
                                LibraryService.loadAlbumArt(s.getAlbum(), t.getAlbum().getBigImageUrl(), false);
                            }

                            thisList.add(s);
                        }
                    }

                    Playlist list = new Playlist(playlist.getTitle(), thisList);
                    list.getSources().addSource(new SongSources.SongSource(playlist.getId(), SOURCE_DEEZER));
                    LibraryService.getPlaylists().add(list);
                    deezerPlaylists.add(list);
                    if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();
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

        @Override
        public List<LibraryObject> query(String query)
        {
            ArrayList<LibraryObject> tr = new ArrayList<>();

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

                        if(!currentSong.getAlbum().hasAlbumArt())
                        {
                            LibraryService.loadAlbumArt(currentSong.getAlbum(), t.getAlbum().getBigImageUrl(), false);
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

                        if(!album.hasAlbumArt())
                        {
                            LibraryService.loadAlbumArt(album, alb.getBigImageUrl(), false);
                        }
                    }
                }
                catch(Exception e) {}
            }

            return tr;
        }
    }
}
