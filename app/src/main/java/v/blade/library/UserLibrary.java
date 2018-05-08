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
import android.graphics.BitmapFactory;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LongSparseArray;
import android.widget.Toast;
import kaaes.spotify.webapi.android.SpotifyApi;
import kaaes.spotify.webapi.android.SpotifyError;
import kaaes.spotify.webapi.android.SpotifyService;
import kaaes.spotify.webapi.android.models.*;
import retrofit.RetrofitError;
import v.blade.ui.settings.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/*
* This class parses and contains the current user library
*/
public class UserLibrary
{
    public static final int SOURCE_LOCAL_LIB = 1;
    public static final int SOURCE_SPOTIFY = 2;

    private static ArrayList<Artist> artists;
    private static ArrayList<Album> albums;
    private static ArrayList<Song> songs;
    private static ArrayList<Playlist> playlists;

    /* sorted arrays used only for short amounts of time (cause of their size) */
    private static LongSparseArray<Album> idsorted_albums;
    private static LongSparseArray<Song> idsorted_songs;

    /* spotify specific */
    public static final String SPOTIFY_CLIENT_ID = "2f95bc7168584e7aa67697418a684bae";
    public static final String SPOTIFY_REDIRECT_URI = "http://valou3433.fr/";
    public static String SPOTIFY_USER_TOKEN;
    public static final SpotifyApi spotifyApi = new SpotifyApi();

    /* list callbacks */
    public static interface UserLibraryCallback{void onLibraryChange();}
    public static UserLibraryCallback currentCallback;

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

        artists = new ArrayList<Artist>();
        albums = new ArrayList<Album>();
        songs = new ArrayList<Song>();
        playlists = new ArrayList<Playlist>();

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

                //read spotify info
                if(SPOTIFY_USER_TOKEN == null)
                {
                    SharedPreferences prefs = appContext.getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
                    SPOTIFY_USER_TOKEN = prefs.getString("spotify_token", null);
                }
                if(SPOTIFY_USER_TOKEN != null)
                {
                    spotifyApi.setAccessToken(SPOTIFY_USER_TOKEN);
                    SpotifyService service = spotifyApi.getService();
                    try
                    {
                        Pager<SavedTrack> userTracks = service.getMySavedTracks();
                        Pager<SavedAlbum> userAlbums = service.getMySavedAlbums();
                        Pager<PlaylistSimple> userPlaylists = service.getMyPlaylists();
                        for (SavedTrack track : userTracks.items)
                        {
                            Track t = track.track;
                            registerSong(t.id, t.artists.get(0).name, 0, t.album.name, 0,
                                    t.track_number, t.duration_ms, t.name, SOURCE_SPOTIFY);
                        }
                        for(SavedAlbum album : userAlbums.items)
                        {
                            kaaes.spotify.webapi.android.models.Album alb = album.album;
                            Pager<Track> tracks = service.getAlbumTracks(alb.id);
                            for(Track t : tracks.items)
                                registerSong(t.id, t.artists.get(0).name, 0, alb.name, 0,
                                    t.track_number, t.duration_ms, t.name, SOURCE_SPOTIFY);
                        }
                    }
                    catch (RetrofitError error)
                    {
                        error.printStackTrace();
                        System.err.println("ERROR BODY : " + error.getBody());
                        SpotifyError spotifyError = SpotifyError.fromRetrofitError(error);
                        spotifyError.printStackTrace();
                        System.err.println("SPOTIFY ERROR DETAILS : " + spotifyError.getErrorDetails());
                    }
                }

                System.out.println("[BLADE-DEBUG] Spotify songs registered.");

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
        };
        loaderThread.setName("loaderThread");
        loaderThread.setDaemon(true);
        loaderThread.start();
    }

    /*
    * Registers a song in user library
    * Care: does not check if the song is already in library
    */
    private static void registerSong(Object id, String artist, long artistId, String album, long albumId,
                                     int albumTrack, long duration, String name, int source)
    {
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

            if(source == SOURCE_LOCAL_LIB) idsorted_albums.put(albumId, songAlbum);

            songArtist.addAlbum(songAlbum);
        }

        Song song = new Song(id, name, songArtist, songAlbum, albumTrack, duration, source);
        songAlbum.addSong(song);
        songs.add(song);
        if(source == SOURCE_LOCAL_LIB) idsorted_songs.put((long) id, song);

        if(currentCallback != null) currentCallback.onLibraryChange();
    }

    private static void registerLocalSongs(Context appContext)
    {
        /* get content resolver and init temp sorted arrays */
        final ContentResolver musicResolver = appContext.getContentResolver();
        idsorted_albums = new LongSparseArray<Album>();
        idsorted_songs = new LongSparseArray<Song>();

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

                registerSong(thisId, thisArtist, artistId, thisAlbum, albumId, albumTrack, thisDuration, thisTitle, SOURCE_LOCAL_LIB);
            }
            while (musicCursor.moveToNext());
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
                ArrayList<Song> thisList = new ArrayList<Song>();
                Cursor thisPlaylistCursor = musicResolver.query(MediaStore.Audio.Playlists.Members.getContentUri("external", thisId), null, null, null, null);
                if(thisPlaylistCursor!=null && thisPlaylistCursor.moveToFirst())
                {
                    int audioIdColumn = thisPlaylistCursor.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID);

                    do
                    {
                        thisList.add(idsorted_songs.get(thisPlaylistCursor.getLong(audioIdColumn)));
                    } while(thisPlaylistCursor.moveToNext());
                }

                Playlist list = new Playlist(thisId, thisName, thisList);
                playlists.add(list);
                if(currentCallback != null) currentCallback.onLibraryChange();
            } while(playlistCursor.moveToNext());
            idsorted_songs = null;
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
                if(a != null) a.setAlbumArt(BitmapFactory.decodeFile(path));
            } while (albumCursor.moveToNext());
        }
        idsorted_albums = null;
    }

    /*
    * Query the library for objects
    */
    public static ArrayList<LibraryObject> query(String s)
    {
        ArrayList<LibraryObject> tr = new ArrayList<LibraryObject>();
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
}
