package v.blade.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.content.ContextCompat;
import android.support.v4.provider.DocumentFile;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagOptionSingleton;
import v.blade.R;
import v.blade.library.*;
import v.blade.ui.settings.ThemesActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

public class TagEditorActivity extends AppCompatActivity
{
    private LibraryObject currentObject;
    EditText nameEdit, albumEdit, artistEdit, yearEdit, trackEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        //set theme
        setTheme(ThemesActivity.currentAppTheme);

        setContentView(R.layout.activity_tag_editor);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        //get components
        nameEdit = findViewById(R.id.name_edit);
        albumEdit = findViewById(R.id.album_edit);
        artistEdit = findViewById(R.id.artist_edit);
        yearEdit = findViewById(R.id.year_edit);
        trackEdit = findViewById(R.id.track_edit);

        //get current object and fill details
        currentObject = MainActivity.selectedObject;

        //check if object is local
        if(currentObject.getSources().getLocal() == null) onBackPressed();

        if(currentObject instanceof Song)
        {
            nameEdit.setText(currentObject.getName());
            albumEdit.setText(((Song) currentObject).getAlbum().getName());
            artistEdit.setText(((Song) currentObject).getArtist().getName());
            trackEdit.setText(((Song) currentObject).getTrackNumber() == 0 ? "" : "" + ((Song) currentObject).getTrackNumber());
            yearEdit.setText(((Song) currentObject).getYear() == 0 ? "" : "" + ((Song) currentObject).getYear());
        }
        else if(currentObject instanceof Album)
        {
            nameEdit.setEnabled(false);
            albumEdit.setText(currentObject.getName());
            artistEdit.setText(((Album) currentObject).getArtist().getName());
            trackEdit.setEnabled(false);
        }
        else if(currentObject instanceof Artist)
        {
            nameEdit.setEnabled(false);
            albumEdit.setEnabled(false);
            artistEdit.setText(currentObject.getName());
            trackEdit.setEnabled(false);
            yearEdit.setEnabled(false);
        }

        //set theme
        findViewById(R.id.tag_editor_layout).setBackgroundColor(ContextCompat.getColor(this, ThemesActivity.currentColorBackground));
    }


    /*
    * Called by cancel button, cancel this activity and returns to MainActivity
     */
    public void cancel(View v)
    {
        this.onBackPressed();
    }

    /*
    * Called by save button, saves the metadata to the android library / id3 tags
     */
    public void save(View v)
    {
        try
        {
            //write tags
            //TODO : build tag edition in a manner that is compatible with android SD Card storage managment

            ArrayList<Song> songs = new ArrayList<>();
            if(currentObject instanceof Song)
            {
                songs.add((Song) currentObject);
            }
            else if(currentObject instanceof Album)
            {
                songs.addAll(((Album) currentObject).getSongs());
            }
            else if(currentObject instanceof Artist)
            {
                for(Album alb : ((Artist) currentObject).getAlbums())
                    songs.addAll(alb.getSongs());
            }

            TagOptionSingleton.getInstance().setAndroid(true);

            for(Song currentSong : songs)
            {
                File basicFile = new File(currentSong.getPath());
                AudioFile currentFile = AudioFileIO.read(basicFile);

                boolean nameEditE = nameEdit.isEnabled() && !nameEdit.getText().toString().equals("");
                boolean albumEditE = albumEdit.isEnabled() && !albumEdit.getText().toString().equals("");
                boolean artistEditE = artistEdit.isEnabled() && !artistEdit.getText().toString().equals("");
                boolean yearEditE = yearEdit.isEnabled() && !yearEdit.getText().toString().equals("");
                boolean trackEditE = trackEdit.isEnabled() && !trackEdit.getText().toString().equals("");

                Tag currentTag = currentFile.getTagOrCreateAndSetDefault();
                if(nameEditE)
                    currentTag.setField(FieldKey.TITLE, nameEdit.getText().toString());
                if(albumEditE)
                    currentTag.setField(FieldKey.ALBUM, albumEdit.getText().toString());
                if(artistEditE)
                    currentTag.setField(FieldKey.ARTIST, artistEdit.getText().toString());
                if(yearEditE)
                    currentTag.setField(FieldKey.YEAR, yearEdit.getText().toString());
                if(trackEditE)
                    currentTag.setField(FieldKey.TRACK, trackEdit.getText().toString());
                currentFile.commit();

                //actualize contentprovider
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(currentFile.getFile())));

                //actualize song in library
                SongSources.SongSource localOld = currentSong.getSources().getLocal();
                LibraryService.unregisterSong(currentSong, localOld);
                LibraryService.registerSong(artistEditE ? artistEdit.getText().toString() : currentSong.getArtist().getName(),
                        albumEditE ? albumEdit.getText().toString() : currentSong.getAlbum().getName(),
                        trackEditE ? Integer.parseInt(trackEdit.getText().toString()) : currentSong.getTrackNumber(),
                        yearEditE ? Integer.parseInt(yearEdit.getText().toString()) : currentSong.getYear(),
                        currentSong.getDuration(),
                        nameEditE ? nameEdit.getText().toString() : currentSong.getName(),
                        localOld);
            }

            MainActivity.selectedObject = null;

            Intent intent = new Intent(TagEditorActivity.this, MainActivity.class);
            startActivity(intent);
        }
        catch (Exception e)
        {
            Toast.makeText(this, getString(R.string.tag_save_failed) + " : " + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
}
