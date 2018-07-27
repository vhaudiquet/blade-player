package v.blade.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import v.blade.R;
import v.blade.ui.MainActivity;

public class ThemesActivity extends AppCompatActivity
{
    public static int currentColorPrimary = R.color.bladeColorPrimary;
    public static int currentColorPrimaryDark = R.color.bladeColorPrimaryDark;
    public static int currentColorPrimaryLight = R.color.bladeColorPrimaryLight;
    public static int currentColorAccent = R.color.bladeColorAccent;
    public static int currentColorPrimaryLighter = R.color.bladeColorPrimaryLighter;
    public static int currentColorBackground = R.color.bladeColorBackground;
    public static int currentAppTheme = R.style.Blade_AppTheme_NoActionBar;
    public static int currentAppThemeWithActionBar = R.style.Blade_AppTheme;
    public static int currentControlTheme = R.style.Theme_ControlTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        //set theme
        setTheme(currentAppTheme);

        setContentView(R.layout.activity_themes);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        //fill theme list
        ListView themeList = findViewById(R.id.themes_list);
        themeList.setAdapter(new BaseAdapter()
        {
            class ViewHolder
            {
                ImageView img;
                TextView title;
                TextView desc;
                ImageView more;
            }
            @Override
            public int getCount() {return 3;}
            @Override
            public Object getItem(int position) {return null;}
            @Override
            public long getItemId(int position) {return 0;}

            @Override
            public View getView(int position, View convertView, ViewGroup parent)
            {
                ViewHolder mViewHolder;

                if(convertView == null)
                {
                    mViewHolder = new ViewHolder();

                    //map to layout
                    convertView = LayoutInflater.from(ThemesActivity.this).inflate(R.layout.list_layout, parent, false);

                    //get imageview
                    mViewHolder.img = convertView.findViewById(R.id.element_image);
                    mViewHolder.title = convertView.findViewById(R.id.element_title);
                    mViewHolder.desc = convertView.findViewById(R.id.element_subtitle);
                    mViewHolder.more = convertView.findViewById(R.id.element_more);

                    convertView.setTag(mViewHolder);
                }
                else mViewHolder = (ViewHolder) convertView.getTag();

                switch(position)
                {
                    case 1:
                    {
                        //nightly theme
                        mViewHolder.img.setImageResource(0);
                        mViewHolder.img.setBackgroundColor(ContextCompat.getColor(ThemesActivity.this, R.color.nightlyColorPrimary));
                        mViewHolder.title.setText("Nightly");
                        mViewHolder.desc.setText(R.string.theme_nightly_desc);
                        break;
                    }
                    case 0:
                    {
                        //blade theme
                        mViewHolder.img.setImageResource(0);
                        mViewHolder.img.setBackgroundColor(ContextCompat.getColor(ThemesActivity.this, R.color.bladeColorPrimary));
                        mViewHolder.title.setText("Blade");
                        mViewHolder.desc.setText(getText(R.string.theme_blade_desc));
                        break;
                    }
                    case 2:
                    {
                        //green theme
                        mViewHolder.img.setImageResource(0);
                        mViewHolder.img.setBackgroundColor(ContextCompat.getColor(ThemesActivity.this, R.color.greenColorPrimary));
                        mViewHolder.title.setText("Green");
                        mViewHolder.desc.setText(getText(R.string.theme_green_desc));
                        break;
                    }
                }

                mViewHolder.more.setImageResource(0);

                return convertView;
            }
        });
        themeList.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id)
            {
                switch(position)
                {
                    case 1:
                    {
                        //nightly theme
                        if(currentColorPrimary == R.color.nightlyColorPrimary) return;
                        setThemeToNightly();

                        SharedPreferences pref = getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = pref.edit();
                        editor.putString("theme", "nightly");
                        editor.apply();

                        break;
                    }
                    case 0:
                    {
                        //blade theme
                        if(currentColorPrimary == R.color.bladeColorPrimary) return;
                        setThemeToBlade();

                        SharedPreferences pref = getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = pref.edit();
                        editor.putString("theme", "blade");
                        editor.apply();

                        break;
                    }
                    case 2:
                    {
                        //blade green
                        if(currentColorPrimary == R.color.greenColorPrimary) return;
                        setThemeToGreen();

                        SharedPreferences pref = getSharedPreferences(SettingsActivity.PREFERENCES_GENERAL_FILE_NAME, Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = pref.edit();
                        editor.putString("theme", "green");
                        editor.apply();

                        break;
                    }
                }

                Intent intent = new Intent(ThemesActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }
        });

        //set theme
        findViewById(R.id.themes_layout).setBackgroundColor(ContextCompat.getColor(this, currentColorBackground));
    }

    public static void setThemeToNightly()
    {
        currentColorPrimary = R.color.nightlyColorPrimary;
        currentColorPrimaryDark = R.color.nightlyColorPrimaryDark;
        currentColorPrimaryLight = R.color.nightlyColorPrimaryLight;
        currentColorAccent = R.color.nightlyColorAccent;
        currentColorPrimaryLighter = R.color.nightlyColorPrimaryLighter;
        currentColorBackground = R.color.nightlyColorBackground;
        currentAppTheme = R.style.Nightly_AppTheme_NoActionBar;
        currentAppThemeWithActionBar = R.style.Nightly_AppTheme;
        currentControlTheme = R.style.Theme_ControlTheme;
    }
    public static void setThemeToBlade()
    {
        currentColorPrimary = R.color.bladeColorPrimary;
        currentColorPrimaryDark = R.color.bladeColorPrimaryDark;
        currentColorPrimaryLight = R.color.bladeColorPrimaryLight;
        currentColorAccent = R.color.bladeColorAccent;
        currentColorPrimaryLighter = R.color.bladeColorPrimaryLighter;
        currentColorBackground = R.color.bladeColorBackground;
        currentAppTheme = R.style.Blade_AppTheme_NoActionBar;
        currentAppThemeWithActionBar = R.style.Blade_AppTheme;
        currentControlTheme = R.style.Theme_ControlTheme;
    }
    public static void setThemeToGreen()
    {
        currentColorPrimary = R.color.greenColorPrimary;
        currentColorPrimaryDark = R.color.greenColorPrimaryDark;
        currentColorPrimaryLight = R.color.greenColorPrimaryLight;
        currentColorAccent = R.color.greenColorAccent;
        currentColorPrimaryLighter = R.color.greenColorPrimaryLighter;
        currentColorBackground = R.color.greenColorBackground;
        currentAppTheme = R.style.Green_AppTheme_NoActionBar;
        currentAppThemeWithActionBar = R.style.Green_AppTheme;
        currentControlTheme = R.style.Theme_ControlTheme;
    }
}
