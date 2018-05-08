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
package v.blade.ui.adapters;

import android.app.Activity;
import android.content.Context;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import v.blade.R;
import v.blade.library.*;
import v.blade.ui.MainActivity;

import java.util.ArrayList;

public class LibraryObjectAdapter extends BaseAdapter
{
    private ArrayList<LibraryObject> original;
    private ArrayList<LibraryObject> libraryObjects;
    private Activity context;
    private LayoutInflater inflater;

    private ImageView.OnClickListener moreClickListener;
    private ImageView.OnTouchListener moreTouchListener;
    private int moreImageRessource = 0;

    static class ViewHolder
    {
        TextView title;
        TextView subtitle;
        ImageView image;
    }

    public LibraryObjectAdapter(final Activity context, ArrayList objects)
    {
        this.original = objects;
        this.libraryObjects = (ArrayList<LibraryObject>) objects.clone();
        this.context = context;
        this.inflater = LayoutInflater.from(context);

        UserLibrary.currentCallback = new UserLibrary.UserLibraryCallback()
        {
            @Override
            public void onLibraryChange()
            {
                context.runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        libraryObjects = (ArrayList<LibraryObject>) original.clone();
                        LibraryObjectAdapter.this.notifyDataSetChanged();
                    }
                });
            }
        };
    }

    public void registerMoreClickListener(ImageView.OnClickListener clickListener)
    {this.moreClickListener = clickListener;}
    public void registerMoreTouchListener(ImageView.OnTouchListener touchListener)
    {this.moreTouchListener = touchListener;}
    public void setMoreImage(int ressource)
    {this.moreImageRessource = ressource;}

    @Override
    public int getCount() {return libraryObjects.size();}

    @Override
    public Object getItem(int position) {return libraryObjects.get(position);}

    @Override
    public long getItemId(int position)
    {
        if(position < 0 || position >= libraryObjects.size()) return -1;
        return position;
    }
    @Override public boolean hasStableIds() {return true;}

    public ArrayList<LibraryObject> getObjects() {return libraryObjects;}

    @Override
    public View getView(int position, View convertView, ViewGroup parent)
    {
        ViewHolder mViewHolder;

        //get item using position
        LibraryObject obj = libraryObjects.get(position);

        if(convertView == null)
        {
            mViewHolder = new ViewHolder();

            //map to song layout
            convertView = inflater.inflate(R.layout.list_layout, parent, false);

            //get title and subtitle views
            mViewHolder.title = convertView.findViewById(R.id.element_title);
            mViewHolder.subtitle = convertView.findViewById(R.id.element_subtitle);
            mViewHolder.image = convertView.findViewById(R.id.element_image);

            //set 'more' action
            ImageView more = convertView.findViewById(R.id.element_more);
            if(moreClickListener != null) more.setOnClickListener(moreClickListener);
            if(moreTouchListener != null) more.setOnTouchListener(moreTouchListener);
            if(moreImageRessource != 0) more.setImageResource(moreImageRessource);

            convertView.setTag(mViewHolder);
        }
        else mViewHolder = (ViewHolder) convertView.getTag();

        ImageView more = convertView.findViewById(R.id.element_more);
        more.setTag(obj);

        mViewHolder.title.setText(obj.getName());

        if(obj instanceof Song)
        {
            //set subtitle : Artist
            mViewHolder.subtitle.setText(((Song) obj).getArtist().getName());

            //set image to song album art
            if(((Song) obj).getAlbum().getAlbumArt() != null)
                mViewHolder.image.setImageBitmap(((Song) obj).getAlbum().getAlbumArt());
            else
                mViewHolder.image.setImageResource(R.drawable.ic_albums);

            if(((Song) obj).getSource() == UserLibrary.SOURCE_SPOTIFY)
                convertView.setBackground(ContextCompat.getDrawable(context, R.color.colorSpotify));
            else convertView.setBackground(ContextCompat.getDrawable(context, R.color.colorAccent));
        }
        else if(obj instanceof Album)
        {
            //set subtitle : Artist
            mViewHolder.subtitle.setText(((Album)obj).getArtist().getName());

            //set image to art
            if(((Album) obj).getAlbumArt() != null) mViewHolder.image.setImageBitmap(((Album) obj).getAlbumArt());
            else mViewHolder.image.setImageResource(R.drawable.ic_albums);
        }
        else if(obj instanceof Artist)
        {
            //set subtitle : song numbers
            mViewHolder.subtitle.setText(((Artist) obj).getSongCount() + " " + context.getResources().getString(R.string.songs).toLowerCase());

            //set image to default artist image
            mViewHolder.image.setImageResource(R.drawable.ic_artists);
        }
        else if(obj instanceof Playlist)
        {
            //set subtitle : song numbers
            mViewHolder.subtitle.setText(((Playlist) obj).getContent().size() + " " + context.getResources().getString(R.string.songs).toLowerCase());

            //set image to default playlist image
            mViewHolder.image.setImageResource(R.drawable.ic_playlists);
        }

        return convertView;
    }
}
