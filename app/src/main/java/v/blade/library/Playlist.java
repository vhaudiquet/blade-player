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

public class Playlist extends LibraryObject
{
    private ArrayList<Song> content;

    private boolean mine = true;
    private String owner;
    private Object ownerID;
    private boolean collaborative = false;
    String path;

    public Playlist(String name, ArrayList<Song> content)
    {
        this.name = name;
        this.content = content;
    }

    public ArrayList<Song> getContent() {return content;}
    public void setOwner(String owner, Object ownerID)
    {
        this.mine = false;
        this.owner = owner;
        this.ownerID = ownerID;
    }

    public void setCollaborative() {this.collaborative = true;}
    public boolean isMine() {return this.mine;}
    public boolean isCollaborative() {return this.collaborative;}
    public String getOwner() {return this.owner;}
    public Object getOwnerID() {return this.ownerID;}

    public String getPath() {return path;}
    public void setPath(String s) {path = s;}
}
