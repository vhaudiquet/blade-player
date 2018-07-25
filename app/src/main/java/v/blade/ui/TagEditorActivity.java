package v.blade.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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
import v.blade.library.LibraryService;
import v.blade.library.Song;
import v.blade.library.SongSources;

import java.io.File;
import java.util.Arrays;

public class TagEditorActivity extends AppCompatActivity
{
    private Song currentSong;
    EditText nameEdit, albumEdit, artistEdit, yearEdit, trackEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
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

        //get current song and fill details
        if(!(MainActivity.selectedObject instanceof Song)) onBackPressed();

        currentSong = (Song) MainActivity.selectedObject;

        //check if song has a registered path
        if(currentSong.getPath() == null) onBackPressed();

        nameEdit.setText(currentSong.getTitle());
        albumEdit.setText(currentSong.getAlbum().getName());
        artistEdit.setText(currentSong.getArtist().getName());
        yearEdit.setText(currentSong.getYear() == 0 ? "" : "" + currentSong.getYear());
        trackEdit.setText(currentSong.getTrackNumber() == 0 ? "" : "" + currentSong.getTrackNumber());
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
            TagOptionSingleton.getInstance().setAndroid(true);
            File basicFile = new File(currentSong.getPath());
            AudioFile currentFile = AudioFileIO.read(basicFile);

            Tag currentTag = currentFile.getTag();
            currentTag.setField(FieldKey.TITLE, nameEdit.getText().toString());
            currentTag.setField(FieldKey.ALBUM, albumEdit.getText().toString());
            currentTag.setField(FieldKey.ARTIST, artistEdit.getText().toString());
            currentFile.commit();

            //actualize contentprovider
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(currentFile.getFile())));

            //actualize song in library
            SongSources.SongSource localOld = currentSong.getSources().getLocal();
            Object oldId = localOld.getId();
            LibraryService.unregisterSong(currentSong, localOld);
            LibraryService.registerSong(artistEdit.getText().toString(), albumEdit.getText().toString(),
                    Integer.parseInt(trackEdit.getText().toString()), Integer.parseInt(yearEdit.getText().toString()), currentSong.getDuration(), nameEdit.getText().toString(),
                    localOld);
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
