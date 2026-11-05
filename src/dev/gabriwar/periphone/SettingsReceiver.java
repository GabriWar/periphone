package dev.gabriwar.periphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Settings (or a stop) from periphone on the PC while the app is in the background:
 *   adb shell am broadcast -n dev.gabriwar.periphone/.SettingsReceiver --ez mic false ...
 * Unlike the Autostart alias, nothing comes to the front. Protected by the DUMP permission in the
 * manifest: the adb shell has it, other apps can't get it.
 */
public class SettingsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        if (i.getBooleanExtra("stopbridge", false)) {
            if (Stats.running) send(ctx, BridgeService.ACTION_STOP);
            return;
        }
        SharedPreferences.Editor e = Cfg.prefs(ctx).edit();
        Cfg.applyExtras(e, i);
        e.apply();
        Stats.pcAddr = null;
        Stats.prefsPushed++;                         // the screen re-reads the settings
        if (Stats.running) send(ctx, BridgeService.ACTION_START);   // restarts only what changed
    }

    private static void send(Context ctx, String action) {
        try {
            ctx.startService(new Intent(ctx, BridgeService.class).setAction(action));
        } catch (IllegalStateException ignored) {
            // the service isn't in the foreground anymore: nothing running to update
        }
    }
}
