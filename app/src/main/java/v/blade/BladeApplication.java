package v.blade;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import v.blade.ui.settings.SettingsActivity;
import v.blade.ui.settings.ThemesActivity;

public class BladeApplication extends Application
{
    @Override
    protected void attachBaseContext(Context base)
    {
        super.attachBaseContext(base);

        //load saved theme
        SharedPreferences generalPrefs = base.getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
        String theme = generalPrefs.getString("theme", null);
        if(theme != null)
        {
            if(theme.equalsIgnoreCase("nightly"))
                ThemesActivity.setThemeToNightly();
            else if(theme.equalsIgnoreCase("blade"))
                ThemesActivity.setThemeToBlade();
            else if(theme.equalsIgnoreCase("green"))
                ThemesActivity.setThemeToGreen();
            else if(theme.equalsIgnoreCase("red"))
                ThemesActivity.setThemeToRed();
            else if(theme.equalsIgnoreCase("dark"))
                ThemesActivity.setThemeToDark();
        }
    }
}
