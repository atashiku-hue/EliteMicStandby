package tw.vic.elitemic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent event) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(event.getAction()) &&
                !Intent.ACTION_MY_PACKAGE_REPLACED.equals(event.getAction())) return;
        if (Prefs.of(c).getBoolean("enabled",false) && Prefs.permitted(c)) {
            try { c.startForegroundService(new Intent(c, StandbyService.class)); }
            catch (IllegalStateException | SecurityException e) {
                Prefs.of(c).edit().putString("status","系統未允許自動恢復；請開啟本 App 恢復待命。").apply();
            }
        }
    }
}
