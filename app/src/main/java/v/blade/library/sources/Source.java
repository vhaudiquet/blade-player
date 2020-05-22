package v.blade.library.sources;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.LongSparseArray;
import android.widget.Toast;
import v.blade.R;
import v.blade.library.*;
import v.blade.player.PlayerMediaPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public abstract class Source
{
    private final int iconImage;
    private final int logoImage;
    private int priority;
    private boolean available;
    private String name;

    protected Source(int iconImage, int logoImage, String name) {this.iconImage = iconImage; this.logoImage = logoImage; this.name = name;}
    public int getIconImage() {return iconImage;}
    public int getLogoImage() {return logoImage;}
    public int getPriority() {return priority;}
    public void setPriority(int priority) {this.priority = priority;}
    public void setAvailable(boolean available) {this.available = available;}
    public boolean isAvailable() {return this.available;}
    public String getName() {return name;}
    @Override public String toString() {return name;}

    //source settings/init/player...
    public abstract void initConfig(SharedPreferences accountsPrefs);
    public abstract void disconnect();
    public abstract String getUserName();
    public abstract SourcePlayer getPlayer();

    //source songs registering
    public abstract void registerCachedSongs();
    public abstract void loadCachedArts();
    public abstract void registerSongs();

    //source querying
    public abstract List<LibraryObject> query(String query);
    public abstract boolean searchForSong(Song song);

    /* operations */
    public interface OperationCallback {void onSucess(LibraryObject result); void onFailure();}

    //playlist management operations
    public abstract void addSongsToPlaylist(List<Song> songs, v.blade.library.Playlist list, OperationCallback callback);
    public abstract void removeSongFromPlaylist(Song song, v.blade.library.Playlist list, OperationCallback callback);
    public abstract void addPlaylist(String name, OperationCallback callback, boolean isPublic, boolean isCollaborative);
    public abstract void removePlaylist(v.blade.library.Playlist playlist, OperationCallback callback);

    //library management operations
    public abstract void addSongToLibrary(Song song, OperationCallback callback);
    public abstract void removeSongFromLibrary(Song song, OperationCallback callback);
    public abstract void addAlbumToLibrary(v.blade.library.Album album, OperationCallback callback);
    public abstract void removeAlbumFromLibrary(v.blade.library.Album album, OperationCallback callback);

    public static Source SOURCE_LOCAL_LIB = new Source(R.drawable.ic_local, 0, "Local")
    {
        private LongSparseArray<v.blade.library.Album> idsorted_albums = null;
        private ArrayList<v.blade.library.Playlist> local_playlists = null;

        private SourcePlayer player = new SourcePlayer()
        {
            MediaPlayer mediaPlayer;
            int duration = 1000; //getDuration may throw if unprepared...

            @Override
            public void init()
            {
                mediaPlayer = new MediaPlayer();
                setListener(PlayerMediaPlayer.playerListener);
            }

            @Override
            public void setListener(PlayerListener listener)
            {
                if(mediaPlayer == null) return;

                mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener()
                {
                    @Override
                    public void onCompletion(MediaPlayer mp)
                    {
                        listener.onSongCompletion();
                    }
                });
            }

            @Override
            public void play(PlayerCallback callback)
            {
                if(mediaPlayer == null) {if(callback != null) callback.onFailure(player); return;}

                mediaPlayer.start();
                if(callback != null) callback.onSucess(player);
            }

            @Override
            public void pause(PlayerCallback callback)
            {
                if(mediaPlayer == null) {if(callback != null) callback.onFailure(player); return;}

                mediaPlayer.pause();
                if(callback != null) callback.onSucess(player);
            }

            @Override
            public void playSong(Song song, PlayerCallback callback)
            {
                SongSources.SongSource local = song.getSources().getLocal();
                if(local == null) {if(callback != null) callback.onFailure(player); return;}
                if(mediaPlayer == null) {if(callback != null) callback.onFailure(player); return;}

                if(song.getFormat() != null && song.getFormat().equals("audio/x-ms-wma"))
                {
                    Toast.makeText(LibraryService.appContext, LibraryService.appContext.getString(R.string.format_unsupported), Toast.LENGTH_SHORT).show();
                    callback.onFailure(player);
                    return;
                }
                Uri songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, (long) song.getSources().getLocal().getId());

                try
                {
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(LibraryService.appContext, songUri);
                    mediaPlayer.prepareAsync();
                    mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener()
                    {
                        @Override
                        public void onPrepared(MediaPlayer mp) {duration = mediaPlayer.getDuration(); play(callback);}
                    });
                }
                catch(Exception e) {if(callback != null) callback.onFailure(player);}
            }

            @Override
            public void seekTo(int msec)
            {
                mediaPlayer.seekTo(msec);
            }

            @Override
            public int getCurrentPosition()
            {
                return mediaPlayer.getCurrentPosition();
            }

            @Override
            public int getDuration()
            {
                return duration;
            }
        };

        @Override
        public String getUserName() {return "";}
        @Override
        public SourcePlayer getPlayer() {return player;}
        @Override
        public List<LibraryObject> query(String query) {return new ArrayList<>();}

        @Override
        public void initConfig(SharedPreferences accountsPrefs)
        {
            setPriority(999);
            player.init();
        }

        @Override
        public void registerCachedSongs()
        {
            registerSongs();
        }

        @Override
        public void registerSongs()
        {
            if(!LibraryService.configured) return;

            System.out.println("[BLADE-LOCAL] Registering songs...");

            //empty lists
            LibraryService.getArtists().clear();
            LibraryService.getAlbums().clear();
            LibraryService.getSongs().clear();
            LibraryService.getPlaylists().clear();
            LibraryService.songsByName.clear();

            /* get content resolver and init temp sorted arrays */
            final ContentResolver musicResolver = LibraryService.appContext.getContentResolver();
            boolean loadAlbumArts = true;
            if(idsorted_albums != null) //load of album art is not finished but resync was called
                loadAlbumArts = false;

            if(loadAlbumArts) idsorted_albums = new LongSparseArray<>();
            LongSparseArray<Song> idsorted_songs = new LongSparseArray<>();

            /* let's get all music files of the user, and register them and their attributes */
            android.database.Cursor musicCursor = musicResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, null, null);
            if(musicCursor!=null && musicCursor.moveToFirst())
            {
                //get columns
                int titleColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int idColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media._ID);
                int artistColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int artistIdColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID);
                int albumColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int albumIdColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                int albumTrackColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.TRACK);
                int yearColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.YEAR);
                int songDurationColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                int formatColumn = musicCursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
                int fileColumn = musicCursor.getColumnIndex(MediaStore.Audio.Media.DATA);

                //add songs to list
                do
                {
                    long thisId = musicCursor.getLong(idColumn);
                    long artistId = musicCursor.getLong(artistIdColumn);
                    long albumId = musicCursor.getLong(albumIdColumn);
                    int albumTrack = musicCursor.getInt(albumTrackColumn);
                    String thisTitle = musicCursor.getString(titleColumn);
                    String thisArtist = musicCursor.getString(artistColumn);
                    String thisAlbum = musicCursor.getString(albumColumn);
                    long thisDuration = musicCursor.getLong(songDurationColumn);
                    int year = musicCursor.getInt(yearColumn);
                    String thisPath = musicCursor.getString(fileColumn);

                    //resolve null artist name
                    if(thisArtist == null || thisArtist.equals("<unknown>"))
                        thisArtist = LibraryService.appContext.getString(R.string.unknown_artist);

                    //resolve null album name (should not happen)
                    if(thisAlbum == null || thisAlbum.equals("<unknown>"))
                        thisAlbum = LibraryService.appContext.getString(R.string.unknown_album);

                    //set to empty string to avoid crashes (NullPointer), should definitely not happen but who knows
                    if(thisTitle == null) thisTitle = "";

                    Song s = LibraryService.registerSong(thisArtist, thisAlbum, albumTrack, year, thisDuration, thisTitle, new SongSources.SongSource(thisId, SOURCE_LOCAL_LIB));
                    s.setFormat(musicCursor.getString(formatColumn));
                    s.setPath(thisPath);
                    idsorted_songs.put(thisId, s);
                    if(loadAlbumArts)
                        if(idsorted_albums.get(albumId) == null) idsorted_albums.put(albumId, s.getAlbum());
                }
                while (musicCursor.moveToNext());
                musicCursor.close();
            }

            /* we also need to get playlists on device */
            local_playlists = new ArrayList<>();
            android.database.Cursor playlistCursor = musicResolver.query(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, null, null, null, null);
            if(playlistCursor!=null && playlistCursor.moveToFirst())
            {
                int idColumn = playlistCursor.getColumnIndex(MediaStore.Audio.Playlists._ID);
                int nameColumn = playlistCursor.getColumnIndex(MediaStore.Audio.Playlists.NAME);
                int fileColumn = playlistCursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);

                do
                {
                    long thisId = playlistCursor.getLong(idColumn);
                    String thisName = playlistCursor.getString(nameColumn);
                    String thisPath = "";
                    if(fileColumn != -1)
                        thisPath = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI + "/" + playlistCursor.getString(fileColumn);

                    //now we have to resolve the content of this playlist
                    ArrayList<Song> thisList = new ArrayList<>();
                    android.database.Cursor thisPlaylistCursor = musicResolver.query(MediaStore.Audio.Playlists.Members.getContentUri("external", thisId), null, null, null, null);
                    if(thisPlaylistCursor!=null && thisPlaylistCursor.moveToFirst())
                    {
                        int audioIdColumn = thisPlaylistCursor.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID);

                        do
                        {
                            thisList.add(idsorted_songs.get(thisPlaylistCursor.getLong(audioIdColumn)));
                        } while(thisPlaylistCursor.moveToNext());
                        thisPlaylistCursor.close();
                    }

                    v.blade.library.Playlist list = new v.blade.library.Playlist(thisName, thisList);
                    list.getSources().addSource(new SongSources.SongSource(thisId, SOURCE_LOCAL_LIB));
                    list.setPath(thisPath);
                    LibraryService.getPlaylists().add(list);
                    local_playlists.add(list);
                    if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                } while(playlistCursor.moveToNext());
                playlistCursor.close();
            }

            System.out.println("[BLADE-LOCAL] Songs registered");
        }

        @Override
        public void loadCachedArts()
        {
            if(idsorted_albums == null) return;
            LongSparseArray<v.blade.library.Album> thisArray = idsorted_albums.clone(); //avoid sync problems

            Cursor albumCursor = LibraryService.appContext.getContentResolver().
                    query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, null, null, null, null, null);
            if(albumCursor!=null && albumCursor.moveToFirst())
            {
                int idCol = albumCursor.getColumnIndex(MediaStore.Audio.Albums._ID);
                int artCol = albumCursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART);

                do
                {
                    long thisId = albumCursor.getLong(idCol);
                    String path = albumCursor.getString(artCol);

                    v.blade.library.Album a = thisArray.get(thisId);
                    if(a != null)
                    {
                        LibraryService.loadArt(a, path, true);
                    }
                } while (albumCursor.moveToNext());
                albumCursor.close();
            }

            thisArray = null;

            //generate image for the playlist, after we are sure that all albumarts image are loaded
            if(local_playlists == null) return;
            for(v.blade.library.Playlist list : local_playlists)
            {
                Bitmap[] bitmaps = new Bitmap[4];
                int imagenumber = 0;
                for(Song s : list.getContent())
                    if(s.getAlbum().hasArt() && s.getAlbum().getArtMiniature() != bitmaps[0] && s.getAlbum().getArtMiniature() != bitmaps[1] && s.getAlbum().getArtMiniature() != bitmaps[2])
                    {
                        bitmaps[imagenumber] = s.getAlbum().getArtMiniature();
                        imagenumber++;
                        if(imagenumber == 4) break;
                    }

                if(imagenumber == 4)
                {
                    //generate 1 image from the 4
                    Bitmap finalBitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(finalBitmap);
                    canvas.drawBitmap(bitmaps[0], new Rect(0, 0, bitmaps[0].getWidth(), bitmaps[0].getHeight()),
                            new Rect(0, 0, 40, 40), null);
                    canvas.drawBitmap(bitmaps[1], new Rect(0, 0, bitmaps[1].getWidth(), bitmaps[1].getHeight()),
                            new Rect(40, 0, 80, 40), null);
                    canvas.drawBitmap(bitmaps[2], new Rect(0, 0, bitmaps[2].getWidth(), bitmaps[2].getHeight()),
                            new Rect(0, 40, 40, 80), null);
                    canvas.drawBitmap(bitmaps[3], new Rect(0, 0, bitmaps[3].getWidth(), bitmaps[3].getHeight()),
                            new Rect(40, 40, 80, 80), null);

                    list.setArt("", finalBitmap);
                }
                else if(imagenumber == 1)
                {
                    list.setArt("", bitmaps[0]);
                }
            }
        }

        @Override
        public void addSongsToPlaylist(List<Song> songs, v.blade.library.Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_LOCAL_LIB) {callback.onFailure(); return;}

            int count = list.getContent().size();
            ContentValues[] values = new ContentValues[songs.size()];
            for (int i = 0; i < songs.size(); i++)
            {
                SongSources.SongSource local = songs.get(i).getSources().getLocal();
                if(local == null) {callback.onFailure(); return;}

                values[i] = new ContentValues();
                values[i].put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, i + count + 1);
                values[i].put(MediaStore.Audio.Playlists.Members.AUDIO_ID, (long) songs.get(i).getSources().getLocal().getId());
            }
            Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", (long) list.getSources().getLocal().getId());
            ContentResolver resolver = LibraryService.appContext.getContentResolver();
            int num = resolver.bulkInsert(uri, values);
            resolver.notifyChange(Uri.parse("content://media"), null);

            list.getContent().addAll(songs);

            callback.onSucess(null);
        }
        @Override
        public void removeSongFromPlaylist(Song song, v.blade.library.Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_LOCAL_LIB) {callback.onFailure(); return;}

            ContentResolver resolver = LibraryService.appContext.getContentResolver();
            try
            {
                Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", (long) list.getSources().getLocal().getId());
                int countDel = resolver.delete(uri, MediaStore.Audio.Playlists.Members.AUDIO_ID + " = ? ",
                        new String[]{Long.toString((long) song.getSources().getLocal().getId())});
                if(countDel >= 1)
                {
                    callback.onSucess(null);
                    list.getContent().remove(song);
                    if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();
                }
                else callback.onFailure();
            }
            catch (Exception e)
            {
                callback.onFailure();
                e.printStackTrace();
            }
        }

        @Override
        public boolean searchForSong(Song song) {return false;}
        @Override
        public void disconnect() {}

        @Override
        public void addPlaylist(String name, OperationCallback callback, boolean isPublic, boolean isCollaborative)
        {
            ContentResolver contentResolver = LibraryService.appContext.getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.Audio.Playlists.NAME, name);
            contentValues.put(MediaStore.Audio.Playlists.DATE_ADDED, System.currentTimeMillis());
            contentValues.put(MediaStore.Audio.Playlists.DATE_MODIFIED, System.currentTimeMillis());
            Uri uri = contentResolver.insert(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, contentValues);

            if(uri != null)
            {
                long mPlaylistId = Long.parseLong(uri.getLastPathSegment());

                //add playlist in RAM
                v.blade.library.Playlist list = new v.blade.library.Playlist(name, new ArrayList<>());
                list.getSources().addSource(new SongSources.SongSource(mPlaylistId, SOURCE_LOCAL_LIB));
                LibraryService.getPlaylists().add(list);
                if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                callback.onSucess(list);
            }
            else callback.onFailure();
        }
        @Override
        public void removePlaylist(Playlist list, OperationCallback callback)
        {
            if(list.getSources().getSourceByPriority(0).getSource() != SOURCE_LOCAL_LIB) {callback.onFailure(); return;}

            if(list.getPath() != null && !list.getPath().equals("")) new File(list.getPath()).delete();

            ContentResolver contentResolver = LibraryService.appContext.getContentResolver();
            String where = MediaStore.Audio.Playlists._ID + "=?";
            String[] whereVal = {Long.toString((long) list.getSources().getLocal().getId())};
            int deleteCount = contentResolver.delete(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, where, whereVal);
            if(deleteCount >= 1)
            {
                //remove playlist from ram
                LibraryService.getPlaylists().remove(list);
                if(LibraryService.currentCallback != null) LibraryService.currentCallback.onLibraryChange();

                callback.onSucess(null);
            }
            else callback.onFailure();
        }

        public void addSongToLibrary(Song song, OperationCallback callback)
        {callback.onFailure();}
        public void removeSongFromLibrary(Song song, OperationCallback callback)
        {
            SongSources.SongSource local = song.getSources().getLocal();
            if(local == null) {if(callback != null) callback.onFailure(); return;}

            new File(song.getPath()).delete();

            ContentResolver resolver = LibraryService.appContext.getContentResolver();
            int count = resolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Audio.Media._ID + " = " + local.getId(),
                    null);

            if(count >= 1)
            {
                //unregister song from library
                LibraryService.unregisterSong(song, local);

                if(callback != null) callback.onSucess(null);
            }
            else if(callback != null) callback.onFailure();
        }
        public void addAlbumToLibrary(v.blade.library.Album album, OperationCallback callback)
        {callback.onFailure();}
        public void removeAlbumFromLibrary(Album album, OperationCallback callback)
        {
            //TODO : handle errors
            for(Song s : album.getSongs())
            {
                removeSongFromLibrary(s, null);
            }

            callback.onSucess(null);

            //TODO : should i delete album entry from content resolver ? (++artist entry if last album)
        }
    };

    public static v.blade.library.sources.Spotify SOURCE_SPOTIFY = new v.blade.library.sources.Spotify();
    public static Deezer SOURCE_DEEZER = new Deezer();
    public static Source SOURCES[] = new Source[]{SOURCE_LOCAL_LIB, SOURCE_SPOTIFY, SOURCE_DEEZER};
}
