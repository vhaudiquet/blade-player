package v.blade.ui.settings;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import com.mobeta.android.dslv.DragSortListView;
import v.blade.R;
import v.blade.library.SongSources;

import java.util.ArrayList;

public class SourcesActivity extends AppCompatActivity
{
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sources);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        DragSortListView sourcesListView = findViewById(R.id.sources_listview);
        sourcesListView.setAdapter(new SourceAdapter(this));
    }

    public static class SourceAdapter extends BaseAdapter
    {
        private ArrayList<SongSources.Source> sources;
        private Context context;

        public SourceAdapter(Context context)
        {
            this.context = context;

            sources = new ArrayList<>();
            if(SongSources.SOURCE_SPOTIFY.getPriority() > SongSources.SOURCE_DEEZER.getPriority())
            {
                sources.add(SongSources.SOURCE_SPOTIFY);
                sources.add(SongSources.SOURCE_DEEZER);
            }
            else
            {
                sources.add(SongSources.SOURCE_DEEZER);
                sources.add(SongSources.SOURCE_SPOTIFY);
            }
        }

        @Override
        public int getCount() {return sources.size();}
        @Override
        public Object getItem(int position) {return sources.get(position);}
        @Override
        public long getItemId(int position) {return position;}

        @Override
        public View getView(int position, View convertView, ViewGroup parent)
        {
            ImageView imageView = new ImageView(context);
            imageView.setImageResource(sources.get(position).getLogoImage());
            return imageView;
        }
    }
}
