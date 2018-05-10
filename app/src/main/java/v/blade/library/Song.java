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

public class Song extends LibraryObject
{
    private Artist artist;
    private Album album;
    private int track;
    long duration;

    public Song(String title, Artist artist, Album album, int albumTrack, long duration)
    {
        this.name = title;
        this.artist = artist;
        this.album = album;
        this.track = albumTrack;
        this.duration = duration;
    }

    public String getTitle() {return getName();}
    public Artist getArtist() {return artist;}
    public Album getAlbum() {return album;}
    public int getTrackNumber() {return track;}
    public long getDuration() {return duration;}
}
