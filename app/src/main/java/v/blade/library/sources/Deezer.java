package v.blade.library.sources;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.deezer.sdk.model.Track;
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
import v.blade.R;
import v.blade.library.*;
import v.blade.player.PlayerMediaPlayer;
import v.blade.ui.settings.SettingsActivity;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static v.blade.library.LibraryService.CACHE_SEPARATOR;

public class Deezer extends Source
{
    public final String DEEZER_CLIENT_ID = "279742";
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
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run()
                        {
                            if(playerState.equals(PlayerState.PLAYBACK_COMPLETED))
                            {
                                listener.onSongCompletion();
                            }
                        }
                    });
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
                            Integer.parseInt(tp[4]), 0, Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(Long.parseLong(tp[6]), SOURCE_DEEZER));
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
                                        0, Long.parseLong(tp[5]), tp[0], new SongSources.SongSource(Long.parseLong(tp[6]), SOURCE_DEEZER))
                                : LibraryService.getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                new SongSources.SongSource(Long.parseLong(tp[6]), SOURCE_DEEZER), Integer.parseInt(tp[4]), 0);
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
            List<Track> tracks = (List<com.deezer.sdk.model.Track>) JsonUtils.deserializeJson(deezerApi.requestSync(requestTracks));
            for(com.deezer.sdk.model.Track t : tracks)
            {
                Song s = LibraryService.registerSong(t.getArtist().getName(),  t.getAlbum().getTitle(),
                        t.getTrackPosition(), 0, t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
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
                            t.getTrackPosition(), 0, t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
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
                                t.getTrackPosition(), 0, t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
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
                                    t.getTrackPosition(), 0, t.getDuration()*1000, t.getTitle(), new SongSources.SongSource(t.getId(), SOURCE_DEEZER));
                        else
                            s = LibraryService.getSongHandle(t.getTitle(), t.getAlbum().getTitle(), t.getArtist().getName(),
                                    t.getDuration()*1000, new SongSources.SongSource(t.getId(), SOURCE_DEEZER), t.getTrackPosition(), 0);

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
                    Song currentSong = LibraryService.getSongHandle(t.getTitle(), t.getAlbum().getTitle(), t.getArtist().getName(), t.getDuration()*1000, new SongSources.SongSource(t.getId(), SOURCE_DEEZER), t.getTrackPosition(), 0);
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
                        Song currentSong = LibraryService.getSongHandle(t.getTitle(), t.getAlbum().getTitle(), t.getArtist().getName(), t.getDuration()*1000, new SongSources.SongSource(t.getId(), SOURCE_DEEZER), t.getTrackPosition(), 0);
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
                                song.getTrackNumber(), 0, song.getDuration(), song.getName(), deezer);
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