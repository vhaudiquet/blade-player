package v.blade.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.deezer.sdk.model.Permissions;
import com.deezer.sdk.network.connect.event.DialogListener;
import com.mobeta.android.dslv.DragSortController;
import com.mobeta.android.dslv.DragSortListView;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;
import v.blade.R;
import v.blade.library.LibraryService;
import v.blade.library.SongSources;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class SourcesActivity extends AppCompatActivity
{
    private static int SPOTIFY_REQUEST_CODE = 1337;

    private SourceAdapter adapter;
    private DragSortListView.DropListener dropListener = new DragSortListView.DropListener()
    {
        @Override
        public void drop(int from, int to)
        {
            SongSources.Source toSwap = adapter.sources.get(from);

            // we reduced this source priority ; priority.set(to), increase all priorities on the way by 1
            if(from < to)
            {
                toSwap.setPriority(adapter.sources.get(to).getPriority());
                from++;
                for(;from<=to;from++)
                {
                    adapter.sources.get(from).setPriority(adapter.sources.get(from).getPriority()+1);
                }
            }
            // we increased this source priority ; priority.set(to), reduce all priorities on the way by 1
            else
            {
                toSwap.setPriority(adapter.sources.get(to).getPriority());
                from--;
                for(;from>=to;from--)
                {
                    adapter.sources.get(from).setPriority(adapter.sources.get(from).getPriority()-1);
                }
            }

            Collections.sort(adapter.sources, new Comparator<SongSources.Source>()
            {
                @Override
                public int compare(SongSources.Source o1, SongSources.Source o2)
                {
                    return o2.getPriority() - o1.getPriority();
                }
            });
            adapter.notifyDataSetChanged();

            //reload songs from source
            SharedPreferences accountsPrefs = getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = accountsPrefs.edit();
            editor.putInt("spotify_prior", SongSources.SOURCE_SPOTIFY.getPriority());
            editor.putInt("deezer_prior", SongSources.SOURCE_DEEZER.getPriority());
            editor.apply();

            Toast.makeText(SourcesActivity.this, getText(R.string.pls_resync), Toast.LENGTH_SHORT).show();
        }
    };

    ListView.OnItemClickListener onItemClickListener = new AdapterView.OnItemClickListener()
    {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id)
        {
            SongSources.Source source = adapter.sources.get(position);
            if(source.isAvailable())
            {
                Toast.makeText(SourcesActivity.this, getString(R.string.already_connected), Toast.LENGTH_SHORT).show();
                return;
            }

            if(source == SongSources.SOURCE_SPOTIFY)
            {
                AuthenticationRequest.Builder builder =
                        new AuthenticationRequest.Builder(LibraryService.SPOTIFY_CLIENT_ID, AuthenticationResponse.Type.CODE,
                                LibraryService.SPOTIFY_REDIRECT_URI).setShowDialog(true);
                builder.setScopes(new String[]{"user-read-private", "streaming", "user-read-email", "user-follow-read",
                        "playlist-read-private", "playlist-read-collaborative", "user-library-read"});
                AuthenticationRequest request = builder.build();
                AuthenticationClient.openLoginActivity(SourcesActivity.this, SPOTIFY_REQUEST_CODE, request);
            }
            else if(source == SongSources.SOURCE_DEEZER)
            {
                String[] permissions = new String[] {Permissions.BASIC_ACCESS, Permissions.MANAGE_LIBRARY,
                        Permissions.EMAIL, Permissions.OFFLINE_ACCESS};

                LibraryService.deezerApi.authorize(SourcesActivity.this, permissions, new DialogListener()
                {
                    @Override
                    public void onComplete(Bundle bundle)
                    {
                        LibraryService.DEEZER_USER_SESSION.save(LibraryService.deezerApi, SourcesActivity.this.getApplicationContext());
                        SongSources.SOURCE_DEEZER.setAvailable(true);
                        SongSources.SOURCE_DEEZER.setPriority(adapter.sources.get(0).getPriority()+1);
                        SharedPreferences pref = getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = pref.edit();
                        editor.putInt("deezer_prior", SongSources.SOURCE_DEEZER.getPriority());
                        editor.apply();
                        adapter.notifyDataSetChanged();

                        Toast.makeText(SourcesActivity.this, getText(R.string.pls_resync), Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onCancel() {}

                    @Override
                    public void onException(Exception e) {}
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sources);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        DragSortListView sourcesListView = findViewById(R.id.sources_listview);
        adapter = new SourceAdapter(this);
        sourcesListView.setAdapter(adapter);
        DragSortController controller = new DragSortController(sourcesListView);
        controller.setDragHandleId(R.id.element_more);
        sourcesListView.setFloatViewManager(controller);
        sourcesListView.setOnTouchListener(controller);
        sourcesListView.setDropListener(dropListener);
        sourcesListView.setOnItemClickListener(onItemClickListener);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent)
    {
        super.onActivityResult(requestCode, resultCode, intent);

        if(requestCode == SPOTIFY_REQUEST_CODE)
        {
            AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, intent);
            if(response.getType() == AuthenticationResponse.Type.CODE)
            {
                final String code = response.getCode();
                Thread t = new Thread()
                {
                    public void run()
                    {
                        Looper.prepare();
                        try
                        {
                            URL apiUrl = new URL("https://accounts.spotify.com/api/token");
                            HttpsURLConnection urlConnection = (HttpsURLConnection) apiUrl.openConnection();
                            urlConnection.setDoInput(true);
                            urlConnection.setDoOutput(true);
                            urlConnection.setRequestMethod("POST");

                            //write POST parameters
                            OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
                            BufferedWriter writer = new BufferedWriter (new OutputStreamWriter(out, "UTF-8"));
                            writer.write("grant_type=authorization_code&");
                            writer.write("code=" + code + "&");
                            writer.write("redirect_uri=" + LibraryService.SPOTIFY_REDIRECT_URI + "&");
                            writer.write("client_id=" + LibraryService.SPOTIFY_CLIENT_ID + "&");
                            writer.write("client_secret=" + "3166d3b40ff74582b03cb23d6701c297");
                            writer.flush();
                            writer.close();
                            out.close();

                            urlConnection.connect();

                            System.out.println("[BLADE] [AUTH] Result : " + urlConnection.getResponseCode() + " " + urlConnection.getResponseMessage());

                            BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                            String result = reader.readLine();
                            reader.close();
                            result = result.substring(1);
                            result = result.substring(0, result.length()-1);
                            String[] results = result.split(",");
                            for(String param : results)
                            {
                                if(param.startsWith("\"access_token\":\""))
                                {
                                    param = param.replaceFirst("\"access_token\":\"", "");
                                    param = param.replaceFirst("\"", "");
                                    LibraryService.SPOTIFY_USER_TOKEN = param;
                                    SharedPreferences pref = getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
                                    SharedPreferences.Editor editor = pref.edit();
                                    editor.putString("spotify_token", LibraryService.SPOTIFY_USER_TOKEN);
                                    editor.commit();
                                }
                                else if(param.startsWith("\"refresh_token\":\""))
                                {
                                    param = param.replaceFirst("\"refresh_token\":\"", "");
                                    param = param.replaceFirst("\"", "");
                                    LibraryService.SPOTIFY_REFRESH_TOKEN = param;
                                    SharedPreferences pref = getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
                                    SharedPreferences.Editor editor = pref.edit();
                                    editor.putString("spotify_refresh_token", LibraryService.SPOTIFY_REFRESH_TOKEN);
                                    editor.commit();
                                }
                            }

                            SongSources.SOURCE_SPOTIFY.setAvailable(true);
                            SongSources.SOURCE_SPOTIFY.setPriority(adapter.sources.get(0).getPriority()+1);
                            SharedPreferences pref = getSharedPreferences(SettingsActivity.PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
                            SharedPreferences.Editor editor = pref.edit();
                            editor.putInt("spotify_prior", SongSources.SOURCE_SPOTIFY.getPriority());
                            editor.apply();
                            adapter.notifyDataSetChanged();
                            LibraryService.spotifyApi.setAccessToken(LibraryService.SPOTIFY_USER_TOKEN);

                            Toast.makeText(SourcesActivity.this, getText(R.string.pls_resync), Toast.LENGTH_SHORT).show();
                        }
                        catch(Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                };
                t.start();
            }
            else
            {
                System.err.println("Wrong reponse received.\n");
                System.err.println("Error : " + response.getError());
            }
        }
    }

    public static class SourceAdapter extends BaseAdapter
    {
        public ArrayList<SongSources.Source> sources;
        private Context context;

        class ViewHolder
        {
            ImageView source;
            TextView status;
            ImageView more;
        }

        public SourceAdapter(Context context)
        {
            this.context = context;

            sources = new ArrayList<>();
            sources.add(SongSources.SOURCE_SPOTIFY);
            sources.add(SongSources.SOURCE_DEEZER);
            Collections.sort(sources, new Comparator<SongSources.Source>() {
                @Override
                public int compare(SongSources.Source o1, SongSources.Source o2) {
                    return o2.getPriority() - o1.getPriority();
                }
            });
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
            ViewHolder mViewHolder;

            if(convertView == null)
            {
                mViewHolder = new ViewHolder();

                //map to layout
                convertView = LayoutInflater.from(context).inflate(R.layout.sources_list_layout, parent, false);

                //get imageview
                mViewHolder.source = convertView.findViewById(R.id.element_image);
                mViewHolder.status = convertView.findViewById(R.id.element_subtitle);

                convertView.setTag(mViewHolder);
            }
            else mViewHolder = (ViewHolder) convertView.getTag();

            SongSources.Source source = sources.get(position);
            mViewHolder.source.setImageResource(source.getLogoImage());
            mViewHolder.status.setText(source.isAvailable() ? context.getString(R.string.connected) : context.getString(R.string.disconnected));
            return convertView;
        }
    }
}
