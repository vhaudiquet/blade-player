package v.blade.library;

public abstract class SourcePlayer
{
    public interface PlayerCallback {void onSucess(SourcePlayer currentPlayer); void onFailure(SourcePlayer currentPlayer);}
    public interface PlayerListener {void onSongCompletion(); void onPlaybackError(SourcePlayer player, String errMsg);}

    public abstract void init();
    public abstract void setListener(PlayerListener listener);
    public abstract void play(PlayerCallback callback);
    public abstract void pause(PlayerCallback callback);
    public abstract void playSong(Song song, PlayerCallback callback);
    //public abstract void stop(PlayerCallback callback);
    public abstract void seekTo(int msec);
    public abstract int getCurrentPosition();

    public int getDuration() {return 0;}
}
