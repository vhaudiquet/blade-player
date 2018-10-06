package v.blade.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import v.blade.ui.settings.SettingsActivity;

/*
 * LibraryService is the class that handles library caching/restore and synchronization
 * It was a background service, but it works actually better as a static class that just calls a Thread to do async work
 */
public class LibraryService {
    private static final boolean LOG_REGISTER_SONG = false;
    private static final int BETTER_SOURCES_MAX = 20; //stop better sources after 20 registered songs (avoid query limit)
    static final String CACHE_SEPARATOR = "##";

    /* user preferences */
    public static boolean configured = false;
    public static boolean SAVE_PLAYLISTS_TO_LIBRARY;
    public static boolean REGISTER_SONGS_BETTER_SOURCES;
    public static Uri TREE_URI;
    public static boolean ENABLE_SONG_CHANGE_ANIM;

    /*
     * Synchronize local/cached library with local/web library
     * This method is asynchronous
     */
    public static final int ERROR_LOADING_NOT_DONE = 1;
    /* library */
    private static final List<Artist> artists = Collections.synchronizedList(new ArrayList<>());
    private static final List<Album> albums = Collections.synchronizedList(new ArrayList<>());
    private static final List<Song> songs = Collections.synchronizedList(new ArrayList<>());
    private static final List<Playlist> playlists = Collections.synchronizedList(new ArrayList<>());
    //song handles, that are not part of library but playable (from web sources)
    private static final List<Song> handles = Collections.synchronizedList(new ArrayList<>());
    private static final List<Album> albumHandles = Collections.synchronizedList(new ArrayList<>());
    static HashMap<String, ArrayList<Song>> songsByName = new HashMap<>();

    //song linkss
    public static final HashMap<Song, List<Song>> songLinks = new HashMap<>();
    private static final List<Artist> artistHandles = Collections.synchronizedList(new ArrayList<>());

    public static UserLibraryCallback currentCallback;

    private static File artCacheDir;
    private static File betterSourceFile;
    private static File songLinksFile;

    public static List<Artist> getArtists() {
        return artists;
    }

    public static List<Album> getAlbums() {
        return albums;
    }

    public static List<Song> getSongs() {
        return songs;
    }

    public static List<Playlist> getPlaylists() {
        return playlists;
    }

    static Context appContext;

    /* synchronization notification management */
    public static volatile boolean synchronization;
    public static volatile boolean loadingDone;
    public static Thread syncThread;

    public static void registerInit() {
        new Thread() {
            public void run() {
                Looper.prepare();
                registerCachedSongs();
                sortLibrary();
            }
        }.start();
    }

    /*
     * Register local songs and web libraries that were previously cached
     */
    private static void registerCachedSongs() {
        if (!configured) return;

        //restore sources cache
        for (Source s : Source.SOURCES) s.registerCachedSongs();

        //restore song better source cache
        if (REGISTER_SONGS_BETTER_SOURCES) {
            try {
                if (betterSourceFile.exists()) {
                    RandomAccessFile raf = new RandomAccessFile(betterSourceFile.getAbsolutePath(), "r");
                    //better sources
                    Source bestSource = Source.SOURCE_DEEZER.getPriority() >
                            Source.SOURCE_SPOTIFY.getPriority() ? Source.SOURCE_DEEZER : Source.SOURCE_SPOTIFY;

                    while (raf.getFilePointer() < raf.length()) {
                        String[] tp = raf.readUTF().split(CACHE_SEPARATOR);
                        Song song = bestSource == Source.SOURCE_DEEZER ?
                                getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                        new SongSources.SongSource(Long.parseLong(tp[6]), bestSource), Integer.parseInt(tp[4])) :
                                getSongHandle(tp[0], tp[1], tp[2], Long.parseLong(tp[5]),
                                        new SongSources.SongSource(tp[6], bestSource), Integer.parseInt(tp[4]));
                        song.setFormat(tp[3]);
                    }
                    raf.close();
                }
            } catch (IOException e) {
                Log.println(Log.ERROR, "[BLADE-CACHE]", "Cache restore : IOException");
                e.printStackTrace();
            }
        }

        registerSongLinks();

        loadingDone = true;

        new Thread() {
            public void run() {
                for (Source s : Source.SOURCES) {
                    s.loadCachedArts();
                }
            }
        }.start();
    }

    /*
     * Configure the APIs, the preferences, ...
     */
    public static void configureLibrary(Context appContext) {
        if (configured) return;

        LibraryService.appContext = appContext;

        //init cache dirs
        artCacheDir = new File(appContext.getCacheDir().getAbsolutePath() + "/arts");
        if (!artCacheDir.exists()) artCacheDir.mkdir();
        betterSourceFile = new File(appContext.getCacheDir().getAbsolutePath() + "/betterSources.cached");
        songLinksFile = new File(appContext.getCacheDir().getAbsolutePath() + "/songLinks.cached");

        //get preferences
        SharedPreferences accountsPrefs = appContext.getSharedPreferences(
                SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
        SharedPreferences generalPrefs = appContext.getSharedPreferences(
                SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);

        String treeUri = generalPrefs.getString("sdcard_uri", null);
        if (treeUri != null) TREE_URI = Uri.parse(treeUri);

        ENABLE_SONG_CHANGE_ANIM = generalPrefs.getBoolean("anim_0", true);

        SAVE_PLAYLISTS_TO_LIBRARY = generalPrefs.getBoolean("save_playlist_to_library", false);
        REGISTER_SONGS_BETTER_SOURCES = generalPrefs.getBoolean("register_better_sources", true);

        //setup each source
        for (Source s : Source.SOURCES) s.initConfig(accountsPrefs);

        configured = true;
    }

    /*
     * Registers a song in user library
     */
    public static Song registerSong(String artist, String album, int albumTrack, int year,
                                    long duration, String name, SongSources.SongSource source) {
        //REGISTER : this song is in the library of this source
        source.setLibrary(true);

        //check if the song is already registered
        ArrayList<Song> snames = songsByName.get(name.toLowerCase());
        if (snames != null) {
            for (Song s : snames) {
                if (s.getArtist().getName().equalsIgnoreCase(artist) &&
                        s.getAlbum().getName().equalsIgnoreCase(album)) {
                    if (LOG_REGISTER_SONG)
                        System.out.println("[REGISTER] Found song " +
                                s.getTitle() + " - " +
                                s.getAlbum().getName() + " - " +
                                s.getArtist().getName() + " SOURCE " + source.getSource());
                    s.getSources().addSource(source);
                    s.getAlbum().getSources().addSource(source);
                    s.getArtist().getSources().addSource(source);
                    return s;
                }
            }
        }

        //check if the song is already handled
        synchronized (handles) {
            for (Song s : handles) {
                if (s.getTitle().equalsIgnoreCase(name) &&
                        s.getArtist().getName().equalsIgnoreCase(artist) &&
                        s.getAlbum().getName().equalsIgnoreCase(album)) {
                    s.getSources().addSource(source);
                    if (s.getAlbum().isHandled()) {
                        albums.add(s.getAlbum());
                        s.getAlbum().setHandled(false);
                        s.getArtist().addAlbum(s.getAlbum());
                        albumHandles.remove(s.getAlbum());
                    }
                    if (s.getArtist().isHandled()) {
                        artists.add(s.getArtist());
                        s.getArtist().setHandled(false);
                        artistHandles.remove(s.getArtist());
                    }
                    s.getAlbum().addSong(s);
                    s.setHandled(false);
                    handles.remove(s);
                    songs.add(s);

                    //register song by name
                    if (snames != null) snames.add(s);
                    else {
                        ArrayList<Song> sn = new ArrayList<>();
                        sn.add(s);
                        songsByName.put(name.toLowerCase(), sn);
                    }

                    if (LOG_REGISTER_SONG)
                        System.out.println("[REGISTER] Found handled song " +
                                s.getTitle() + " - " +
                                s.getAlbum().getName() + " - " +
                                s.getArtist().getName() + " SOURCE " +
                                source.getSource());
                    return s;
                }
            }
        }

        Artist songArtist = null;
        synchronized (artists) {
            for (Artist art : artists) if (art.getName().equalsIgnoreCase(artist)) songArtist = art;
        }
        if (songArtist == null) {
            songArtist = new Artist(artist);
            artists.add(songArtist);
        }
        songArtist.getSources().addSource(source);

        Album songAlbum = null;
        synchronized (songArtist.getAlbums()) {
            for (int i = 0; i < songArtist.getAlbums().size(); i++)
                if (songArtist.getAlbums().get(i).getName().equalsIgnoreCase(album))
                    songAlbum = songArtist.getAlbums().get(i);
        }

        if (songAlbum == null) {
            songAlbum = new Album(album, songArtist);
            albums.add(songAlbum);
            songArtist.addAlbum(songAlbum);
        }
        songAlbum.getSources().addSource(source);

        Song song = new Song(name, songArtist, songAlbum, albumTrack, duration, year);
        song.getSources().addSource(source);
        songAlbum.addSong(song);
        songs.add(song);

        if (currentCallback != null) currentCallback.onLibraryChange();

        //register song by name
        if (snames != null) snames.add(song);
        else {
            ArrayList<Song> sn = new ArrayList<>();
            sn.add(song);
            songsByName.put(name.toLowerCase(), sn);
        }

        if (LOG_REGISTER_SONG)
            System.out.println("[REGISTER] Registered : " + name + " - " +
                    songAlbum.getName() + " - " +
                    songArtist.getName() + " - SOURCE " +
                    source.getSource());
        return song;
    }

    public static void unregisterSong(Song song, SongSources.SongSource toUnregister) {
        //remove songsource
        SongSources sources = song.getSources();
        sources.removeSource(toUnregister);

        boolean isInLibrary = false;
        for (SongSources.SongSource s : sources.sources)
            if (s != null) if (s.getLibrary()) {
                isInLibrary = true;
                break;
            }
        if (!isInLibrary) {
            //remove from library
            LibraryService.getSongs().remove(song);
            if (LibraryService.currentCallback != null)
                LibraryService.currentCallback.onLibraryChange();

            //remove from album and set handled + remove album if last one song
            song.getAlbum().getSongs().remove(song);
            song.setHandled(true);
            LibraryService.handles.add(song);

            boolean albumHandled = true;
            for (Song s : song.getAlbum().getSongs()) if (!s.isHandled()) albumHandled = false;
            if (albumHandled) {
                //remove from library
                LibraryService.getSongs().remove(song);
                if (LibraryService.currentCallback != null)
                    LibraryService.currentCallback.onLibraryChange();

                //remove from artist and set handled + remove artist if last one album
                song.getArtist().getAlbums().remove(song.getAlbum());
                song.getAlbum().setHandled(true);
                LibraryService.albumHandles.add(song.getAlbum());

                boolean artistHandled = true;
                for (Album a : song.getArtist().getAlbums())
                    if (!a.isHandled()) artistHandled = false;

                if (artistHandled) {
                    //remove from library
                    LibraryService.getArtists().remove(song.getArtist());
                    if (LibraryService.currentCallback != null)
                        LibraryService.currentCallback.onLibraryChange();

                    //set handled
                    song.getArtist().setHandled(true);
                    artistHandles.add(song.getArtist());
                }
            }
        }
    }

    /*
     * Link a song to another (to say they are the same)
     * source gets removed and songsources are merged
     */
    public static void linkSong(Song source, Song destination, boolean save) {
        if (source == destination) return;

        for (SongSources.SongSource src : source.getSources().sources) {
            if (src != null) {
                destination.getSources().addSource(src); //addSource is checking for double-add
                unregisterSong(source, src);
            }
        }

        if (source.isHandled()) handles.remove(source);

        //replace song in playlists
        synchronized (playlists) {
            for (Playlist p : playlists) {
                int index = p.getContent().indexOf(source);
                if (index != -1) {
                    p.getContent().remove(index);
                    p.getContent().add(index, destination);
                }
            }
        }

        if (currentCallback != null) currentCallback.onLibraryChange();

        List<Song> list = songLinks.get(destination);
        if (list == null) {
            list = new ArrayList<>();
            songLinks.put(destination, list);
        }
        list.add(source);

        //save songlinks to cache (by rewriting hashmap)
        if (save) writeLinks();
    }

    /*
     * Rewrite song links on disk
     */
    public static void writeLinks() {
        try {
            songLinksFile.createNewFile();
            BufferedWriter writer = new BufferedWriter(new FileWriter(songLinksFile));
            for (Song s : songLinks.keySet()) {
                List<Song> links = songLinks.get(s);
                writer.write(s.getArtist() + CACHE_SEPARATOR +
                        s.getAlbum() + CACHE_SEPARATOR +
                        s.getTitle() + CACHE_SEPARATOR +
                        links.size() + CACHE_SEPARATOR);
                for (Song linked : links)
                    writer.write(linked.getArtist() + CACHE_SEPARATOR +
                            linked.getAlbum() + CACHE_SEPARATOR +
                            linked.getTitle() + CACHE_SEPARATOR);
                writer.newLine();
            }
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void synchronizeLibrary(SynchronizeCallback callback) {
        if (!loadingDone) {
            callback.synchronizeFail(ERROR_LOADING_NOT_DONE);
            return;
        }

        synchronization = true;

        syncThread = new Thread() {
            public void run() {
                Looper.prepare();

                //copy all songs in library to HANDLES so that they don't need complete resync (better sources search, img load)
                synchronized (songs) {
                    for (Song s : songs) {
                        handles.add(s);
                        s.setHandled(true);
                    }
                }
                synchronized (albums) {
                    for (Album a : albums) {
                        albumHandles.add(a);
                        a.setHandled(true);
                        a.getSongs().clear();
                    }
                }
                synchronized (artists) {
                    for (Artist a : artists) {
                        artistHandles.add(a);
                        a.setHandled(true);
                        a.getAlbums().clear();
                    }
                }

                for (Source s : Source.SOURCES) s.registerSongs();

                registerSongLinks();
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
    private static void registerSongBetterSources() {
        if (!configured) return;
        if (!REGISTER_SONGS_BETTER_SOURCES) return;

        Source bestSource = Source.SOURCE_DEEZER.getPriority() >
                Source.SOURCE_SPOTIFY.getPriority() ? Source.SOURCE_DEEZER : Source.SOURCE_SPOTIFY;
        if (!bestSource.isAvailable()) return;

        int addedSongs = 0;

        //search all songs on best source
        ArrayList<Song> bestSourceSongs = new ArrayList<>();
        synchronized (songs) {
            for (Song s : songs) {
                if (s.getSources().getSourceByPriority(0).getSource() != bestSource &&
                        s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_LOCAL_LIB) {
                    //query bestSource for this song
                    try {
                        if (bestSource.searchForSong(s)) {
                            bestSourceSongs.add(s);
                        }
                        addedSongs++;
                        if (addedSongs >= BETTER_SOURCES_MAX) break;
                    } catch (Exception error) {
                        //TODO : handle error
                    }
                }
            }
        }

        //also search for songs in playlist
        if (!SAVE_PLAYLISTS_TO_LIBRARY) {
            for (Playlist p : playlists) {
                for (Song s : p.getContent()) {
                    if (s.getSources().getSourceByPriority(0).getSource() != bestSource &&
                            s.getSources().getSourceByPriority(0).getSource() != Source.SOURCE_LOCAL_LIB) {
                        if (bestSource.searchForSong(s)) {
                            bestSourceSongs.add(s);
                        }
                        addedSongs++;
                        if (addedSongs >= BETTER_SOURCES_MAX) break;
                    }
                }
            }
        }

        //cache theses
        try {
            //TODO : for now we keep betterSources forever, find a way to get rid of unused ones
            if (betterSourceFile.exists()) betterSourceFile.createNewFile();
            RandomAccessFile randomAccessFile = new RandomAccessFile(betterSourceFile.getAbsolutePath(), "rw");
            randomAccessFile.seek(randomAccessFile.length());
            for (Song song : bestSourceSongs) {
                randomAccessFile.writeUTF(song.getTitle() + CACHE_SEPARATOR +
                        song.getAlbum().getName() + CACHE_SEPARATOR +
                        song.getArtist().getName() + CACHE_SEPARATOR +
                        song.getFormat() + CACHE_SEPARATOR +
                        song.getTrackNumber() + CACHE_SEPARATOR +
                        song.getDuration() + CACHE_SEPARATOR +
                        song.getSources().getSourceByPriority(0).getId() + CACHE_SEPARATOR + "\n");
            }
            randomAccessFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (currentCallback != null) currentCallback.onLibraryChange();
    }

    /*
     * Register cached user-defined song links
     */
    private static void registerSongLinks() {
        if (!configured) return;
        if (!songLinksFile.exists()) return;

        try {
            BufferedReader reader = new BufferedReader(new FileReader(songLinksFile));
            while (reader.ready()) {
                String[] line = reader.readLine().split(CACHE_SEPARATOR);
                //System.out.println(Arrays.toString(line));
                Song song = getSongHandle(line[2], line[1], line[0], 0, null, 0);
                if (song == null) continue;
                int size = Integer.parseInt(line[3]);
                for (int i = 0; i < size; i++) {
                    Song toLink = getSongHandle(line[6 + i], line[5 + i], line[4 + i], 0, null, 0);
                    if (toLink == null) continue;
                    linkSong(toLink, song, false);
                }
            }
            reader.close();
        } catch (IOException e) {
            System.err.println("registerSongLinks() encountered IOException");
            e.printStackTrace();
        }
    }

    public static void sortLibrary() {
        /* sort collection by alphabetical order */

        synchronized (songs) {
            Collections.sort(songs, (a, b) -> {
                if (a.getTitle() != null && b.getTitle() != null)
                    return a.getTitle().toLowerCase().compareTo(b.getTitle().toLowerCase());
                else return 0;
            });
        }
        synchronized (albums) {
            Collections.sort(albums, (a, b) -> {
                if (a.getName() != null && b.getName() != null)
                    return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
                else return 0;
            });
        }
        synchronized (artists) {
            Collections.sort(artists, (a, b) -> {
                if (a.getName() != null && b.getName() != null)
                    return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
                else return 0;
            });
        }
        synchronized (playlists) {
            Collections.sort(playlists, (a, b) -> {
                if (a.getName() != null && b.getName() != null)
                    return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
                else return 0;
            });
        }
        if (currentCallback != null) currentCallback.onLibraryChange();

        /* sort each album per tracks */
        synchronized (albums) {
            for (Album alb : albums) {
                synchronized (alb.getSongs()) {
                    Collections.sort(alb.getSongs(), (o1, o2) -> o1.getTrackNumber() - o2.getTrackNumber());
                }
            }
        }

        if (currentCallback != null) currentCallback.onLibraryChange();
    }

    /*
     * Query the library for songs
     */
    public static ArrayList<Song> querySongs(String s) {
        ArrayList<Song> tr = new ArrayList<>();
        String q = s.toLowerCase();

        synchronized (songs) {
            for (Song song : songs)
                if (song.getTitle().toLowerCase().contains(q))
                    tr.add(song);
        }

        return tr;
    }

    /*
     * Query the library for objects
     */
    public static ArrayList<LibraryObject> query(String s) {
        ArrayList<LibraryObject> tr = new ArrayList<>();
        String q = s.toLowerCase();

        synchronized (songs) {
            for (Song song : songs)
                if (song.getTitle().toLowerCase().contains(q))
                    tr.add(song);
        }
        synchronized (albums) {
            for (Album alb : albums)
                if (alb.getName().toLowerCase().contains(q))
                    tr.add(alb);
        }
        synchronized (artists) {
            for (Artist artist : artists)
                if (artist.getName().toLowerCase().contains(q))
                    tr.add(artist);
        }
        synchronized (playlists) {
            for (Playlist playlist : playlists)
                if (playlist.getName() != null)
                    if (playlist.getName().toLowerCase().contains(q))
                        tr.add(playlist);
        }

        return tr;
    }

    /*
     * Query all web sources + local library for objects
     */
    public static ArrayList<LibraryObject> queryWeb(String s) {
        ArrayList<LibraryObject> tr = new ArrayList<>();
        String q = s.toLowerCase();

        for (Source src : Source.SOURCES) tr.addAll(src.query(s));

        // add results from local query
        tr.addAll(query(s));

        //remove doublons (oh my god n*m)
        ArrayList<LibraryObject> trfinal = new ArrayList<>();
        for (LibraryObject libraryObject : tr) {
            if (!trfinal.contains(libraryObject)) trfinal.add(libraryObject);
        }

        //sort (songs first, then albums, then artists)
        Collections.sort(trfinal, (o1, o2) -> {
            if (o1 instanceof Song && o2 instanceof Song)
                return o2.getSources().getSourceByPriority(0).getSource().getPriority() -
                        o1.getSources().getSourceByPriority(0).getSource().getPriority();
            else if (o1 instanceof Song && o2 instanceof Album) return -2;
            else if (o1 instanceof Song && o2 instanceof Artist) return -3;
            else if (o1 instanceof Album && o2 instanceof Song) return 2;
            else if (o1 instanceof Artist && o2 instanceof Song) return 3;
            else if (o1 instanceof Album && o2 instanceof Artist) return -2;
            else if (o1 instanceof Artist && o2 instanceof Album) return 2;
            return 0;
        });

        return trfinal;
    }

    static Song getSongHandle(String name, String album, String artist,
                              long duration, SongSources.SongSource source, int track) {
        //if song is already registered, return song from library
        ArrayList<Song> snames = songsByName.get(name.toLowerCase()); //TODO : fix sync problems
        if (snames != null) {
            //check if the song is already registered
            for (Song s : snames) {
                if (s.getArtist().getName().equalsIgnoreCase(artist) && s.getAlbum().getName().equalsIgnoreCase(album)) {
                    if (source != null) {
                        s.getSources().addSource(source);
                        s.getAlbum().getSources().addSource(source);
                        s.getArtist().getSources().addSource(source);
                    }
                    if (LOG_REGISTER_SONG)
                        System.out.println("[HANDLE] Found registered song : " +
                                s.getTitle() + " - " +
                                s.getAlbum().getName() + " - " +
                                s.getArtist().getName() + " - SOURCE " + source.getSource());
                    return s;
                }
            }
        }
        for (Song s : handles)
            if (s.getTitle().equalsIgnoreCase(name) &&
                    s.getArtist().getName().equalsIgnoreCase(artist) &&
                    s.getAlbum().getName().equalsIgnoreCase(album)) {
                if (source != null) {
                    s.getSources().addSource(source);
                    s.getAlbum().getSources().addSource(source);
                    s.getArtist().getSources().addSource(source);
                }
                if (LOG_REGISTER_SONG)
                    System.out.println("[HANDLE] Found handled song : " +
                            s.getTitle() + " - " +
                            s.getAlbum().getName() + " - " +
                            s.getArtist().getName() + " - SOURCE " + source.getSource());
                return s;
            }

        if (source == null) return null;

        //else create song object
        Artist songArtist = null;
        synchronized (artists) {
            for (Artist art : artists) if (art.getName().equalsIgnoreCase(artist)) songArtist = art;
        }
        if (songArtist == null) for (Artist art : artistHandles)
            if (art.getName().equalsIgnoreCase(artist)) songArtist = art;
        if (songArtist == null) {
            songArtist = new Artist(artist);
            songArtist.setHandled(true);
            artistHandles.add(songArtist);
        }
        songArtist.getSources().addSource(source);

        Album songAlbum = null;
        synchronized (albums) {
            for (Album alb : albums)
                if (alb.getName().equalsIgnoreCase(album) && alb.getArtist().getName().equalsIgnoreCase(songArtist.getName()))
                    songAlbum = alb;
        }
        if (songAlbum == null)
            synchronized (albumHandles) {
                for (Album alb : albumHandles)
                    if (alb.getName().equalsIgnoreCase(album) && alb.getArtist().getName().equalsIgnoreCase(songArtist.getName()))
                        songAlbum = alb;
            }
        if (songAlbum == null) {
            songAlbum = new Album(album, songArtist);
            songAlbum.setHandled(true);
            albumHandles.add(songAlbum);
        }
        songAlbum.getSources().addSource(source);

        Song s = new Song(name, songArtist, songAlbum, track, duration, 0);
        s.getSources().addSource(source);
        s.setHandled(true);
        handles.add(s);
        if (LOG_REGISTER_SONG)
            System.out.println("[HANDLE] Handled : " + name + " - " + songAlbum.getName() + " - " + songArtist.getName() + " SOURCE " + source.getSource());
        return s;
    }

    static void loadArt(LibraryObject obj, String path, boolean local) {
        if (local) {
            BitmapFactory.Options options = new BitmapFactory.Options();

            //decode file bounds
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            //calculate resize to do
            int inSampleSize = calculateSampleSize(options);

            //load miniature
            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            Bitmap toSet = BitmapFactory.decodeFile(path, options);

            if (toSet != null) obj.setArt(path, toSet);
        } else {
            String fileName = obj.getName();
            if (obj instanceof Album) fileName += "." + ((Album) obj).getArtist().getName();
            if (fileName.contains("/")) fileName = fileName.replaceAll("/", "#");
            fileName += "." + obj.getType();
            File toSave = new File(artCacheDir.getAbsolutePath() + "/" + fileName + ".png");
            if (!toSave.exists()) {
                try {
                    URLConnection connection = new URL(path).openConnection();
                    connection.setUseCaches(true);
                    OutputStream fos = new FileOutputStream(toSave);
                    InputStream nis = connection.getInputStream();

                    //copy stream
                    byte[] buffer = new byte[4096];
                    while (true) {
                        try {
                            int count = nis.read(buffer, 0, 4096);
                            if (count == -1) break;
                            fos.write(buffer, 0, count);
                        } catch (InterruptedIOException ex) {
                            ex.printStackTrace();
                        }
                    }

                    connection.getInputStream().close();
                } catch (Exception e) {
                    Log.println(Log.WARN, "[BLADE]", "Exception on decoding art for object " + obj.getName() + " : " + path);
                    e.printStackTrace();
                    return;
                }
            }
            loadArt(obj, toSave.getPath(), true);
        }
    }

    private static int calculateSampleSize(BitmapFactory.Options options) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > Album.minatureSize || width > Album.minatureSize) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= Album.minatureSize && (halfWidth / inSampleSize) >= Album.minatureSize)
                inSampleSize *= 2;
        }

        return inSampleSize;
    }

    /* list callbacks */
    public interface UserLibraryCallback {
        void onLibraryChange();
    }

    public interface SynchronizeCallback {
        void synchronizeDone();

        void synchronizeFail(int error);
    }
}
