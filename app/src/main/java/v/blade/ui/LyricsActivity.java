package v.blade.ui;

import android.os.Bundle;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.text.Spanned;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import v.blade.R;
import v.blade.library.Song;
import v.blade.ui.settings.ThemesActivity;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;

public class LyricsActivity extends AppCompatActivity
{
    private static final String CLIENT_ID = "FRg0YEVeEV898Z7MQxe-tnI6rgyNMLa_A0Pfj8wE1BqGZ5j5txgD4t8EsCA8FvRH";
    private static final String CLIENT_ACCESS_TOKEN = "BSbt1m4YALb2bKNhwFsWWM5wDIbPV2cdLQPrcxDdvXcKVuoJZelnoiK9UUN-q2jo";

    private TextView songTitle, songLyrics;
    Song currentObject;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        //set theme
        setTheme(ThemesActivity.currentAppTheme);

        setContentView(R.layout.activity_lyrics);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        songTitle = findViewById(R.id.song_title);
        songLyrics = findViewById(R.id.song_lyrics);

        currentObject = PlayerConnection.getService().getCurrentSong();
        songTitle.setText(currentObject.getTitle());

        Thread t = new Thread()
        {
            public void run()
            {
                Looper.prepare();

                try
                {
                    //search for song id on genius
                    URL searchUrl = new URL("https://api.genius.com/search?q=" + currentObject.getTitle().replaceAll(" ", "%20") + "%20" + currentObject.getArtist().getName().replaceAll(" ", "%20"));
                    HttpsURLConnection urlConnection = (HttpsURLConnection) searchUrl.openConnection();
                    urlConnection.setRequestMethod("GET");
                    urlConnection.addRequestProperty("Authorization", "Bearer " + CLIENT_ACCESS_TOKEN);
                    urlConnection.addRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.0; SM-G930V Build/NRD90M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.125 Mobile Safari/537.36");

                    urlConnection.connect();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    String songId = reader.readLine();
                    reader.close();

                    urlConnection.disconnect();

                    //check if song was found on Genius or not
                    JSONObject object = new JSONObject(songId);
                    JSONArray hitsResponse = object.getJSONObject("response").getJSONArray("hits");
                    if(hitsResponse.length() != 0)
                    {
                        JSONObject first = hitsResponse.getJSONObject(0);
                        //System.out.println("first = " + first.toString());
                        JSONObject ff = first.getJSONObject("result");
                        //System.out.println("ff = " + ff.toString());
                        String path = ff.getString("path");

                        //now that we have the song id, get the lyrics
                        //TODO : try to improve speed here, really slow...
                        URL songUrl = new URL("https://genius.com" + path);
                        HttpsURLConnection urlConnection2 = (HttpsURLConnection) songUrl.openConnection();
                        urlConnection2.setRequestMethod("GET");
                        //urlConnection2.addRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.0; SM-G930V Build/NRD90M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.125 Mobile Safari/537.36");

                        urlConnection2.connect();
                        System.out.println("LyricsRequest (to " + songUrl.toString() + ") response : " + urlConnection2.getResponseCode() + " : " + urlConnection2.getResponseMessage());
                        BufferedReader reader2 = new BufferedReader(new InputStreamReader(urlConnection2.getInputStream()));
                        String div = "";
                        boolean reached = false;
                        String str;
                        while((str = reader2.readLine()) != "")
                        {
                            if(str.contains("<div class=\"lyrics\">")) reached = true;
                            if(reached) div += (str + "\n");
                            if(reached) if(str.contains("</div>")) break;
                        }
                        reader2.close();
                        urlConnection2.disconnect();

                        if(!reached)
                        {
                            runOnUiThread(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    songLyrics.setText(R.string.lyrics_parsing_error);
                                }
                            });
                        }
                        else
                        {
                            Spanned s = Html.fromHtml(div, null, null);
                            runOnUiThread(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    songLyrics.setText(s.toString());
                                }
                            });
                        }
                    }
                    else
                    {
                        //song was not found
                        runOnUiThread(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                songLyrics.setText(R.string.lyrics_not_found);
                            }
                        });
                    }
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                    runOnUiThread(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            songLyrics.setText(R.string.lyrics_obtention_err);
                        }
                    });
                }
            }
        };
        t.start();

        //set theme
        findViewById(R.id.lyrics_viewer_layout).setBackgroundColor(ContextCompat.getColor(this, ThemesActivity.currentColorBackground));
    }


}
