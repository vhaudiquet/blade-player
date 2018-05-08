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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.ArrayList;

public class Album extends LibraryObject
{
    public static final int minatureSize = 80;

    private ArrayList<Song> songs;
    private Artist artist;
    private long id;
    private Bitmap miniatureArt;
    private boolean hasArt = false;
    private String albumArtPath;

    public Album(String name, Artist artist, long id)
    {
        this.artist = artist;
        this.name = name;
        this.id = id;
        this.songs = new ArrayList<Song>();
    }

    public ArrayList<Song> getSongs() {return songs;}
    public Artist getArtist() {return artist;}
    public long getId() {return id;}
    public Bitmap getAlbumArtMiniature() {return miniatureArt;}
    public Bitmap getAlbumArt() {return BitmapFactory.decodeFile(albumArtPath);}
    public boolean hasAlbumArt() {return hasArt;}

    public void addSong(Song song) {this.songs.add(song);}
    public void setAlbumArt(String path, Bitmap miniatureArt)
    {
        this.hasArt = true;
        this.miniatureArt = miniatureArt;
        this.albumArtPath = path;
    }
}
