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
package v.blade.library;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.LongSparseArray;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

/*
* This class parses and contains the current user library
*/
public class UserLibrary
{
    /* user preferences */
    public static boolean SAVE_PLAYLISTS_TO_LIBRARY;

    /* library */
    public static final int SOURCE_LOCAL_LIB = 1;
    public static final int SOURCE_SPOTIFY = 2;

    private static ArrayList<Artist> artists;
    private static ArrayList<Album> albums;
    private static ArrayList<Song> songs;
    private static ArrayList<Playlist> playlists;

    private static HashMap<String, ArrayList<Song>> songsByName = new HashMap<>();

    /* spotify specific */
    public static final String SPOTIFY_CLIENT_ID = "2f95bc7168584e7aa67697418a684bae";
    public static final String SPOTIFY_REDIRECT_URI = "http://valou3433.fr/";
    public static String SPOTIFY_USER_TOKEN;
    public static String SPOTIFY_REFRESH_TOKEN;
    public static final SpotifyApi spotifyApi = new SpotifyApi();

    /* list callbacks */
    public interface UserLibraryCallback{void onLibraryChange();}
    public static UserLibraryCallback currentCallback;

    private static Context appContext;
    private static File artCacheDir;

    public static ArrayList<Artist> getArtists() {return artists;}
    public static ArrayList<Album> getAlbums() {return albums;}
    public static ArrayList<Song> getSongs() {return songs;}
    public static ArrayList<Playlist> getPlaylists() {return playlists;}

    /*
    * Start the library config
     */
    public static void configureLibrary(final Context appContext)
    {
        /* init the lists (to make sure they are empty) */
        if(songs != null) return;

        artists = new ArrayList<>();
        albums = new ArrayList<>();
        songs = new ArrayList<>();
        playlists = new ArrayList<>();

        //init cache dir
        UserLibrary.appContext = appContext;
        artCacheDir = appContext.getCacheDir();

        //get preferences
        SharedPreferences accountsPrefs = appContext.getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
        SharedPreferences generalPrefs = appContext.getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);

        SAVE_PLAYLISTS_TO_LIBRARY = generalPrefs.getBoolean("save_playlist_to_library", false);

        //setup spotify api
        if(SPOTIFY_USER_TOKEN == null)
        {
            SPOTIFY_USER_TOKEN = accountsPrefs.getString("spotify_token", null);
            SPOTIFY_REFRESH_TOKEN = accountsPrefs.getString("spotify_refresh_token", null);
        }
        if(SPOTIFY_USER_TOKEN != null) spotifyApi.setAccessToken(SPOTIFY_USER_TOKEN);

        //load songs from all sources (async)
        Thread loaderThread = new Thread()
        {
            @Override
            public void run()
            {
                Looper.prepare();

                //load local library
                registerLocalSongs(appContext);
                System.out.println("[BLADE-DEBUG] Local song registered.");

                //load spotify library
                registerSpotifySongs();
                System.out.println("[BLADE-DEBUG] Spotify songs registered.");

                sortLibrary();
            }
        };
        loaderThread.setName("loaderThread");
        loaderThread.setDaemon(true);
        loaderThread.start();
    }

    /*
    * Registers a song in user library
    */
    private static Song registerSong(Object id, String artist, long artistId, String album, long albumId,
                                     int albumTrack, long duration, String name, int source)
    {
        ArrayList<Song> snames = songsByName.get(name.toLowerCase());
        if(snames != null)
        {
            //check if the song is already registered
            for(Song s : snames)
            {
                if(s.getArtist().getName().equalsIgnoreCase(artist) && s.getAlbum().getName().equalsIgnoreCase(album))
                    return s;
            }
        }

        Artist songArtist = null;
        for (Artist art : artists) if (art.getName().equals(artist)) songArtist = art;
        if(songArtist == null)
        {
            songArtist = new Artist(artist, artistId);
            artists.add(songArtist);
        }

        Album songAlbum = null;
        for(int i = 0;i<songArtist.getAlbums().size();i++)
            if(songArtist.getAlbums().get(i).getName().equals(album)) songAlbum = songArtist.getAlbums().get(i);

        if(songAlbum == null)
        {
            songAlbum = new Album(album, songArtist, albumId);
            albums.add(songAlbum);
            songArtist.addAlbum(songAlbum);
        }

        Song song = new Song(id, name, songArtist, songAlbum, albumTrack, duration, source);
        songAlbum.addSong(song);
        songs.add(song);

        if(currentCallback != null) currentCallback.onLibraryChange();

        //register song by name
        if(snames != null) snames.add(song);
        else {ArrayList<Song> sn = new ArrayList<>(); sn.add(song); songsByName.put(name.toLowerCase(), sn);}

        return song;
    }

    private static void registerLocalSongs(Context appContext)
    {
        /* get content resolver and init temp sorted arrays */
        final ContentResolver musicResolver = appContext.getContentResolver();
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

                Song s = registerSong(thisId, thisArtist, artistId, thisAlbum, albumId, albumTrack, thisDuration, thisTitle, SOURCE_LOCAL_LIB);
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

                Playlist list = new Playlist(thisId, thisName, thisList, SOURCE_LOCAL_LIB);
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
    public static void registerSpotifySongs()
    {
        if(SPOTIFY_USER_TOKEN != null)
        {
            SpotifyService service = spotifyApi.getService();
            try
            {
                Pager<SavedTrack> userTracks = service.getMySavedTracks();
                Pager<SavedAlbum> userAlbums = service.getMySavedAlbums();
                Pager<PlaylistSimple> userPlaylists = service.getMyPlaylists();
                for (SavedTrack track : userTracks.items)
                {
                    Track t = track.track;
                    Song s = registerSong(t.id, t.artists.get(0).name, 0, t.album.name, 0,
                            t.track_number, t.duration_ms, t.name, SOURCE_SPOTIFY);
                    if(!s.getAlbum().hasAlbumArt())
                    {
                        Image albumImage = t.album.images.get(0);
                        loadAlbumArt(s.getAlbum(), albumImage.url, false);
                    }
                }
                for(SavedAlbum album : userAlbums.items)
                {
                    Album savedAlbum = null;
                    kaaes.spotify.webapi.android.models.Album alb = album.album;
                    Pager<Track> tracks = service.getAlbumTracks(alb.id);
                    for(Track t : tracks.items)
                    {
                        Song s = registerSong(t.id, t.artists.get(0).name, 0, alb.name, 0,
                                t.track_number, t.duration_ms, t.name, SOURCE_SPOTIFY);
                        if(savedAlbum == null) savedAlbum = s.getAlbum();
                    }

                    if(!savedAlbum.hasAlbumArt())
                    {
                        Image albumImage = alb.images.get(0);
                        loadAlbumArt(savedAlbum, albumImage.url, false);
                    }
                }
                for(PlaylistSimple playlistBase : userPlaylists.items)
                {
                    ArrayList<Song> thisList = new ArrayList<>();
                    Pager<PlaylistTrack> tracks = service.getPlaylistTracks(playlistBase.owner.id, playlistBase.id);
                    for(PlaylistTrack pt : tracks.items)
                    {
                        Track t = pt.track;
                        Song s;
                        if(SAVE_PLAYLISTS_TO_LIBRARY)
                            s = registerSong(t.id, t.artists.get(0).name, 0, t.album.name, 0,
                                    t.track_number, t.duration_ms, t.name, SOURCE_SPOTIFY);
                        else
                            s = getSongHandle(t.id, t.name, t.album.name, t.artists.get(0).name, t.duration_ms, SOURCE_SPOTIFY,
                                    t.track_number);

                        //get albumart for this song
                        if(!s.getAlbum().hasAlbumArt())
                        {
                            Image albumImage = t.album.images.get(0);
                            loadAlbumArt(s.getAlbum(), albumImage.url, false);
                        }

                        thisList.add(s);
                    }

                    Playlist list = new Playlist(playlistBase.id, playlistBase.name, thisList, SOURCE_SPOTIFY);
                    playlists.add(list);
                    if(currentCallback != null) currentCallback.onLibraryChange();
                }
            }
            catch (RetrofitError error)
            {
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
    }
    public static void refreshSpotifyToken()
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
            writer.write("client_id=" + UserLibrary.SPOTIFY_CLIENT_ID + "&");
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
                    UserLibrary.SPOTIFY_USER_TOKEN = param;
                    spotifyApi.setAccessToken(SPOTIFY_USER_TOKEN);
                    SharedPreferences pref = appContext.getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = pref.edit();
                    editor.putString("spotify_token", UserLibrary.SPOTIFY_USER_TOKEN);
                    editor.commit();
                }
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void sortLibrary()
    {
        /* sort collection by alphabetical order */
        Collections.sort(songs, new Comparator<Song>(){
            public int compare(Song a, Song b){ return a.getTitle().compareTo(b.getTitle()); }
        });
        Collections.sort(albums, new Comparator<Album>(){
            public int compare(Album a, Album b){ return a.getName().compareTo(b.getName());
            }
        });
        Collections.sort(artists, new Comparator<Artist>(){
            public int compare(Artist a, Artist b){ return a.getName().compareTo(b.getName());
            }
        });
        Collections.sort(playlists, new Comparator<Playlist>(){
            public int compare(Playlist a, Playlist b){ return a.getName().compareTo(b.getName());
            }
        });
        if(currentCallback != null) currentCallback.onLibraryChange();

        /* sort each album per tracks */
        for(Album alb : albums)
        {
            Collections.sort(alb.getSongs(), new Comparator<Song>() {
                @Override
                public int compare(Song o1, Song o2) {return o1.getTrackNumber() - o2.getTrackNumber();}
            });
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

    private static Song getSongHandle(Object id, String name, String album, String artist, long duration, int source, int track)
    {
        //if song is already registered, return song from library
        ArrayList<Song> snames = songsByName.get(name.toLowerCase());
        if(snames != null)
        {
            //check if the song is already registered
            for(Song s : snames)
            {
                if(s.getArtist().getName().equalsIgnoreCase(artist) && s.getAlbum().getName().equalsIgnoreCase(album))
                    return s;
            }
        }

        //else create song object
        Artist songArtist = null;
        for(Artist art : artists) if(art.getName().equalsIgnoreCase(artist)) songArtist = art;
        if(songArtist == null) songArtist = new Artist(name, 0);

        Album songAlbum = null;
        for(Album alb : albums) if(alb.getName().equalsIgnoreCase(album)) songAlbum = alb;
        if(songAlbum == null) songAlbum = new Album(album, songArtist, 0);

        return new Song(id, name, songArtist, songAlbum, track, duration, source);
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
}
