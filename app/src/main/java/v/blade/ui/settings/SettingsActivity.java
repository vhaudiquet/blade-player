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
package v.blade.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.support.v7.widget.Toolbar;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;
import v.blade.R;
import v.blade.library.UserLibrary;

public class SettingsActivity extends AppCompatActivity
{
    public static final String PREFERENCES_ACCOUNT_FILE_NAME = "accounts";
    public static final String PREFERENCES_GENERAL_FILE_NAME = "general";
    private static int SPOTIFY_REQUEST_CODE = 1337;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, new SettingsFragment()).commit();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent)
    {
        super.onActivityResult(requestCode, resultCode, intent);

        if(requestCode == SPOTIFY_REQUEST_CODE)
        {
            AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, intent);
            if(response.getType() == AuthenticationResponse.Type.TOKEN)
            {
                UserLibrary.SPOTIFY_USER_TOKEN = response.getAccessToken();
                SharedPreferences pref = getSharedPreferences(PREFERENCES_ACCOUNT_FILE_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = pref.edit();
                editor.putString("spotify_token", UserLibrary.SPOTIFY_USER_TOKEN);
                editor.commit();

                new Thread()
                {
                    @Override
                    public void run()
                    {
                        Looper.prepare();
                        UserLibrary.spotifyApi.setAccessToken(UserLibrary.SPOTIFY_USER_TOKEN);
                        UserLibrary.registerSpotifySongs();
                        UserLibrary.sortLibrary();
                    }
                }.start();
            }
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
    {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey)
        {
            getPreferenceManager().setSharedPreferencesName(PREFERENCES_GENERAL_FILE_NAME);

            addPreferencesFromResource(R.xml.preferences);

            if(UserLibrary.SPOTIFY_USER_TOKEN != null)
            {
                Preference sp = getPreferenceScreen().findPreference("spotify_screen");
                sp.setSummary("Connecté");
            }
        }

        @Override
        public boolean onPreferenceTreeClick(Preference preference)
        {
            if(preference.getKey().equals("about_screen"))
            {
                Intent intent = new Intent(getActivity(), AboutActivity.class);
                startActivity(intent);
            }
            else if(preference.getKey().equals("spotify_screen"))
            {
                AuthenticationRequest.Builder builder =
                        new AuthenticationRequest.Builder(UserLibrary.SPOTIFY_CLIENT_ID, AuthenticationResponse.Type.TOKEN,
                                UserLibrary.SPOTIFY_REDIRECT_URI);
                builder.setScopes(new String[]{"user-read-private", "streaming", "user-read-email", "user-follow-read",
                "playlist-read-private", "playlist-read-collaborative", "user-library-read"});
                AuthenticationRequest request = builder.build();
                AuthenticationClient.openLoginActivity(getActivity(), SPOTIFY_REQUEST_CODE, request);
            }

            return super.onPreferenceTreeClick(preference);
        }
    }
}
