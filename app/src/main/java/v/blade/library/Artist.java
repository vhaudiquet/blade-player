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

import java.util.ArrayList;

public class Artist extends LibraryObject
{
    private ArrayList<Album> albums;

    public Artist(String name)
    {
        this.name = name;
        this.albums = new ArrayList<>();
    }

    public ArrayList<Album> getAlbums() {return albums;}

    public void addAlbum(Album album) {this.albums.add(album);}

    public int getSongCount()
    {
        int songCount = 0;

        for (Album album : albums) {
            songCount += album.getSongs().size();
        }

        return songCount;
    }
}
