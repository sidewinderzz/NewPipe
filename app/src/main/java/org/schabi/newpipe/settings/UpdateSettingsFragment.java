package org.schabi.newpipe.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.R;
import org.schabi.newpipe.subspipe.SubsPipeUpdater;

public class UpdateSettingsFragment extends BasePreferenceFragment {
    private final Preference.OnPreferenceClickListener manualUpdateClick = preference -> {
        Toast.makeText(getContext(), R.string.checking_updates_toast, Toast.LENGTH_SHORT).show();
        // SubsPipe: check sidewinderzz/NewPipe releases instead of NewPipe's
        SubsPipeUpdater.INSTANCE.checkInBackground(requireActivity(), true);
        return true;
    };

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        // SubsPipe: NewPipe's own update checker doesn't apply; SubsPipe checks on every launch
        getPreferenceScreen().removePreference(requirePreference(R.string.update_app_key));
        requirePreference(R.string.manual_update_key)
                .setSummary(getString(R.string.subspipe_manual_update_summary,
                        BuildConfig.VERSION_NAME));
        requirePreference(R.string.manual_update_key)
                .setOnPreferenceClickListener(manualUpdateClick);
    }

    public static void askForConsentToUpdateChecks(final Context context) {
        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.check_for_updates))
                .setMessage(context.getString(R.string.auto_update_check_description))
                .setPositiveButton(context.getString(R.string.yes), (d, w) -> {
                    d.dismiss();
                    setAutoUpdateCheckEnabled(context, true);
                })
                .setNegativeButton(R.string.no, (d, w) -> {
                    d.dismiss();
                    // set explicitly to false, since the default is true on previous versions
                    setAutoUpdateCheckEnabled(context, false);
                })
                .show();
    }

    private static void setAutoUpdateCheckEnabled(final Context context, final boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(context.getString(R.string.update_app_key), enabled)
                .putBoolean(context.getString(R.string.update_check_consent_key), true)
                .apply();
    }

    /**
     * Whether the user was asked for consent to automatically check for app updates.
     * @param context
     * @return true if the user was asked for consent, false otherwise
     */
    public static boolean wasUserAskedForConsent(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.update_check_consent_key), false);
    }
}
