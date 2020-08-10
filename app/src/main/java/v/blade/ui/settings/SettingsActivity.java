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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.widget.Toast;
import v.blade.R;
import v.blade.library.LibraryService;
import v.blade.ui.MainActivity;

public class SettingsActivity extends AppCompatActivity
{
    public static final String PREFERENCES_ACCOUNT_FILE_NAME = "accounts";
    public static final String PREFERENCES_GENERAL_FILE_NAME = "general";
    private static final int REQUEST_CODE_STORAGE_ACCESS = 1337;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        //set theme
        setTheme(ThemesActivity.currentAppThemeWithActionBar);

        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, new SettingsFragment()).commit();
    }

    @Override
    public final void onActivityResult(final int requestCode, final int resultCode, final Intent resultData)
    {
        if (resultCode == AppCompatActivity.RESULT_OK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
        {
            //if (requestCode == REQUEST_CODE_STORAGE_ACCESS)
            //{
                // Get Uri from Storage Access Framework.
                Uri treeUri = resultData.getData();

                if(!treeUri.toString().endsWith("%3A"))
                {
                    //show the user that we are not happy
                    Toast.makeText(this, R.string.please_sd_root, Toast.LENGTH_LONG).show();
                    return;
                }

                // Persist URI in shared preference so that you can use it later.
                SharedPreferences sharedPreferences = getSharedPreferences(PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("sdcard_uri", treeUri.toString());
                editor.apply();

                LibraryService.TREE_URI = treeUri;

                // Persist access permissions, so you dont have to ask again
                final int takeFlags = resultData.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                grantUriPermission(getPackageName(), treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                Toast.makeText(this, getString(R.string.perm_granted) + " : " + treeUri, Toast.LENGTH_LONG).show();
            //}
        }
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
            else if(preference.getKey().equals("themes"))
            {
                Intent intent = new Intent(getActivity(), ThemesActivity.class);
                startActivity(intent);
            }
            else if(preference.getKey().equals("link_manager"))
            {
                Intent intent = new Intent(getActivity(), LinkManagerActivity.class);
                startActivity(intent);
            }
            else if(preference.getKey().equals("sd_perm"))
            {
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
                {
                    Toast.makeText(getActivity(), R.string.sd_perm_unneeded, Toast.LENGTH_LONG).show();
                    return super.onPreferenceTreeClick(preference);
                }

                //request user to select entire SD card
                //TODO : display image
                Toast.makeText(getActivity(), R.string.select_sd_card, Toast.LENGTH_LONG).show();

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(intent, REQUEST_CODE_STORAGE_ACCESS);
            }
            else if(preference.getKey().equals("save_playlists_to_library"))
            {
                SharedPreferences generalPrefs = getActivity().getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                LibraryService.SAVE_PLAYLISTS_TO_LIBRARY = generalPrefs.getBoolean("save_playlist_to_library", false);
                Toast.makeText(getActivity(), getText(R.string.pls_resync), Toast.LENGTH_SHORT).show();
            }
            else if(preference.getKey().equals("register_better_sources"))
            {
                SharedPreferences generalPrefs = getActivity().getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                LibraryService.REGISTER_SONGS_BETTER_SOURCES = generalPrefs.getBoolean("register_better_sources", true);
                Toast.makeText(getActivity(), getText(R.string.pls_resync), Toast.LENGTH_SHORT).show();
            }
            else if(preference.getKey().equals("anim_0"))
            {
                SharedPreferences generalPrefs = getActivity().getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                LibraryService.ENABLE_SONG_CHANGE_ANIM = generalPrefs.getBoolean("anim_0", true);
            }
            else if(preference.getKey().equals("enable_folder_view"))
            {
                SharedPreferences generalPrefs = getActivity().getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                LibraryService.FOLDER_VIEW_ENABLED = generalPrefs.getBoolean("enable_folder_view", true);

                Intent intent = new Intent(getActivity(), MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }
            else if(preference.getKey().equals("data_saver_mode"))
            {
                SharedPreferences generalPrefs = getActivity().getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                LibraryService.FOLDER_VIEW_ENABLED = generalPrefs.getBoolean("data_saver_mode", false);
            }

            return super.onPreferenceTreeClick(preference);
        }
    }
}
