/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * DataControlWidgetProvider - Radek kAmMa Davidek
 *
 */

package com.android.datawidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

/**
 * Provides control of power-related settings from a widget.
 */
public class DataControlWidgetProvider extends AppWidgetProvider {
	static final String TAG = "DataControlWidgetProvider";

	static final ComponentName THIS_APPWIDGET = new ComponentName("com.android.datawidget",
			"com.android.datawidget.DataControlWidgetProvider");

	private static final int BUTTON_WIFI = 0;
	private static final int BUTTON_APN = 1;
	private static final int BUTTON_BLUETOOTH = 2;
	private static final int BUTTON_SLEEP = 3;
	private static final int BUTTON_BRIGHTNESS = 4;

	// Position in the widget bar, to enable different graphics for left, center
	// and right buttons
	private static final int POS_LEFT = 0;
	private static final int POS_CENTER = 1;

	private static final int[] ind_data_DRAWABLE_OFF = { R.drawable.appwidget_settings_ind_off_l_holo,
			R.drawable.appwidget_settings_ind_off_c_holo, R.drawable.appwidget_settings_ind_off_r_holo };

	private static final int[] ind_data_DRAWABLE_MID = { R.drawable.appwidget_settings_ind_mid_l_holo,
			R.drawable.appwidget_settings_ind_mid_c_holo, R.drawable.appwidget_settings_ind_mid_r_holo };

	private static final int[] ind_data_DRAWABLE_ON = { R.drawable.appwidget_settings_ind_on_l_holo,
			R.drawable.appwidget_settings_ind_on_c_holo, R.drawable.appwidget_settings_ind_on_r_holo };

	private static final StateTracker sWifiState = new WifiStateTracker();
	private static final StateTracker sBluetoothState = new BluetoothStateTracker();
	private static final StateTracker sApnState = new ApnStateTracker();

	/**
	 * Minimum brightness at which the indicator is shown at half-full and ON
	 */
	private static final float HALF_BRIGHTNESS_THRESHOLD = 0.3f;
	/**
	 * Minimum brightness at which the indicator is shown at full
	 */
	private static final float FULL_BRIGHTNESS_THRESHOLD = 0.8f;
	private static SettingsObserver sSettingsObserver;
	private static ConnectionStateMonitor csm;

	/**
	 * The state machine for a setting's toggling, tracking reality versus the
	 * user's intent.
	 * <p>
	 * This is necessary because reality moves relatively slowly (turning on &amp;
	 * off radio drivers), compared to user's expectations.
	 */
	private abstract static class StateTracker {
		private Boolean mActualState = null; // initially not set
		private Boolean mIntendedState = null; // initially not set

		/**
		 * Return the ID of the main large image button for the setting.
		 */
		public abstract int getButtonId();

		/**
		 * Returns the small indicator image ID underneath the setting.
		 */
		public abstract int getIndicatorId();

		/**
		 * Returns the resource ID of the image to show as a function of the on-vs-off
		 * state.
		 */
		public abstract int getButtonImageId(boolean on);

		/**
		 * Returns the position in the button bar - either POS_LEFT, POS_RIGHT or
		 * POS_CENTER.
		 */
		public int getPosition() {
			return POS_CENTER;
		}

		/**
		 * Updates the remote views depending on the state (off, on, turning off,
		 * turning on) of the setting.
		 */
		public final void setImageViewResources(Context context, RemoteViews views) {
			int buttonId = getButtonId();
			int indicatorId = getIndicatorId();
			int pos = getPosition();
			if (getActualState(context) == false) {
				views.setImageViewResource(buttonId, getButtonImageId(false));
				views.setImageViewResource(indicatorId, ind_data_DRAWABLE_OFF[pos]);
			} else if (getActualState(context) == true) {
				views.setImageViewResource(buttonId, getButtonImageId(true));
				views.setImageViewResource(indicatorId, ind_data_DRAWABLE_ON[pos]);
			}
		}

		/**
		 * Gets underlying actual state.
		 *
		 * @param context
		 * @return true or false
		 */
		public abstract boolean getActualState(Context context);

		/**
		 * Actually make the desired change to the underlying radio API.
		 */
		protected abstract void requestStateChange(Context context);
	}

	/**
	 * Subclass of StateTracker to get/set Wifi state.
	 */
	private static final class WifiStateTracker extends StateTracker {
		public int getButtonId() {
			return R.id.img_data_wifi;
		}

		public int getIndicatorId() {
			return R.id.ind_data_wifi;
		}

		public int getButtonImageId(boolean on) {
			return on ? R.drawable.ic_appwidget_settings_wifi_on_holo : R.drawable.ic_appwidget_settings_wifi_off_holo;
		}

		@Override
		public int getPosition() {
			return POS_LEFT;
		}

		@Override
		public boolean getActualState(Context context) {
			WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
			if (wifiManager != null) {
				int actualState = wifiManager.getWifiState();
				Log.d(TAG, "Actual wifiState:" + actualState);
				if (actualState == WifiManager.WIFI_STATE_ENABLED)
					return true;
				else
					return false;
			}
			return false;
		}

		@Override
		protected void requestStateChange(Context context) {
			Log.d(TAG, "WIFI toggle");
			WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
			if (wifiManager == null) {
				Log.d(TAG, "No WifiManager.");
				return;
			}

			new AsyncTask<Void, Void, Void>() {
				@Override
				protected Void doInBackground(Void... args) {
					/**
					 * Disable tethering if enabling Wifi
					 */
					int wifiApState = wifiManager.getWifiApState();
					boolean desiredState = !getActualState(context);
					Log.d(TAG, "Actual wifiApState:" + wifiApState);
					Log.d(TAG, "Desired wifiState:" + desiredState);
					if (desiredState && ((wifiApState == WifiManager.WIFI_AP_STATE_ENABLING)
							|| (wifiApState == WifiManager.WIFI_AP_STATE_ENABLED))) {
						final ConnectivityManager connectivityManager = (ConnectivityManager) context
								.getSystemService(Context.CONNECTIVITY_SERVICE);
						connectivityManager.stopTethering(ConnectivityManager.TETHERING_WIFI);
					}
					wifiManager.setWifiEnabled(desiredState);

					for (int i = 0; i < 15; i++) {
						boolean state = getActualState(context);
						Log.d(TAG, "Actual wifiState:" + state + " cycle: " + i);
						if ((desiredState && state) || (!desiredState && !state)) {
							return null;
						}
						try {
							Thread.sleep(1000);
						} catch (Exception e) {
						}
					}
					Toast.makeText(context, "Cannot change WiFi state.", Toast.LENGTH_SHORT).show();
					return null;
				}

				@Override
				protected void onPostExecute(Void result) {
					updateWidget(context);
				};
			}.execute();
		}
	}

	/**
	 * Subclass of StateTracker to get/set Bluetooth state.
	 */
	private static final class BluetoothStateTracker extends StateTracker {
		public int getButtonId() {
			return R.id.img_data_bluetooth;
		}

		public int getIndicatorId() {
			return R.id.ind_data_bluetooth;
		}

		public int getButtonImageId(boolean on) {
			return on ? R.drawable.ic_appwidget_settings_bluetooth_on_holo
					: R.drawable.ic_appwidget_settings_bluetooth_off_holo;
		}

		@Override
		public boolean getActualState(Context context) {
			BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
			if (ba != null) {
				int actualState = ba.getState();
				Log.d(TAG, "Actual btState:" + actualState);
				if (actualState == BluetoothAdapter.STATE_ON)
					return true;
				else
					return false;
			}
			return false;
		}

		@Override
		protected void requestStateChange(Context context) {
			Log.d(TAG, "BT toggle");
			BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
			if (ba == null) {
				Log.d(TAG, "No BluetoothAdapter.");
				return;
			}

			new AsyncTask<Void, Void, Void>() {
				@Override
				protected Void doInBackground(Void... args) {
					boolean desiredState = !getActualState(context);
					Log.d(TAG, "Desired wifiState:" + desiredState);

					if (desiredState)
						ba.enable();
					else
						ba.disable();

					for (int i = 0; i < 15; i++) {
						boolean state = getActualState(context);
						Log.d(TAG, "Actual btState:" + state + " cycle: " + i);
						if ((desiredState && state) || (!desiredState && !state)) {
							return null;
						}
						try {
							Thread.sleep(1000);
						} catch (Exception e) {
						}
					}
					Toast.makeText(context, "Cannot change BT state.", Toast.LENGTH_SHORT).show();
					return null;
				}

				@Override
				protected void onPostExecute(Void result) {
					updateWidget(context);
				};
			}.execute();
		}
	}

	/**
	 * Subclass of StateTracker for APN state.
	 */
	private static final class ApnStateTracker extends StateTracker {
		public int getButtonId() {
			return R.id.img_data_apn;
		}

		public int getIndicatorId() {
			return R.id.ind_data_apn;
		}

		public int getButtonImageId(boolean on) {
			return on ? R.drawable.ic_appwidget_settings_apn_on_holo : R.drawable.ic_appwidget_settings_apn_off_holo;
		}

		@Override
		public boolean getActualState(Context context) {
			TelephonyManager tm = TelephonyManager.from(context);
			if (tm != null) {
				boolean actualState = tm.getDataEnabled();
				Log.d(TAG, "Actual apnState:" + actualState);
				return actualState;
			}
			return false;
		}

		@Override
		public void requestStateChange(final Context context) {
			Log.d(TAG, "APN DATA toggle");
			TelephonyManager tm = TelephonyManager.from(context);
			if (tm == null) {
				Log.d(TAG, "No TelephonyManager.");
				return;
			}

			new AsyncTask<Void, Void, Void>() {
				@Override
				protected Void doInBackground(Void... args) {
					boolean desiredState = !getActualState(context);
					Log.d(TAG, "Desired apnState:" + desiredState);

					tm.setDataEnabled(desiredState);

					for (int i = 0; i < 15; i++) {
						boolean state = getActualState(context);
						Log.d(TAG, "Actual apnState:" + state + " cycle: " + i);
						if ((desiredState && state) || (!desiredState && !state)) {
							return null;
						}
						try {
							Thread.sleep(1000);
						} catch (Exception e) {
						}
					}
					Toast.makeText(context, "Cannot change APN state.", Toast.LENGTH_SHORT).show();
					return null;
				}

				@Override
				protected void onPostExecute(Void result) {
					updateWidget(context);
				};
			}.execute();
		}
	}

	private static void checkObserver(Context context) {
		if (sSettingsObserver == null) {
			sSettingsObserver = new SettingsObserver(new Handler(), context.getApplicationContext());
			sSettingsObserver.startObserving();
		}
	}

	private static void checkConnectionStateMonitor(Context context) {
		if (csm == null) {
			csm = new ConnectionStateMonitor(context);
			csm.enable();
		}
	}

	@Override
	public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
		// Update each requested appWidgetId
		RemoteViews view = buildUpdate(context);

		for (int i = 0; i < appWidgetIds.length; i++) {
			appWidgetManager.updateAppWidget(appWidgetIds[i], view);
		}
	}

	@Override
	public void onEnabled(Context context) {
		Class clazz = com.android.datawidget.DataControlWidgetProvider.class;
		PackageManager pm = context.getPackageManager();
		pm.setComponentEnabledSetting(new ComponentName(context.getPackageName(), clazz.getName()),
				PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
		checkObserver(context);
		checkConnectionStateMonitor(context);
	}

	@Override
	public void onDisabled(Context context) {
		Class clazz = com.android.datawidget.DataControlWidgetProvider.class;
		PackageManager pm = context.getPackageManager();
		pm.setComponentEnabledSetting(new ComponentName(context.getPackageName(), clazz.getName()),
				PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
		if (sSettingsObserver != null) {
			sSettingsObserver.stopObserving();
			sSettingsObserver = null;
		}
		if (csm != null) {
			csm.disable();
			csm = null;
		}
	}

	/**
	 * Load image for given widget and build {@link RemoteViews} for it.
	 */
	static RemoteViews buildUpdate(Context context) {
		RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_data);
		views.setOnClickPendingIntent(R.id.btn_data_wifi, getLaunchPendingIntent(context, BUTTON_WIFI));
		views.setOnClickPendingIntent(R.id.btn_data_bluetooth, getLaunchPendingIntent(context, BUTTON_BLUETOOTH));
		views.setOnClickPendingIntent(R.id.btn_data_apn, getLaunchPendingIntent(context, BUTTON_APN));
		views.setOnClickPendingIntent(R.id.btn_data_sleep, getLaunchPendingIntent(context, BUTTON_SLEEP));
		views.setOnClickPendingIntent(R.id.btn_data_settings, getSettingsIntent(context));
		views.setOnClickPendingIntent(R.id.btn_data_tether, getTetherIntent(context));
		views.setOnClickPendingIntent(R.id.btn_brightness, getLaunchPendingIntent(context, BUTTON_BRIGHTNESS));

		updateButtons(views, context);
		return views;
	}

	/**
	 * Updates the widget when something changes, or when a button is pushed.
	 * 
	 * @param context
	 */
	public static void updateWidget(Context context) {
		RemoteViews views = buildUpdate(context);
		// Update specific list of appWidgetIds if given, otherwise default to
		// all
		final AppWidgetManager gm = AppWidgetManager.getInstance(context);
		gm.updateAppWidget(THIS_APPWIDGET, views);
		checkObserver(context);
		checkConnectionStateMonitor(context);
	}

	/**
	 * Updates the buttons based on the underlying states of wifi, etc.
	 * 
	 * @param views   The RemoteViews to update.
	 * @param context
	 */
	private static void updateButtons(RemoteViews views, Context context) {
		sWifiState.setImageViewResources(context, views);
		sBluetoothState.setImageViewResources(context, views);
		sApnState.setImageViewResources(context, views);

		if (getBrightnessMode(context)) {
			views.setContentDescription(R.id.btn_brightness, context.getString(R.string.gadget_brightness_template,
					context.getString(R.string.gadget_brightness_state_auto)));
			views.setImageViewResource(R.id.img_brightness, R.drawable.ic_appwidget_settings_brightness_auto_holo);
			views.setImageViewResource(R.id.ind_brightness, R.drawable.appwidget_settings_ind_on_c_holo);
		} else {
			final int brightness = getBrightness(context);
			final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
			// Set the icon
			final int full = (int) (pm.getMaximumScreenBrightnessSetting() * FULL_BRIGHTNESS_THRESHOLD);
			final int half = (int) (pm.getMaximumScreenBrightnessSetting() * HALF_BRIGHTNESS_THRESHOLD);
			if (brightness > full) {
				views.setContentDescription(R.id.btn_brightness, context.getString(R.string.gadget_brightness_template,
						context.getString(R.string.gadget_brightness_state_full)));
				views.setImageViewResource(R.id.img_brightness, R.drawable.ic_appwidget_settings_brightness_full_holo);
			} else if (brightness > half) {
				views.setContentDescription(R.id.btn_brightness, context.getString(R.string.gadget_brightness_template,
						context.getString(R.string.gadget_brightness_state_half)));
				views.setImageViewResource(R.id.img_brightness, R.drawable.ic_appwidget_settings_brightness_half_holo);
			} else {
				views.setContentDescription(R.id.btn_brightness, context.getString(R.string.gadget_brightness_template,
						context.getString(R.string.gadget_brightness_state_off)));
				views.setImageViewResource(R.id.img_brightness, R.drawable.ic_appwidget_settings_brightness_off_holo);
			}
			// Set the ON state
			if (brightness > half) {
				views.setImageViewResource(R.id.ind_brightness, R.drawable.appwidget_settings_ind_on_c_holo);
			} else {
				views.setImageViewResource(R.id.ind_brightness, R.drawable.appwidget_settings_ind_off_c_holo);
			}
		}
	}

	/**
	 * Creates PendingIntent to notify the widget of a button click.
	 * 
	 * @param context
	 * @return
	 */
	private static PendingIntent getLaunchPendingIntent(Context context, int buttonId) {
		Intent launchIntent = new Intent();
		launchIntent.setClass(context, DataControlWidgetProvider.class);
		launchIntent.addCategory(Intent.CATEGORY_ALTERNATIVE);
		launchIntent.setData(Uri.parse("custom:" + buttonId));
		PendingIntent pi = PendingIntent.getBroadcast(context, 0 /*
																	 * no requestCode
																	 */, launchIntent, 0 /*
																							 * no flags
																							 */);
		return pi;
	}

	private static PendingIntent getSettingsIntent(Context context) {
		Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
		return pendingIntent;
	}

	private static PendingIntent getTetherIntent(Context context) {
		Intent intent = new Intent();
		intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
		return pendingIntent;
	}

	/**
	 * Receives and processes a button pressed intent or state change.
	 * 
	 * @param context
	 * @param intent  Indicates the pressed button.
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		super.onReceive(context, intent);
		Log.d(TAG, "onReceive hasCategory:" + intent.hasCategory(Intent.CATEGORY_ALTERNATIVE));
		if (intent.hasCategory(Intent.CATEGORY_ALTERNATIVE)) {
			Uri data = intent.getData();
			int buttonId = Integer.parseInt(data.getSchemeSpecificPart());
			Log.d(TAG, "onReceive buttonId:" + buttonId);
			if (buttonId == BUTTON_WIFI) {
				sWifiState.requestStateChange(context);
			} else if (buttonId == BUTTON_BLUETOOTH) {
				sBluetoothState.requestStateChange(context);
			} else if (buttonId == BUTTON_APN) {
				sApnState.requestStateChange(context);
			} else if (buttonId == BUTTON_SLEEP) {
				Log.d(TAG, "Going to SLEEP");
				long now = SystemClock.uptimeMillis();
				PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
				if (pm != null)
					pm.goToSleep(now);
			} else if (buttonId == BUTTON_BRIGHTNESS) {
				toggleBrightness(context);
			}
		} else {
			// Don't fall-through to updating the widget. The Intent
			// was something unrelated or that our super class took
			// care of.
			return;
		}
		// State changes fall through
		updateWidget(context);
	}

	/**
	 * Gets brightness level.
	 *
	 * @param context
	 * @return brightness level between 0 and 255.
	 */
	private static int getBrightness(Context context) {
		try {
			int brightness = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
			return brightness;
		} catch (Exception e) {
		}
		return 0;
	}

	/**
	 * Gets state of brightness mode.
	 *
	 * @param context
	 * @return true if auto brightness is on.
	 */
	private static boolean getBrightnessMode(Context context) {
		try {
			int brightnessMode = Settings.System.getInt(context.getContentResolver(),
					Settings.System.SCREEN_BRIGHTNESS_MODE);
			return brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
		} catch (Exception e) {
			Log.d(TAG, "getBrightnessMode: " + e);
		}
		return false;
	}

	/**
	 * Increases or decreases the brightness.
	 *
	 * @param context
	 */
	private void toggleBrightness(Context context) {
		try {
			DisplayManager dm = context.getSystemService(DisplayManager.class);
			PowerManager pm = context.getSystemService(PowerManager.class);

			ContentResolver cr = context.getContentResolver();
			int brightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS);
			int brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
			// Only get brightness setting if available
			if (context.getResources().getBoolean(com.android.internal.R.bool.config_automatic_brightness_available)) {
				brightnessMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE);
			}

			// Rotate AUTO -> MINIMUM -> DEFAULT -> MAXIMUM
			// Technically, not a toggle...
			if (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
				brightness = pm.getMinimumScreenBrightnessSetting();
				brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
			} else if (brightness < pm.getDefaultScreenBrightnessSetting()) {
				brightness = pm.getDefaultScreenBrightnessSetting();
			} else if (brightness < pm.getMaximumScreenBrightnessSetting()) {
				brightness = pm.getMaximumScreenBrightnessSetting();
			} else {
				brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
				brightness = pm.getMinimumScreenBrightnessSetting();
			}

			if (context.getResources().getBoolean(com.android.internal.R.bool.config_automatic_brightness_available)) {
				// Set screen brightness mode (automatic or manual)
				Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
						brightnessMode);
			} else {
				// Make sure we set the brightness if automatic mode isn't available
				brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
			}
			if (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
				dm.setTemporaryBrightness(brightness);
				Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, brightness);
			}
		} catch (Settings.SettingNotFoundException e) {
			Log.d(TAG, "toggleBrightness: " + e);
		}
	}

	/** Observer to watch for changes to the BRIGHTNESS setting */
	private static class SettingsObserver extends ContentObserver {

		private Context mContext;

		SettingsObserver(Handler handler, Context context) {
			super(handler);
			mContext = context;
		}

		void startObserving() {
			ContentResolver resolver = mContext.getContentResolver();
			// Listen to brightness and brightness mode
			resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, this);
			resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false,
					this);
			resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ),
					false, this);
		}

		void stopObserving() {
			mContext.getContentResolver().unregisterContentObserver(this);
		}

		@Override
		public void onChange(boolean selfChange) {
			updateWidget(mContext);
		}
	}

	public static class ConnectionStateMonitor extends NetworkCallback {

		final NetworkRequest networkRequest;
		private Context mContext;

		public ConnectionStateMonitor(Context mContext) {
			this.mContext = mContext;
			networkRequest = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
					.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
					.addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH).build();
		}

		public void disable() {
			ConnectivityManager connectivityManager = (ConnectivityManager) mContext
					.getSystemService(Context.CONNECTIVITY_SERVICE);
			connectivityManager.unregisterNetworkCallback(this);
		}

		public void enable() {
			ConnectivityManager connectivityManager = (ConnectivityManager) mContext
					.getSystemService(Context.CONNECTIVITY_SERVICE);
			connectivityManager.registerNetworkCallback(networkRequest, this);
		}

		@Override
		public void onAvailable(Network network) {
			Log.d(TAG, "Connection available...");
			updateWidget(mContext);
		}

		@Override
		public void onLost(Network network) {
			Log.d(TAG, "Connection lost...");
			updateWidget(mContext);
		}
	}
}
