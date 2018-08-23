package v.blade.ui.settings;

import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import v.blade.R;
import v.blade.library.LibraryService;
public class LinkManagerActivity extends AppCompatActivity
{
    ListView linkList;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setTheme(ThemesActivity.currentAppTheme);

        setContentView(R.layout.activity_link_manager);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        linkList = findViewById(R.id.link_list);
        linkList.setBackgroundColor(ContextCompat.getColor(this, ThemesActivity.currentColorBackground));
        linkList.setAdapter(new BaseAdapter()
        {
            class ViewHolder
            {
                TextView originalSongInfo;
                TextView replacedSongInfo;
                ImageView image;
                ImageView more;
            }

            @Override
            public int getCount() {return LibraryService.songLinks.size();}
            @Override
            public Object getItem(int position) {return position;}
            @Override
            public long getItemId(int position) {return position;}

            @Override
            public View getView(int position, View convertView, ViewGroup parent)
            {
                ViewHolder viewHolder;

                if(convertView == null)
                {
                    viewHolder = new ViewHolder();

                    //map to song layout
                    convertView = LayoutInflater.from(LinkManagerActivity.this).inflate(R.layout.list_layout, parent, false);

                    //get title and subtitle views
                    viewHolder.originalSongInfo = convertView.findViewById(R.id.element_title);
                    viewHolder.replacedSongInfo = convertView.findViewById(R.id.element_subtitle);
                    viewHolder.image = convertView.findViewById(R.id.element_image);
                    viewHolder.more = convertView.findViewById(R.id.element_more);

                    viewHolder.image.setVisibility(View.GONE);
                    convertView.setTag(viewHolder);
                }
                else viewHolder = (ViewHolder) convertView.getTag();

                return convertView;
            }
        });
    }
}
