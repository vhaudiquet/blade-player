package v.blade.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Looper;
import android.util.Log;
import retrofit.RetrofitError;
import v.blade.ui.PlayerConnection;
import v.blade.ui.settings.SettingsActivity;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

/*
* LibraryService is the class that handles library caching/restore and synchronyzation
* It was a background service, but it works actually better as a static class that just calls a Thread to do async work
 */
public class LibraryService
{
    private static final boolean LOG_REGISTER_SONG = false;
    static final String CACHE_SEPARATOR = "##";

    /* user preferences */
    public static boolean configured = false;
    public static boolean SAVE_PLAYLISTS_TO_LIBRARY;
    public static boolean REGISTER_SONGS_BETTER_SOURCES;

    /* library */
    private static final List<Artist> artists = Collections.synchronizedList(new ArrayList<Artist>());
    private static final List<Album> albums = Collections.synchronizedList(new ArrayList<Album>());
    private static final List<Song> songs = Collections.synchronizedList(new ArrayList<Song>());
    private static final List<Playlist> playlists = Collections.synchronizedList(new ArrayList<Playlist>());

    //song handles, that are not part of library but playable (from web sources)
    private static List<Song> handles = Collections.synchronizedList(new ArrayList<Song>());
    private static List<Album> albumHandles = Collections.synchronizedList(new ArrayList<Album>());
    private static List<Artist> artistHandles = Collections.synchronizedList(new ArrayList<Artist>());
    static HashMap<String, ArrayList<Song>> songsByName = new HashMap<>();

    /* list callbacks */
    public interface UserLibraryCallback{void onLibraryChange();}
    public static UserLibraryCallback currentCallback;

    private static File artCacheDir;
    private static File betterSourceFile;

    public static List<Artist> getArtists() {return artists;}
    public static List<Album> getAlbums() {return albums;}
    public static List<Song> getSongs() {return songs;}
    public static List<Playlist> getPlaylists() {return playlists;}

    static Context appContext;

    /* synchronization notification management */
    public static volatile boolean synchronization;
    public static volatile boolean loadingDone;
    public static Thread syncThread;

    public static void registerInit()
    {
        new Thread()
        {
            public void run()
            {
                Looper.prepare();
                registerCachedSongs();
                sortLibrary();
            }
        }.start();
    }

    /*
    * Register local songs and web libraries that were previously cached
     */
    private static void registerCachedSongs()
    {
        if(!configured) return;

        //restore sources cache
        for(Source s : Source.SOURCES) s.registerCachedSongs();

        //restore song better source cache
        if(REGISTER_SONGS_BETTER_SOURCES)
        {
            try
            {
                if(betterSourceFile.exists())
                {
                    //better sources
                    Source bestSource = Source.SOURCE_DEEZER.getPriority() > Source.SOURCE_SPOTIFY.getPriority() ? Source.SOURCE_DEEZER : Source.SOURCE_SPOTIFY;
                    BufferedReader spr = new BufferedReader(new FileReader(betterSourceFile));
                    while(spr.ready())
                    {
                        String[] tp = spr.readLine().split(CACHE_SEPARATOR);
                        Song song = bestSource == Source.SOURCE_DEEZER ?
                                getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                        new SongSources.SongSource(Long.parseLong(tp[6]), bestSource), Integer.parseInt(tp[4])) :
                                getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                        new SongSources.SongSource(tp[6], bestSource), Integer.parseInt(tp[4]));
                        song.setFormat(tp[3]);
                    }
                    spr.close();
                }
            }
            catch(IOException e)
            {
                Log.println(Log.ERROR, "[BLADE-CACHE]", "Cache restore : IOException");
                e.printStackTrace();
            }
        }

        loadingDone = true;
    }

    /*
     * Configure the APIs, the preferences, ...
     */
    public static void configureLibrary(Context appContext)
    {
        if(configured) return;

        LibraryService.appContext = appContext;

        //init cache dirs
        artCacheDir = new File(appContext.getCacheDir().getAbsolutePath() + "/albumArts");
        if(!artCacheDir.exists()) artCacheDir.mkdir();
        betterSourceFile = new File(appContext.getCacheDir().getAbsolutePath() + "/betterSources.cached");

        //get preferences
        SharedPreferences accountsPrefs = appContext.getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
        SharedPreferences generalPrefs = appContext.getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);

        SAVE_PLAYLISTS_TO_LIBRARY = generalPrefs.getBoolean("save_playlist_to_library", false);
        REGISTER_SONGS_BETTER_SOURCES = generalPrefs.getBoolean("register_better_sources", true);

        //setup each source
        for(Source s : Source.SOURCES) s.initConfig(accountsPrefs);

        configured = true;

        //wait for spotify callback
    }
    /*
    * Spotify config init and player init are all async, so i need to wait for them to then call playerConnection init...
     */
    public static void onSpotifyConfigDone()
    {
        //Start playerConnection
        PlayerConnection.start(null, 0);
    }

    /*
     * Registers a song in user library
     */
    static Song registerSong(String artist, long artistId, String album, long albumId,
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
                    if(LOG_REGISTER_SONG) System.out.println("[REGISTER] Found song " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName() + " SOURCE " + source.getSource());
                    s.getSources().addSource(source);
                    s.getAlbum().getSources().addSource(source);
                    s.getArtist().getSources().addSource(source);
                    return s;
                }
            }
        }

        //check if the song is already handled
        synchronized(handles)
        {
            for(Song s : handles)
            {
                if(s.getTitle().equalsIgnoreCase(name) && s.getArtist().getName().equalsIgnoreCase(artist) && s.getAlbum().getName().equalsIgnoreCase(album))
                {
                    s.getSources().addSource(source);
                    if(s.getAlbum().isHandled()) {albums.add(s.getAlbum()); s.getAlbum().setHandled(false); s.getArtist().addAlbum(s.getAlbum()); albumHandles.remove(s.getAlbum());}
                    if(s.getArtist().isHandled()) {artists.add(s.getArtist());s.getArtist().setHandled(false);artistHandles.remove(s.getArtist());}
                    s.getAlbum().addSong(s);
                    s.setHandled(false);
                    handles.remove(s);

                    //register song by name
                    if(snames != null) snames.add(s);
                    else {ArrayList<Song> sn = new ArrayList<>(); sn.add(s); songsByName.put(name.toLowerCase(), sn);}

                    if(LOG_REGISTER_SONG) System.out.println("[REGISTER] Found handled song " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName() + " SOURCE " + source.getSource());
                    return s;
                }
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

        if(LOG_REGISTER_SONG) System.out.println("[REGISTER] Registered : " + name + " - " + songAlbum.getName() + " - " + songArtist.getName() + " - SOURCE " + source.getSource());
        return song;
    }


    /*
    * Synchronize local/cached library with local/web library
    * This method is asynchronous
     */
    public static final int ERROR_LOADING_NOT_DONE = 1;
    public interface SynchronizeCallback
    {
        void synchronizeDone();
        void synchronizeFail(int error);
    }
    public static void synchronizeLibrary(SynchronizeCallback callback)
    {
        if(!loadingDone) {callback.synchronizeFail(ERROR_LOADING_NOT_DONE); return;}

        synchronization = true;

        syncThread = new Thread()
        {
            public void run()
            {
                Looper.prepare();

                //copy all songs in library to HANDLES so that they don't need complete resync (better sources search, img load)
                synchronized (songs)
                {
                    for(Song s : songs) {handles.add(s); s.setHandled(true);}
                }
                synchronized (albums)
                {
                    for(Album a : albums) {albumHandles.add(a); a.setHandled(true); a.getSongs().clear();}
                }
                synchronized (artists)
                {
                    for(Artist a : artists) {artistHandles.add(a); a.setHandled(true); a.getAlbums().clear();}
                }

                for(Source s : Source.SOURCES) s.registerSongs();

                registerSongBetterSources();
                sortLibrary();
                synchronization = false;
                callback.synchronizeDone();
            }
        };
        syncThread.setName("SYNC_THREAD");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    /*
     * If the option is enabled, Blade will try to find a better source for all the songs added by WebService
     * Example : you added a spotify album, but deezer prior > spotify ; Blade will load that album from Deezer
     */
    private static void registerSongBetterSources()
    {
        if(!configured) return;
        if(!REGISTER_SONGS_BETTER_SOURCES) return;

        Source bestSource = Source.SOURCE_DEEZER.getPriority() > Source.SOURCE_SPOTIFY.getPriority() ? Source.SOURCE_DEEZER : Source.SOURCE_SPOTIFY;
        if(!bestSource.isAvailable()) return;

        if(bestSource == Source.SOURCE_SPOTIFY)
        {
            //search all songs on spotify
            ArrayList<Song> spotifySongs = new ArrayList<>();
            for(Song s : songs)
            {
                if(s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_SPOTIFY && s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_LOCAL_LIB)
                {
                    //query spotify for this song
                    try
                    {
                        if(Source.SOURCE_SPOTIFY.searchForSong(s))
                        {
                            spotifySongs.add(s);
                        }
                    }
                    catch (RetrofitError error)
                    {
                        //TODO : handle error
                        continue;
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
                        if(s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_SPOTIFY && s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_LOCAL_LIB)
                        {
                            if(Source.SOURCE_SPOTIFY.searchForSong(s))
                            {
                                spotifySongs.add(s);
                            }
                        }
                    }
                }
            }

            //cache theses
            try
            {
                betterSourceFile.createNewFile();
                BufferedWriter spw = new BufferedWriter(new FileWriter(betterSourceFile));
                for(Song song : spotifySongs)
                {
                    spw.append(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                            + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getSpotify().getId()
                            + CACHE_SEPARATOR + "\n");
                }
                spw.close();
            }
            catch(IOException e) {e.printStackTrace();}

        }
        else if(bestSource == Source.SOURCE_DEEZER)
        {
            ArrayList<Song> deezerSongs = new ArrayList<>();

            //search all songs on deezer
            synchronized (songs)
            {
                for (Song s : songs)
                {
                    if (s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_DEEZER && s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_LOCAL_LIB)
                    {
                        if(Source.SOURCE_DEEZER.searchForSong(s))
                        {
                            deezerSongs.add(s);
                        }
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
                                if(s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_DEEZER && s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_LOCAL_LIB)
                                {
                                    if(Source.SOURCE_DEEZER.searchForSong(s))
                                    {
                                       deezerSongs.add(s);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            //cache theses
            try
            {
                betterSourceFile.createNewFile();
                BufferedWriter spw = new BufferedWriter(new FileWriter(betterSourceFile));
                for(Song song : deezerSongs)
                {
                    spw.write(song.getTitle() + CACHE_SEPARATOR + song.getAlbum().getName() + CACHE_SEPARATOR + song.getArtist().getName() + CACHE_SEPARATOR
                            + song.getFormat() + CACHE_SEPARATOR + song.getTrackNumber() + CACHE_SEPARATOR + song.getDuration() + CACHE_SEPARATOR + song.getSources().getSourceByPriority(0).getId()
                            + CACHE_SEPARATOR);
                    spw.newLine();
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
            public int compare(Song a, Song b){ return a.getTitle().toLowerCase().compareTo(b.getTitle().toLowerCase()); }
        });}
        synchronized (albums)
        {Collections.sort(albums, new Comparator<Album>(){
            public int compare(Album a, Album b){ return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
            }
        });}
        synchronized (artists)
        {Collections.sort(artists, new Comparator<Artist>(){
            public int compare(Artist a, Artist b){ return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
            }
        });}
        synchronized (playlists)
        {Collections.sort(playlists, new Comparator<Playlist>(){
            public int compare(Playlist a, Playlist b){ return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
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

        synchronized(songs)
        {
            for(Song song : songs)
                if(song.getTitle().toLowerCase().contains(q))
                    tr.add(song);
        }
        synchronized(albums)
        {
            for(Album alb : albums)
                if(alb.getName().toLowerCase().contains(q))
                    tr.add(alb);
        }
        synchronized(artists)
        {
            for(Artist artist : artists)
                if(artist.getName().toLowerCase().contains(q))
                    tr.add(artist);
        }
        synchronized(playlists)
        {
            for(Playlist playlist : playlists)
                if(playlist.getName().toLowerCase().contains(q))
                    tr.add(playlist);
        }

        return tr;
    }

    /*
    * Query all web sources + local library for objects
     */
    public static ArrayList<LibraryObject> queryWeb(String s)
    {
        ArrayList<LibraryObject> tr = new ArrayList<>();
        String q = s.toLowerCase();

        for(Source src : Source.SOURCES) tr.addAll(src.query(s));

        // add results from local query
        tr.addAll(query(s));

        //remove doublons (oh my god n*m)
        ArrayList<LibraryObject> trfinal = new ArrayList<>();
        for(LibraryObject libraryObject : tr)
        {
            if(!trfinal.contains(libraryObject)) trfinal.add(libraryObject);
        }

        //sort (songs first, then albums, then artists)
        Collections.sort(trfinal, new Comparator<LibraryObject>()
        {
            @Override
            public int compare(LibraryObject o1, LibraryObject o2)
            {
                if(o1 instanceof Song && o2 instanceof Song)
                    return o2.getSources().getSourceByPriority(0).getSource().getPriority() - o1.getSources().getSourceByPriority(0).getSource().getPriority();
                else if(o1 instanceof Song && o2 instanceof Album) return -2;
                else if(o1 instanceof Song && o2 instanceof Artist) return -3;
                else if(o1 instanceof Album && o2 instanceof Song) return 2;
                else if(o1 instanceof Artist && o2 instanceof Song) return 3;
                else if(o1 instanceof Album && o2 instanceof Artist) return -2;
                else if(o1 instanceof Artist && o2 instanceof Album) return 2;
                return 0;
            }
        });

        return trfinal;
    }

    static Song getSongHandle(String name, String album, String artist, long duration, SongSources.SongSource source, int track)
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
                    if(LOG_REGISTER_SONG) System.out.println("[HANDLE] Found registered song : " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName() + " - SOURCE " + source.getSource());
                    return s;
                }
            }
        }
        for(Song s : handles)
            if(s.getTitle().equalsIgnoreCase(name) && s.getArtist().getName().equalsIgnoreCase(artist) && s.getAlbum().getName().equalsIgnoreCase(album))
            {
                s.getSources().addSource(source);
                s.getAlbum().getSources().addSource(source);
                s.getArtist().getSources().addSource(source);
                if(LOG_REGISTER_SONG) System.out.println("[HANDLE] Found handled song : " + s.getTitle() + " - " + s.getAlbum().getName() + " - " + s.getArtist().getName() + " - SOURCE " + source.getSource());
                return s;
            }

        //else create song object
        Artist songArtist = null;
        synchronized (artists)
        {for(Artist art : artists) if(art.getName().equalsIgnoreCase(artist)) songArtist = art;}
        if(songArtist == null) for(Artist art : artistHandles) if(art.getName().equalsIgnoreCase(artist)) songArtist = art;
        if(songArtist == null) {songArtist = new Artist(artist); songArtist.setHandled(true); artistHandles.add(songArtist);}
        songArtist.getSources().addSource(source);

        Album songAlbum = null;
        synchronized (albums)
        {for(Album alb : albums) if(alb.getName().equalsIgnoreCase(album)) songAlbum = alb;}
        if(songAlbum == null) for(Album alb : albumHandles) if(alb.getName().equalsIgnoreCase(album)) songAlbum = alb;
        if(songAlbum == null) {songAlbum = new Album(album, songArtist); songAlbum.setHandled(true); albumHandles.add(songAlbum);}
        songAlbum.getSources().addSource(source);

        Song s = new Song(name, songArtist, songAlbum, track, duration);
        s.getSources().addSource(source);
        s.setHandled(true);
        handles.add(s);
        if(LOG_REGISTER_SONG) System.out.println("[HANDLE] Handled : " + name + " - " + songAlbum.getName() + " - " + songArtist.getName() + " SOURCE " + source.getSource());
        return s;
    }

    static void loadAlbumArt(Album alb, String path, boolean local)
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
}
