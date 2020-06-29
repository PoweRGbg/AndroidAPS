package info.nightscout.androidaps.utils;

import android.app.backup.BackupAgentHelper;
import android.app.backup.BackupDataInput;
import android.app.backup.SharedPreferencesBackupHelper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

import info.nightscout.androidaps.MainApp;

public class SPBackupAgent extends BackupAgentHelper {

    // API 24
    //static final String PREFS = PreferenceManager.getDefaultSharedPreferencesName(MainApp.instance().getApplicationContext());
    static final String PREFS = MainApp.instance().getApplicationContext().getPackageName() + "_preferences";

    static final String PREFS_BACKUP_KEY = "SP";

    @Override
    public void onCreate() {

        SharedPreferencesBackupHelper helper =
                new SharedPreferencesBackupHelper(this, PREFS);
        addHelper(PREFS_BACKUP_KEY, helper);
    }

    @Override
    public void onQuotaExceeded (long backupDataBytes,
                                 long quotaBytes) {
        Log.d( "SPBackupAgent", "onQuotaExceeded" );
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        Log.d("SPBackupAgent", "onRestore");
        super.onRestore(data, appVersionCode, newState);
    }
}