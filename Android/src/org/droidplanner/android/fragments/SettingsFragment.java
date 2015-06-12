package org.droidplanner.android.fragments;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.support.annotation.StringRes;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.HitBuilders;
import com.o3dr.android.client.Drone;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeEventExtra;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.connection.ConnectionType;
import com.o3dr.services.android.lib.drone.property.State;
import com.o3dr.services.android.lib.drone.property.Type;

import org.beyene.sius.unit.length.LengthUnit;
import org.droidplanner.android.DroidPlannerApp;
import org.droidplanner.android.R;
import org.droidplanner.android.activities.helpers.MapPreferencesActivity;
import org.droidplanner.android.dialogs.ClearBTDialogPreference;
import org.droidplanner.android.maps.providers.DPMapProvider;
import org.droidplanner.android.utils.Utils;
import org.droidplanner.android.utils.analytics.GAUtils;
import org.droidplanner.android.utils.file.DirectoryPath;
import org.droidplanner.android.utils.prefs.DroidPlannerPrefs;
import org.droidplanner.android.utils.unit.UnitManager;
import org.droidplanner.android.utils.unit.providers.length.LengthUnitProvider;
import org.droidplanner.android.utils.unit.systems.UnitSystem;

import java.util.HashSet;
import java.util.Locale;

/**
 * Implements the application settings screen.
 */
public class SettingsFragment extends PreferenceFragment implements OnSharedPreferenceChangeListener,
        DroidPlannerApp.ApiListener {

    /**
     * Used as tag for logging.
     */
    private final static String TAG = SettingsFragment.class.getSimpleName();

    private static final String PACKAGE_NAME = Utils.PACKAGE_NAME;

    /**
     * Action used to broadcast updates to the period for the spoken status
     * summary.
     */
    public static final String ACTION_UPDATED_STATUS_PERIOD = PACKAGE_NAME + ".ACTION_UPDATED_STATUS_PERIOD";

    /**
     * Action used to broadcast updates to the gps hdop display preference.
     */
    public static final String ACTION_PREF_HDOP_UPDATE = PACKAGE_NAME + ".ACTION_PREF_HDOP_UPDATE";

    /**
     * Action used to broadcast updates to the unit system.
     */
    public static final String ACTION_PREF_UNIT_SYSTEM_UPDATE = PACKAGE_NAME + ".ACTION_PREF_UNIT_SYSTEM_UPDATE";

    /**
     * Used to retrieve the new period for the spoken status summary.
     */
    public static final String EXTRA_UPDATED_STATUS_PERIOD = "extra_updated_status_period";

    public static final String ACTION_LOCATION_SETTINGS_UPDATED = PACKAGE_NAME + ".action.LOCATION_SETTINGS_UPDATED";
    public static final String EXTRA_RESULT_CODE = "extra_result_code";

    public static final String ACTION_ADVANCED_MENU_UPDATED = PACKAGE_NAME + ".action.ADVANCED_MENU_UPDATED";

    /**
     * Used to notify of an update to the map rotation preference.
     */
    public static final String ACTION_MAP_ROTATION_PREFERENCE_UPDATED = PACKAGE_NAME +
            ".ACTION_MAP_ROTATION_PREFERENCE_UPDATED";

    private static final IntentFilter intentFilter = new IntentFilter();

    static {
        intentFilter.addAction(AttributeEvent.STATE_DISCONNECTED);
        intentFilter.addAction(AttributeEvent.STATE_CONNECTED);
        intentFilter.addAction(AttributeEvent.STATE_UPDATED);
        intentFilter.addAction(AttributeEvent.HEARTBEAT_FIRST);
        intentFilter.addAction(AttributeEvent.HEARTBEAT_RESTORED);
        intentFilter.addAction(AttributeEvent.TYPE_UPDATED);
        intentFilter.addAction(ACTION_PREF_UNIT_SYSTEM_UPDATE);
    }

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final Activity activity = getActivity();
            if(activity == null)
                return;

            final String action = intent.getAction();
            switch (action) {
                case AttributeEvent.STATE_DISCONNECTED:
                    updateMavlinkVersionPreference(null);
                    updateFirmwareVersionPreference(null);
                    break;

                case AttributeEvent.HEARTBEAT_FIRST:
                case AttributeEvent.HEARTBEAT_RESTORED:
                    int mavlinkVersion = intent.getIntExtra(AttributeEventExtra.EXTRA_MAVLINK_VERSION, -1);
                    if (mavlinkVersion == -1)
                        updateMavlinkVersionPreference(null);
                    else
                        updateMavlinkVersionPreference(String.valueOf(mavlinkVersion));
                    break;

                case AttributeEvent.STATE_CONNECTED:
                case AttributeEvent.TYPE_UPDATED:
                    Drone drone = dpApp.getDrone();
                    if (drone.isConnected()) {
                        Type droneType = drone.getAttribute(AttributeType.TYPE);
                        updateFirmwareVersionPreference(droneType.getFirmwareVersion());
                    } else
                        updateFirmwareVersionPreference(null);
                    break;

                case ACTION_PREF_UNIT_SYSTEM_UPDATE:
                    setupAltitudePreferences();
                    break;
            }
        }
    };

    /**
     * Keep track of which preferences' summary need to be updated.
     */
    private final HashSet<String> mDefaultSummaryPrefs = new HashSet<String>();

    private final Handler mHandler = new Handler();

    private DroidPlannerApp dpApp;
    private DroidPlannerPrefs dpPrefs;
    private LocalBroadcastManager lbm;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        dpApp = (DroidPlannerApp) activity.getApplication();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        initSummaryPerPrefs();

        final Context context = getActivity().getApplicationContext();
        dpPrefs = new DroidPlannerPrefs(context);
        lbm = LocalBroadcastManager.getInstance(context);
        final SharedPreferences sharedPref = dpPrefs.prefs;

        // Populate the map preference category
        final String mapsProvidersPrefKey = DroidPlannerPrefs.PREF_MAPS_PROVIDERS;
        final ListPreference mapsProvidersPref = (ListPreference) findPreference(mapsProvidersPrefKey);
        if (mapsProvidersPref != null) {
            final DPMapProvider[] providers = DPMapProvider.values();
            final int providersCount = providers.length;

            final CharSequence[] providersNames = new CharSequence[providersCount];
            final CharSequence[] providersNamesValues = new CharSequence[providersCount];
            for (int i = 0; i < providersCount; i++) {
                final String providerName = providers[i].name();
                providersNamesValues[i] = providerName;
                providersNames[i] = providerName.toLowerCase(Locale.ENGLISH).replace('_', ' ');
            }

            final String defaultProviderName = sharedPref.getString(mapsProvidersPrefKey,
                    DPMapProvider.DEFAULT_MAP_PROVIDER.name());

            mapsProvidersPref.setEntries(providersNames);
            mapsProvidersPref.setEntryValues(providersNamesValues);
            mapsProvidersPref.setValue(defaultProviderName);
            mapsProvidersPref
                    .setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

                        @Override
                        public boolean onPreferenceChange(Preference preference, Object newValue) {
                            // Update the map provider settings preference.
                            final String mapProviderName = newValue.toString();
                            return updateMapSettingsPreference(mapProviderName);
                        }
                    });

            updateMapSettingsPreference(defaultProviderName);
        }

        // update the summary for the preferences in the mDefaultSummaryPrefs hash table.
        for (String prefKey : mDefaultSummaryPrefs) {
            final Preference pref = findPreference(prefKey);
            if (pref != null) {
                pref.setSummary(sharedPref.getString(prefKey, ""));
            }
        }

        // Set the usage statistics preference
        final String usageStatKey = DroidPlannerPrefs.PREF_USAGE_STATISTICS;
        final CheckBoxPreference usageStatPref = (CheckBoxPreference) findPreference(usageStatKey);
        if (usageStatPref != null) {
            usageStatPref
                    .setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                        @Override
                        public boolean onPreferenceChange(Preference preference, Object newValue) {
                            // Update the google analytics singleton.
                            final boolean optIn = (Boolean) newValue;
                            final GoogleAnalytics analytics = GoogleAnalytics.getInstance(context);
                            analytics.setAppOptOut(!optIn);
                            return true;
                        }
                    });
        }

        final Preference storagePref = findPreference(DroidPlannerPrefs.PREF_STORAGE);
        if (storagePref != null) {
            storagePref.setSummary(DirectoryPath.getPublicDataPath());
        }

        try {
            Preference versionPref = findPreference(DroidPlannerPrefs.PREF_APP_VERSION);
            if (versionPref != null) {
                String version = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), 0).versionName;
                versionPref.setSummary(version);
            }
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Unable to retrieve version name.", e);
        }

        updateMavlinkVersionPreference(null);

        setupPeriodicControls();
        setupConnectionPreferences();
        setupAdvancedMenu();
        setupUnitSystemPreferences();
        setupBluetoothDevicePreferences();
        setupImminentGroundCollisionWarningPreference();
        setupMapPreferences();
        setupAltitudePreferences();
    }

    private void setupAdvancedMenu(){
        final CheckBoxPreference hdopToggle = (CheckBoxPreference) findPreference(DroidPlannerPrefs.PREF_SHOW_GPS_HDOP);
        if(hdopToggle !=  null) {
            hdopToggle.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    lbm.sendBroadcast(new Intent(ACTION_PREF_HDOP_UPDATE));
                    return true;
                }
            });
        }

        final CheckBoxPreference killSwitch = (CheckBoxPreference) findPreference(DroidPlannerPrefs.PREF_ENABLE_KILL_SWITCH);
        if(killSwitch != null) {
            killSwitch.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    lbm.sendBroadcast(new Intent(ACTION_ADVANCED_MENU_UPDATED));
                    return true;
                }
            });
        }
    }

    private void setupUnitSystemPreferences(){
        ListPreference unitSystemPref = (ListPreference) findPreference(DroidPlannerPrefs.PREF_UNIT_SYSTEM);
        if(unitSystemPref != null){
            int defaultUnitSystem = dpPrefs.getUnitSystemType();
            updateUnitSystemSummary(unitSystemPref, defaultUnitSystem);
            unitSystemPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    int unitSystem = Integer.parseInt((String) newValue);
                    updateUnitSystemSummary(preference, unitSystem);
                    lbm.sendBroadcast(new Intent(ACTION_PREF_UNIT_SYSTEM_UPDATE));
                    return true;
                }
            });
        }
    }

    private void setupMapPreferences(){
        final CheckBoxPreference mapRotation = (CheckBoxPreference) findPreference(DroidPlannerPrefs.PREF_ENABLE_MAP_ROTATION);
        mapRotation.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                lbm.sendBroadcast(new Intent(ACTION_MAP_ROTATION_PREFERENCE_UPDATED));
                return true;
            }
        });
    }

    private void setupImminentGroundCollisionWarningPreference(){
        final CheckBoxPreference collisionWarn = (CheckBoxPreference) findPreference(DroidPlannerPrefs.PREF_WARNING_GROUND_COLLISION);
        if(collisionWarn != null){
            collisionWarn.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final boolean isEnabled = (Boolean) newValue;
                    if(!isEnabled){
                        lbm.sendBroadcast(new Intent(Drone.ACTION_GROUND_COLLISION_IMMINENT)
                                .putExtra(Drone.EXTRA_IS_GROUND_COLLISION_IMMINENT, false));
                    }
                    return true;
                }
            });
        }
    }

    private void updateUnitSystemSummary(Preference preference, int unitSystemType){
        final int summaryResId;
        switch(unitSystemType){
            case 0:
            default:
                summaryResId = R.string.unit_system_entry_auto;
                break;

            case 1:
                summaryResId = R.string.unit_system_entry_metric;
                break;

            case 2:
                summaryResId = R.string.unit_system_entry_imperial;
                break;
        }

        preference.setSummary(summaryResId);
    }

    private void setupConnectionPreferences() {
        ListPreference connectionTypePref = (ListPreference) findPreference(DroidPlannerPrefs.PREF_CONNECTION_TYPE);
        if (connectionTypePref != null) {
            int defaultConnectionType = dpPrefs.getConnectionParameterType();
            updateConnectionPreferenceSummary(connectionTypePref, defaultConnectionType);
            connectionTypePref
                    .setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                        @Override
                        public boolean onPreferenceChange(Preference preference, Object newValue) {
                            int connectionType = Integer.parseInt((String) newValue);
                            updateConnectionPreferenceSummary(preference, connectionType);
                            return true;
                        }
                    });
        }
    }

    private void setupBluetoothDevicePreferences(){
        final ClearBTDialogPreference preference = (ClearBTDialogPreference) findPreference(DroidPlannerPrefs.PREF_BT_DEVICE_ADDRESS);
        if(preference != null){
            updateBluetoothDevicePreference(preference, dpPrefs.getBluetoothDeviceAddress());
            preference.setOnResultListener(new ClearBTDialogPreference.OnResultListener() {
                @Override
                public void onResult(boolean result) {
                    if (result) {
                        updateBluetoothDevicePreference(preference, dpPrefs.getBluetoothDeviceAddress());
                    }
                }
            });
        }
    }

    private void setupAltitudePreferences(){
        setupAltitudePreferenceHelper(DroidPlannerPrefs.PREF_ALT_MAX_VALUE, dpPrefs.getMaxAltitude());
        setupAltitudePreferenceHelper(DroidPlannerPrefs.PREF_ALT_MIN_VALUE, dpPrefs.getMinAltitude());
        setupAltitudePreferenceHelper(DroidPlannerPrefs.PREF_ALT_DEFAULT_VALUE, dpPrefs.getDefaultAltitude());
    }

    private void setupAltitudePreferenceHelper(final String prefKey, int defaultAlt){
        final LengthUnitProvider lup = getLengthUnitProvider();

        final EditTextPreference altPref = (EditTextPreference) findPreference(prefKey);
        if(altPref != null){
            final LengthUnit altValue = lup.boxBaseValueToTarget(defaultAlt);

            altPref.setText(String.valueOf((int) altValue.getValue()));
            altPref.setSummary(altValue.toString());

            altPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    try {
                        final int altValue = Integer.parseInt(newValue.toString());

                        final LengthUnitProvider lup = getLengthUnitProvider();
                        final LengthUnit newAltValue = lup.boxTargetValue(altValue);

                        altPref.setText(String.valueOf((int) newAltValue.getValue()));
                        altPref.setSummary(newAltValue.toString());

                        dpPrefs.setAltitudePreference(prefKey, (int) lup.fromTargetToBase(newAltValue).getValue());
                    } catch (NumberFormatException e) {

                    }
                    return false;
                }
            });
        }
    }

    private LengthUnitProvider getLengthUnitProvider(){
        final UnitSystem unitSystem = UnitManager.getUnitSystem(getActivity().getApplicationContext());
        return unitSystem.getLengthUnitProvider();
    }

    private void updateBluetoothDevicePreference(Preference preference, String deviceAddress){
        if(TextUtils.isEmpty(deviceAddress)) {
            preference.setEnabled(false);
            preference.setTitle(R.string.pref_no_saved_bluetooth_device_title);
            preference.setSummary("");
        }
        else{
            preference.setEnabled(true);
            preference.setSummary(deviceAddress);

            final String deviceName = dpPrefs.getBluetoothDeviceName();
            if(deviceName != null){
                preference.setTitle(getString(R.string.pref_forget_bluetooth_device_title, deviceName));
            }
            else
                preference.setTitle(getString(R.string.pref_forget_bluetooth_device_address));
        }
    }

    private void updateConnectionPreferenceSummary(Preference preference, int connectionType) {
        String connectionName;
        switch (connectionType) {
            case ConnectionType.TYPE_USB:
                connectionName = "USB";
                break;

            case ConnectionType.TYPE_UDP:
                connectionName = "UDP";
                break;

            case ConnectionType.TYPE_TCP:
                connectionName = "TCP";
                break;

            case ConnectionType.TYPE_BLUETOOTH:
                connectionName = "BLUETOOTH";
                break;

            default:
                connectionName = null;
                break;
        }

        if (connectionName != null)
            preference.setSummary(connectionName);
    }

    private void initSummaryPerPrefs() {
        mDefaultSummaryPrefs.clear();

        mDefaultSummaryPrefs.add(DroidPlannerPrefs.PREF_USB_BAUD_RATE);
        mDefaultSummaryPrefs.add(DroidPlannerPrefs.PREF_TCP_SERVER_PORT);
        mDefaultSummaryPrefs.add(DroidPlannerPrefs.PREF_TCP_SERVER_IP);
        mDefaultSummaryPrefs.add(DroidPlannerPrefs.PREF_UDP_SERVER_PORT);
        mDefaultSummaryPrefs.add(DroidPlannerPrefs.PREF_UDP_PING_RECEIVER_IP);
        mDefaultSummaryPrefs.add(DroidPlannerPrefs.PREF_UDP_PING_RECEIVER_PORT);
    }

    /**
     * This is used to update the mavlink version preference.
     *
     * @param version mavlink version
     */
    private void updateMavlinkVersionPreference(String version) {
        final Preference mavlinkVersionPref = findPreference(DroidPlannerPrefs.PREF_MAVLINK_VERSION);
        if (mavlinkVersionPref != null) {
            final HitBuilders.EventBuilder mavlinkEvent = new HitBuilders.EventBuilder()
                    .setCategory(GAUtils.Category.MAVLINK_CONNECTION);

            if (version == null) {
                mavlinkVersionPref.setSummary(getString(R.string.empty_content));
                mavlinkEvent.setAction("Mavlink version unset");
            } else {
                mavlinkVersionPref.setSummary('v' + version);
                mavlinkEvent.setAction("Mavlink version set").setLabel(version);
            }

            // Record the mavlink version
            GAUtils.sendEvent(mavlinkEvent);
        }
    }

    private void updateFirmwareVersionPreference(String firmwareVersion) {
        final Preference firmwareVersionPref = findPreference(DroidPlannerPrefs.PREF_FIRMWARE_VERSION);
        if (firmwareVersionPref != null) {
            final HitBuilders.EventBuilder firmwareEvent = new HitBuilders.EventBuilder()
                    .setCategory(GAUtils.Category.MAVLINK_CONNECTION);

            if (firmwareVersion == null) {
                firmwareVersionPref.setSummary(getString(R.string.empty_content));
                firmwareEvent.setAction("Firmware version unset");
            } else {
                firmwareVersionPref.setSummary(firmwareVersion);
                firmwareEvent.setAction("Firmware version set").setLabel(firmwareVersion);
            }

            // Record the firmware version.
            GAUtils.sendEvent(firmwareEvent);
        }
    }

    private boolean updateMapSettingsPreference(final String mapProviderName) {
        final DPMapProvider mapProvider = DPMapProvider.getMapProvider(mapProviderName);
        if (mapProvider == null)
            return false;

        final Preference providerPrefs = findPreference(DroidPlannerPrefs.PREF_MAPS_PROVIDER_SETTINGS);
        if (providerPrefs != null) {
            providerPrefs.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    startActivity(new Intent(getActivity(), MapPreferencesActivity.class).putExtra(
                            MapPreferencesActivity.EXTRA_MAP_PROVIDER_NAME, mapProviderName));
                    return true;
                }
            });
        }
        return true;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        final Preference preference = findPreference(key);
        if (preference == null) {
            return;
        }

        if (mDefaultSummaryPrefs.contains(key)) {
            preference.setSummary(sharedPreferences.getString(key, ""));
        }
    }

    private void setupPeriodicControls() {
        final PreferenceCategory periodicSpeechPrefs = (PreferenceCategory) findPreference(DroidPlannerPrefs.PREF_TTS_PERIODIC);
        ListPreference periodic = ((ListPreference) periodicSpeechPrefs.getPreference(0));
        periodic.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, final Object newValue) {
                // Broadcast the event locally on update.
                // A handler is used to that the current action has the time to
                // return, and store the value in the preferences.
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        lbm.sendBroadcast(new Intent(ACTION_UPDATED_STATUS_PERIOD)
                                .putExtra(EXTRA_UPDATED_STATUS_PERIOD, (String) newValue));

                        setupPeriodicControls();
                    }
                });
                return true;
            }
        });

        int val = Integer.parseInt(periodic.getValue());

        final boolean isEnabled = val != 0;
        if (isEnabled) {
            periodic.setSummary(getString(R.string.pref_tts_status_every) + " " + val + " "
                    + getString(R.string.pref_tts_seconds));
        } else {
            periodic.setSummary(R.string.pref_tts_periodic_status_disabled);
        }

        for (int i = 1; i < periodicSpeechPrefs.getPreferenceCount(); i++) {
            periodicSpeechPrefs.getPreference(i).setEnabled(isEnabled);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        dpApp.addApiListener(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        dpApp.removeApiListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(
                this);
    }

    @Override
    public void onApiConnected() {
        Drone drone = dpApp.getDrone();
        State droneState = drone.getAttribute(AttributeType.STATE);
        Type droneType = drone.getAttribute(AttributeType.TYPE);
        final int mavlinkVersion = droneState == null
                ? State.INVALID_MAVLINK_VERSION
                : droneState.getMavlinkVersion();

        if (mavlinkVersion != State.INVALID_MAVLINK_VERSION) {
            updateMavlinkVersionPreference(String.valueOf(mavlinkVersion));
        } else {
            updateMavlinkVersionPreference(null);
        }

        String firmwareVersion = droneType == null ? null : droneType.getFirmwareVersion();
        updateFirmwareVersionPreference(firmwareVersion);

        lbm.registerReceiver(broadcastReceiver, intentFilter);
    }

    @Override
    public void onApiDisconnected() {
        lbm.unregisterReceiver(broadcastReceiver);
    }
}
