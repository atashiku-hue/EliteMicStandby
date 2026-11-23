package tw.vic.elitemic;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

final class Prefs {
    static final String[] REQUIRED = {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_PHONE_STATE};
    static SharedPreferences of(Context c) { return c.getSharedPreferences("standby", Context.MODE_PRIVATE); }
    static boolean permitted(Context c) {
        for (String p : REQUIRED)
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    private Prefs() {}
}
