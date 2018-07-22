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

import java.io.Serializable;

public abstract class LibraryObject implements Serializable
{
    protected String name;
    protected SongSources sources;
    protected boolean handled;

    private Bitmap miniatureArt;
    private boolean hasArt = false;
    private String artPath;
    private boolean artLoad;

    public LibraryObject() {this.sources = new SongSources();}

    public String getName() {return this.name;}
    @Override public String toString() {return this.name;}
    public SongSources getSources() {return sources;}
    public boolean isHandled() {return handled;}
    public void setHandled(boolean handled) {this.handled = handled;}

    public void setArt(String path, Bitmap miniatureArt)
    {
        this.hasArt = true;
        this.miniatureArt = miniatureArt;
        this.artPath = path;
        this.artLoad = false;
    }
    public void setArtLoading() {this.artLoad = true;}
    public boolean getArtLoading() {return this.artLoad;}
    public Bitmap getArtMiniature() {return miniatureArt;}
    public Bitmap getArt() {return BitmapFactory.decodeFile(artPath);}
    public boolean hasArt() {return hasArt;}

    public String getType() {return this.getClass().getSimpleName();}

    public void setName(String name) {this.name = name;}
}
