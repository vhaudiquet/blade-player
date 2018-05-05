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
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/*
* This class parses and contains the current user library
*/
public class UserLibrary
{
    private static ArrayList<Artist> artists;
    private static ArrayList<Album> albums;
    private static ArrayList<Song> songs;
    private static ArrayList<Playlist> playlists;

    /* sorted arrays used only for short amounts of time (cause of their size) */
    private static Album[] idsorted_albums;
    private static Song[] idsorted_songs;

    public static ArrayList<Artist> getArtists() {return artists;}
    public static ArrayList<Album> getAlbums() {return albums;}
    public static ArrayList<Song> getSongs() {return songs;}
    public static ArrayList<Playlist> getPlaylists() {return playlists;}

    /*
    * Registers a song in user library
    * Care: does not check if the song is already in library
    */
    private static void registerSong(long id, String artist, long artistId, String album, long albumId, int albumTrack, long duration, String name)
    {
        Artist songArtist = null;
        for(int i = 0;i<artists.size();i++) if(artists.get(i).getName().equals(artist)) songArtist = artists.get(i);
        if(songArtist == null) {songArtist = new Artist(artist, artistId); artists.add(songArtist);}

        Album songAlbum = null;
        for(int i = 0;i<songArtist.getAlbums().size();i++) if(songArtist.getAlbums().get(i).getName().equals(album)) songAlbum = songArtist.getAlbums().get(i);
        if(songAlbum == null)
        {
            songAlbum = new Album(album, songArtist, albumId);
            albums.add(songAlbum);
            idsorted_albums[(int) albumId] = songAlbum;
            songArtist.addAlbum(songAlbum);
        }

        Song song = new Song(id, name, songArtist, songAlbum, albumTrack, duration);
        songAlbum.addSong(song);
        songs.add(song);
        idsorted_songs[(int) id] = song;
    }

    public static void registerLocalSongs(Context appContext)
    {
        /* init the lists (to make sure they are empty) */
        artists = new ArrayList<Artist>();
        albums = new ArrayList<Album>();
        songs = new ArrayList<Song>();
        playlists = new ArrayList<Playlist>();

        /* get content resolver and init temp sorted arrays */
        final ContentResolver musicResolver = appContext.getContentResolver();
        idsorted_albums = new Album[5000];
        idsorted_songs = new Song[100000];

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

                registerSong(thisId, thisArtist, artistId, thisAlbum, albumId, albumTrack, thisDuration, thisTitle);
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
                        thisList.add(idsorted_songs[(int) thisPlaylistCursor.getLong(audioIdColumn)]);
                    } while(thisPlaylistCursor.moveToNext());
                }

                Playlist list = new Playlist(thisId, thisName, thisList);
                playlists.add(list);
            } while(playlistCursor.moveToNext());
            idsorted_songs = null;
        }

        /* sort collection by alphabetical order */
        Collections.sort(songs, new Comparator<Song>(){
            public int compare(Song a, Song b){ return a.getTitle().compareTo(b.getTitle()); }
        });
        Collections.sort(albums, new Comparator<Album>(){
            public int compare(Album a, Album b){
                return a.getName().compareTo(b.getName());
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

        /* sort each album per tracks */
        for(Album alb : albums)
        {
            Collections.sort(alb.getSongs(), new Comparator<Song>() {
                @Override
                public int compare(Song o1, Song o2) {return o1.getTrackNumber() - o2.getTrackNumber();}
            });
        }

        /* now let's get all albumarts, async to avoid lag */
        new Thread()
        {
            @Override
            public void run()
            {
                Cursor albumCursor = musicResolver.query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, null, null, null, null, null);
                if(albumCursor!=null && albumCursor.moveToFirst())
                {
                    int idCol = albumCursor.getColumnIndex(MediaStore.Audio.Albums._ID);
                    int artCol = albumCursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART);

                    do
                    {
                        long thisId = albumCursor.getLong(idCol);
                        String path = albumCursor.getString(artCol);

                        Album a = idsorted_albums[(int) thisId];
                        if(a != null) a.setAlbumArt(BitmapFactory.decodeFile(path));
                    }
                    while (albumCursor.moveToNext());
                }
                idsorted_albums = null;
            }
        }.start();
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
