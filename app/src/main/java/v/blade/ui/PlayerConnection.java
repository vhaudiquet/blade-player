/*
 *    Blade - Android music player
 *    Copyright (C) 2018 Valentin HAUDIQUET
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package v.blade.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.v4.media.session.MediaControllerCompat;
import v.blade.player.PlayerService;

import java.util.ArrayList;

import static java.lang.System.exit;

public class PlayerConnection
{
    private static Context applicationContext;

    /* connection to player and callbacks */
    public static PlayerService musicPlayer;
    public static MediaControllerCompat musicController;
    private static ServiceConnection musicConnection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service)
        {
            musicPlayer = ((PlayerService.PlayerBinder) service).getService();

            try{musicController = new MediaControllerCompat(applicationContext, musicPlayer.getSessionToken());}
            catch(Exception e) { exit(1); }

            for(Callback c : callbacks) c.onConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name)
        {
            musicPlayer = null;
            for(Callback c : callbacks) c.onDisconnected();
        }
    };

    public static void initConnection(Context context)
    {
        if(musicPlayer == null)
        {
            applicationContext = context.getApplicationContext();
            Intent intent = new Intent(applicationContext, PlayerService.class);
            applicationContext.bindService(intent, musicConnection, Context.BIND_AUTO_CREATE);
            applicationContext.startService(intent);
        }
    }

    /* connection callbacks */
    private static ArrayList<Callback> callbacks = new ArrayList<Callback>();
    public interface Callback {public void onConnected(); public void onDisconnected();}
    public static void registerCallback(Callback callback) {callbacks.add(callback);}
    public static void unregisterCallback(Callback callback) {callbacks.remove(callback);}
}
