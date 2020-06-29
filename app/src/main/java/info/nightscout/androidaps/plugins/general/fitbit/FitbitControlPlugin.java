package info.nightscout.androidaps.plugins.general.fitbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.Calendar;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;

public class FitbitControlPlugin extends PluginBase {

    private static FitbitControlPlugin fitbitControlPlugin;
    public static FitbitControlPlugin getPlugin() {
        return fitbitControlPlugin;
    }

    private final Context ctx;
    private SharedPreferences mPrefs;

    private Thread thread;

    public static FitbitControlPlugin initPlugin(Context ctx) {
        if (fitbitControlPlugin == null) {
            fitbitControlPlugin = new FitbitControlPlugin(ctx);
        }
        return fitbitControlPlugin;
    }

    public FitbitControlPlugin(Context ctx) {
        super(new PluginDescription()
                .mainType(PluginType.GENERAL)
                .pluginName(R.string.fitbitcontrol)
                .shortName(R.string.fitbitcontrol_shortname)
                .neverVisible(true)
//                .preferencesId(R.xml.pref_xdripstatus)
//                .description(R.string.description_xdrip_status_line)
        );
        this.ctx = ctx;
        this.mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (thread == null || thread.getState() == Thread.State.TERMINATED) {
            thread = new Thread(new FitbitWebService(17570, this));
            thread.start();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
    }
}
