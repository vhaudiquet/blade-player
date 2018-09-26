package v.blade.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v4.provider.DocumentFile;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
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

public class TagEditorActivity extends AppCompatActivity
{
    private LibraryObject currentObject;
    EditText nameEdit, albumEdit, artistEdit, yearEdit, trackEdit;
    ImageView imageEdit;

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
        imageEdit = findViewById(R.id.image_edit);

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
            imageEdit.setImageBitmap(((Song) currentObject).getAlbum().getArt());

            if(((Song) currentObject).getAlbum().hasArt()) imageEdit.setImageBitmap(((Song) currentObject).getAlbum().getArt());
            else imageEdit.setImageResource(R.drawable.ic_albums);
        }
        else if(currentObject instanceof Album)
        {
            nameEdit.setEnabled(false);
            albumEdit.setText(currentObject.getName());
            artistEdit.setText(((Album) currentObject).getArtist().getName());
            trackEdit.setEnabled(false);

            if(((Album) currentObject).hasArt()) imageEdit.setImageBitmap(((Album) currentObject).getArt());
            else imageEdit.setImageResource(R.drawable.ic_albums);
        }
        else if(currentObject instanceof Artist)
        {
            nameEdit.setEnabled(false);
            albumEdit.setEnabled(false);
            artistEdit.setText(currentObject.getName());
            trackEdit.setEnabled(false);
            yearEdit.setEnabled(false);
            imageEdit.setEnabled(false);
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

                String[] splitted = basicFile.getAbsolutePath().split("/");
                int index = -1;
                if(LibraryService.TREE_URI != null)
                {
                    String[] splittedTreeUri = LibraryService.TREE_URI.getPath().split("/");

                    for(int i = 0; i < splitted.length; i++)
                    {
                        if(splittedTreeUri[2].substring(0, splittedTreeUri[2].length()-1).equals(splitted[i]))
                        {
                            index = i;
                            break;
                        }
                    }
                }

                AudioFile currentFile = null;

                if(index != -1)
                {
                    //file is on SD card, lets retrieve path
                    DocumentFile f = DocumentFile.fromTreeUri(this, LibraryService.TREE_URI);
                    for(int i = index+1; i < splitted.length; i++) f = f.findFile(splitted[i]);
                    if(!f.canWrite())
                    {
                        Toast.makeText(this, R.string.give_perm_ext, Toast.LENGTH_LONG).show();
                        return;
                    }

                    //OutputStream os = getContentResolver().openOutputStream(f.getUri());
                    //InputStream is = getContentResolver().openInputStream(f.getUri());
                    Toast.makeText(this, "Edition on SD Card is not supported for now", Toast.LENGTH_LONG).show();
                }
                else
                {
                    //file is on local storage, direct access
                    currentFile = AudioFileIO.read(basicFile);
                }

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
                Song newSong = LibraryService.registerSong(artistEditE ? artistEdit.getText().toString() : currentSong.getArtist().getName(),
                        albumEditE ? albumEdit.getText().toString() : currentSong.getAlbum().getName(),
                        trackEditE ? Integer.parseInt(trackEdit.getText().toString()) : currentSong.getTrackNumber(),
                        yearEditE ? Integer.parseInt(yearEdit.getText().toString()) : currentSong.getYear(),
                        currentSong.getDuration(),
                        nameEditE ? nameEdit.getText().toString() : currentSong.getName(),
                        localOld);
                newSong.setFormat(currentSong.getFormat());
                newSong.setPath(currentSong.getPath());
            }

            MainActivity.selectedObject = null;

            Intent intent = new Intent(TagEditorActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        catch (Exception e)
        {
            Toast.makeText(this, getString(R.string.tag_save_failed) + " : " + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
}
