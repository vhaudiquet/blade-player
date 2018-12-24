package v.blade;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;
import org.acra.ACRA;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.config.HttpSenderConfigurationBuilder;
import org.acra.config.ToastConfigurationBuilder;
import org.acra.data.StringFormat;
import org.acra.sender.HttpSender;
import v.blade.ui.settings.SettingsActivity;
import v.blade.ui.settings.ThemesActivity;

public class BladeApplication extends Application
{
    @Override
    protected void attachBaseContext(Context base)
    {
        super.attachBaseContext(base);

        //initialize ACRA
        CoreConfigurationBuilder builder = new CoreConfigurationBuilder(this);
        builder.setBuildConfigClass(BuildConfig.class)
                .setReportFormat(StringFormat.JSON);
        builder.getPluginConfigurationBuilder(ToastConfigurationBuilder.class)
                .setResText(R.string.oncrash)
                .setLength(Toast.LENGTH_LONG).setEnabled(true);
        builder.getPluginConfigurationBuilder(HttpSenderConfigurationBuilder.class)
                .setUri("http://valou3433.fr:5984/acra-blade/_design/acra-storage/_update/report")
                .setBasicAuthLogin("REPORTER")
                .setBasicAuthPassword("thereporterpassword")
                .setHttpMethod(HttpSender.Method.PUT)
                .setEnabled(true);
        ACRA.init(this, builder);

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
