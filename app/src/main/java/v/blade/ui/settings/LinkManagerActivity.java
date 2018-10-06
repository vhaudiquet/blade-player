package v.blade.ui.settings;

import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import v.blade.R;
import v.blade.library.LibraryService;
import v.blade.library.Song;

public class LinkManagerActivity extends AppCompatActivity {
    ListView linkList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTheme(ThemesActivity.currentAppTheme);

        setContentView(R.layout.activity_link_manager);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        linkList = findViewById(R.id.link_list);
        linkList.setBackgroundColor(ContextCompat.getColor(this, ThemesActivity.currentColorBackground));
        linkList.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return LibraryService.songLinks.size();
            }

            @Override
            public Object getItem(int position) {
                return position;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Song currentSong = (Song) LibraryService.songLinks.keySet().toArray()[position];

                ViewHolder viewHolder;

                if (convertView == null) {
                    viewHolder = new ViewHolder();

                    //map to song layout
                    convertView = LayoutInflater.from(LinkManagerActivity.this).inflate(R.layout.linkmanager_list_layout, parent, false);

                    //get title and subtitle views
                    viewHolder.originalSongTitle = convertView.findViewById(R.id.element_title);
                    viewHolder.originalSongInfo = convertView.findViewById(R.id.element_subtitle);
                    viewHolder.image = convertView.findViewById(R.id.element_image);
                    viewHolder.links = convertView.findViewById(R.id.element_list);

                    convertView.setTag(viewHolder);
                } else viewHolder = (ViewHolder) convertView.getTag();

                viewHolder.image.setImageBitmap(currentSong.getAlbum().getArtMiniature());
                viewHolder.originalSongTitle.setText(currentSong.getTitle());
                viewHolder.originalSongInfo.setText(currentSong.getAlbum().getName() + " - " + currentSong.getArtist().getName());
                List<Song> linkedSongs = LibraryService.songLinks.get(currentSong);
                viewHolder.links.setAdapter(new BaseAdapter() {
                    @Override
                    public int getCount() {
                        return linkedSongs.size();
                    }

                    @Override
                    public Object getItem(int i) {
                        return linkedSongs.get(i);
                    }

                    @Override
                    public long getItemId(int i) {
                        return i;
                    }

                    @Override
                    public View getView(int position, View convertView, ViewGroup viewGroup) {
                        Song currentSong1 = linkedSongs.get(position);

                        new ViewHolder();
                        ViewHolder viewHolder;

                        if (convertView == null) {
                            viewHolder = new ViewHolder();

                            //map to song layout
                            convertView = LayoutInflater.from(LinkManagerActivity.this).inflate(R.layout.list_layout, parent, false);

                            //get title and subtitle views
                            viewHolder.songTitle = convertView.findViewById(R.id.element_title);
                            viewHolder.songInfo = convertView.findViewById(R.id.element_subtitle);
                            viewHolder.image = convertView.findViewById(R.id.element_image);
                            viewHolder.more = convertView.findViewById(R.id.element_more);

                            viewHolder.more.setImageResource(R.drawable.ic_cancel_black);

                            viewHolder.image.setVisibility(View.GONE);
                            viewHolder.image.setEnabled(false);
                            convertView.setTag(viewHolder);
                        } else viewHolder = (ViewHolder) convertView.getTag();

                        viewHolder.songTitle.setText(currentSong1.getTitle());
                        viewHolder.songInfo.setText(currentSong1.getAlbum().getName() + " - " + currentSong1.getArtist().getName());

                        viewHolder.more.setOnClickListener(view -> {
                            LibraryService.songLinks.get(currentSong).remove(currentSong1);
                            notifyDataSetChanged();
                            LibraryService.writeLinks();

                            //put message to user to notify him we need resync
                            Toast.makeText(LinkManagerActivity.this, getText(R.string.pls_resync), Toast.LENGTH_SHORT).show();
                        });

                        return convertView;
                    }

                    class ViewHolder {
                        ImageView image;
                        TextView songTitle;
                        TextView songInfo;
                        ImageView more;
                    }
                });

                return convertView;
            }

            class ViewHolder {
                TextView originalSongTitle;
                TextView originalSongInfo;
                ImageView image;
                ListView links;
            }
        });
    }
}
