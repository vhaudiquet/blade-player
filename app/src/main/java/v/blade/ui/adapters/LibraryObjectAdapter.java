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
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import v.blade.R;
import v.blade.library.*;

import java.util.ArrayList;
import java.util.List;

public class LibraryObjectAdapter extends BaseAdapter
{
    private List<LibraryObject> original;
    private List<LibraryObject> libraryObjects;
    private Activity context;
    private LayoutInflater inflater;

    private ImageView.OnClickListener moreClickListener;
    private ImageView.OnTouchListener moreTouchListener;
    private int moreImageRessource = 0;
    private boolean hideMore;

    private boolean repaintSongBackground = false;
    private int selectedPosition;

    static class ViewHolder
    {
        TextView title;
        TextView subtitle;
        ImageView image;
        ImageView source0;
        ImageView source1;
        ImageView source2;
    }

    public LibraryObjectAdapter(final Activity context, List libraryObjects)
    {this(context, libraryObjects, true);}

    public LibraryObjectAdapter(final Activity context, List objects, boolean libraryCallback)
    {
        this.original = objects;
        this.libraryObjects = new ArrayList<>(original);
        this.context = context;
        this.inflater = LayoutInflater.from(context);

        if(libraryCallback) LibraryService.currentCallback = new LibraryService.UserLibraryCallback()
        {
            @Override
            public void onLibraryChange()
            {
                context.runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        libraryObjects = new ArrayList<>(original);
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
    public void setMoreImage(int ressource) {this.moreImageRessource = ressource;}
    public void setHideMore(boolean hideMore) {this.hideMore = hideMore;}
    public void repaintSongBackground() {repaintSongBackground = true;}
    public void setSelectedPosition(int selectedPosition) {this.selectedPosition  = selectedPosition;}
    public void resetList(List objects)
    {
        this.original = objects;
        this.libraryObjects = new ArrayList<>(original);
        this.notifyDataSetChanged();
    }

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

    public List getObjectList() {return libraryObjects;}
    public List<LibraryObject> getObjects() {return libraryObjects;}

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
            mViewHolder.source0 = convertView.findViewById(R.id.element_source0);
            mViewHolder.source1 = convertView.findViewById(R.id.element_source1);
            mViewHolder.source2 = convertView.findViewById(R.id.element_source2);

            //set 'more' action
            ImageView more = convertView.findViewById(R.id.element_more);
            if(moreClickListener != null) more.setOnClickListener(moreClickListener);
            if(moreTouchListener != null) more.setOnTouchListener(moreTouchListener);
            if(moreImageRessource != 0) more.setImageResource(moreImageRessource);
            if(hideMore) more.setVisibility(View.INVISIBLE);

            convertView.setTag(mViewHolder);
        }
        else mViewHolder = (ViewHolder) convertView.getTag();

        ImageView more = convertView.findViewById(R.id.element_more);
        more.setTag(obj);

        mViewHolder.title.setText(obj.getName());

        if(obj instanceof Song)
        {
            Song song = (Song) obj;
            //set subtitle : Artist
            mViewHolder.subtitle.setText(song.getArtist().getName());

            //set image to song album art
            if(song.getAlbum().hasArt())
                mViewHolder.image.setImageBitmap(song.getAlbum().getArtMiniature());
            else
                mViewHolder.image.setImageResource(R.drawable.ic_albums);

            if(repaintSongBackground)
                if(position != selectedPosition)
                    convertView.setBackground(ContextCompat.getDrawable(context, R.color.colorBackground));
                else convertView.setBackground(ContextCompat.getDrawable(context, R.color.colorPrimaryLight));
        }
        else if(obj instanceof Album)
        {
            //set subtitle : Artist
            mViewHolder.subtitle.setText(((Album)obj).getArtist().getName());

            //set image to art
            if(((Album) obj).hasArt()) mViewHolder.image.setImageBitmap(((Album) obj).getArtMiniature());
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
            if(((Playlist) obj).getContent() != null)
                mViewHolder.subtitle.setText(((Playlist) obj).getContent().size() + " " + context.getResources().getString(R.string.songs).toLowerCase() +
                    (((Playlist) obj).isCollaborative() ? (" - " + context.getString(R.string.collaborative)) : "") +
                    (((Playlist) obj).isMine() ? "" : (" - " + ((Playlist) obj).getOwner())));
            else mViewHolder.subtitle.setText("");

            //set image to playlist image
            if(obj.hasArt()) mViewHolder.image.setImageBitmap(obj.getArtMiniature());
            else mViewHolder.image.setImageResource(R.drawable.ic_playlists);
        }

        //set sources images
        SongSources.SongSource source0 = obj.getSources().getSourceByPriority(0);
        SongSources.SongSource source1 = obj.getSources().getSourceByPriority(1);
        SongSources.SongSource source2 = obj.getSources().getSourceByPriority(2);

        if(source0 == null) mViewHolder.source0.setImageResource(0);
        else mViewHolder.source0.setImageResource(source0.getSource().getIconImage());
        if(source1 == null) mViewHolder.source1.setImageResource(0);
        else mViewHolder.source1.setImageResource(source1.getSource().getIconImage());
        if(source2 == null) mViewHolder.source2.setImageResource(0);
        else mViewHolder.source2.setImageResource(source2.getSource().getIconImage());

        return convertView;
    }
}
