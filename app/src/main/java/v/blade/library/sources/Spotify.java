package v.blade.library.sources;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import com.spotify.sdk.android.player.*;
import kaaes.spotify.webapi.android.SpotifyApi;
import kaaes.spotify.webapi.android.SpotifyError;
import kaaes.spotify.webapi.android.SpotifyService;
import kaaes.spotify.webapi.android.models.*;
import retrofit.RetrofitError;
import v.blade.R;
import v.blade.library.Album;
import v.blade.library.Playlist;
import v.blade.library.*;
import v.blade.player.PlayerMediaPlayer;
import v.blade.ui.settings.SettingsActivity;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static v.blade.library.LibraryService.CACHE_SEPARATOR;

public class Spotify extends Source
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
                        public void onLoginFailed(com.spotify.sdk.android.player.Error error)
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
                public void onPlaybackError(com.spotify.sdk.android.player.Error error)
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
                public void onError(com.spotify.sdk.android.player.Error error) {callback.onFailure(player);}
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
                public void onError(com.spotify.sdk.android.player.Error error) {if(callback != null) callback.onFailure(player);}
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
                public void onError(com.spotify.sdk.android.player.Error error) {if(callback != null) callback.onFailure(player);}
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
            return spotifyPlayer == null ? 0 : (spotifyPlayer.getPlaybackState() == null ? 0 : (int) spotifyPlayer.getPlaybackState().positionMs);
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
                            Integer.parseInt(tp[4]), 0, Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(tp[6], SOURCE_SPOTIFY));
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
                if(spotifyPlaylistsCache.exists())
                {
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
                                    LibraryService.registerSong(tp[2],  tp[1],  Integer.parseInt(tp[4]), 0,
                                            Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(tp[6], SOURCE_SPOTIFY))
                                    : LibraryService.getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                    new SongSources.SongSource(tp[6], SOURCE_SPOTIFY), Integer.parseInt(tp[4]), 0);
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
                            t.track_number, 0, t.duration_ms, t.name, new SongSources.SongSource(t.id, SOURCE_SPOTIFY));
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
                                t.track_number, 0, t.duration_ms, t.name, new SongSources.SongSource(t.id, SOURCE_SPOTIFY));
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
                                        t.track_number, 0, t.duration_ms, t.name, new SongSources.SongSource(t.id, SOURCE_SPOTIFY));
                            else
                                s = LibraryService.getSongHandle(t.name, t.album.name, t.artists.get(0).name, t.duration_ms, new SongSources.SongSource(t.id, SOURCE_SPOTIFY),
                                        t.track_number, 0);

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
                    Song song = LibraryService.getSongHandle(t.name, t.album.name, t.artists.get(0).name, t.duration_ms, new SongSources.SongSource(t.id, SOURCE_SPOTIFY), t.track_number, 0);
                    tr.add(song);

                    //System.out.println("[SART] Song : " + song.getName() + " - " + song.getAlbum() + " - " + song.getArtist() + " , hasArt = " + song.getAlbum().hasArt() + " art = " + song.getAlbum().getArtUri());
                    if(!song.getAlbum().hasArt())
                    {
                        if(t.album.images.get(0) != null)
                            urls.put(song.getAlbum(), t.album.images.get(0).url);
                        //System.out.println("[SART] Album " + song.getAlbum() + " (artist = " + song.getArtist() + ") : img = " + t.album.images.get(0).url);
                    }
                }
                for(kaaes.spotify.webapi.android.models.AlbumSimple a : albums.albums.items)
                {
                    Album album = null;
                    Pager<Track> albumTracks = spotifyApi.getService().getAlbumTracks(a.id);
                    for(Track t : albumTracks.items)
                    {
                        Song currentSong = LibraryService.getSongHandle(t.name, a.name, t.artists.get(0).name, t.duration_ms, new SongSources.SongSource(t.id, SOURCE_SPOTIFY), t.track_number, 0);
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
                    System.out.println("[SART] Loading albumArt for " + a.getName() + " - " + a.getArtist().getName() + " ; img = " + urls.get(a));
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
                catch(Exception ex)
                {
                    if(mePrivate == null) checkAndRefreshSpotifyToken();
                    callback.onFailure();
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

        //check connection
        if(mePrivate == null)
        {
            Toast.makeText(LibraryService.appContext, R.string.spotify_connection_error, Toast.LENGTH_SHORT).show();
            return;
        }

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
                                song.getTrackNumber(), song.getYear(), song.getDuration(), song.getName(), spot);
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
                        String toCompare = song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                                + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getSpotify().getId()
                                + CACHE_SEPARATOR + "\n";
                        while(reader.ready())
                        {
                            String toAdd = (reader.readLine() + "\n");
                            if(toAdd.equals(toCompare)) toAdd = "";
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