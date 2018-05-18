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
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.support.v7.widget.Toolbar;
import v.blade.R;
import v.blade.library.LibraryService;

public class SettingsActivity extends AppCompatActivity
{
    public static final String PREFERENCES_ACCOUNT_FILE_NAME = "accounts";
    public static final String PREFERENCES_GENERAL_FILE_NAME = "general";

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, new SettingsFragment()).commit();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
    {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey)
        {
            getPreferenceManager().setSharedPreferencesName(PREFERENCES_GENERAL_FILE_NAME);

            addPreferencesFromResource(R.xml.preferences);
        }

        @Override
        public boolean onPreferenceTreeClick(Preference preference)
        {
            if(preference.getKey().equals("about_screen"))
            {
                Intent intent = new Intent(getActivity(), AboutActivity.class);
                startActivity(intent);
            }
            else if(preference.getKey().equals("sources_screen"))
            {
                Intent intent = new Intent(getActivity(), SourcesActivity.class);
                startActivity(intent);
            }
            else if(preference.getKey().equals("save_playlists_to_library"))
            {
                SharedPreferences generalPrefs = getActivity().getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                LibraryService.SAVE_PLAYLISTS_TO_LIBRARY = generalPrefs.getBoolean("save_playlist_to_library", false);
            }
            else if(preference.getKey().equals("register_better_sources"))
            {
                SharedPreferences generalPrefs = getActivity().getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                LibraryService.REGISTER_SONGS_BETTER_SOURCES = generalPrefs.getBoolean("register_better_sources", true);
            }

            return super.onPreferenceTreeClick(preference);
        }
    }
}
