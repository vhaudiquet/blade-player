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

public class LibraryObject
{
    protected String name;
    protected SongSources sources;
    protected boolean handled;

    public LibraryObject() {this.sources = new SongSources();}

    public String getName() {return this.name;}
    @Override public String toString() {return this.name;}
    public SongSources getSources() {return sources;}
    public boolean isHandled() {return handled;}
    public void setHandled(boolean handled) {this.handled = handled;}
}
