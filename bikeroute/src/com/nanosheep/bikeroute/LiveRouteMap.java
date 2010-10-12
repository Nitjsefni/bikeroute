/**
 * 
 */
package com.nanosheep.bikeroute;

import org.andnav.osm.util.GeoPoint;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnDismissListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.nanosheep.bikeroute.constants.BikeRouteConsts;
import com.nanosheep.bikeroute.service.RoutePlannerService;
import edu.wlu.cs.levy.CG.KeySizeException;

/**
 * Extends RouteMap providing live/satnav features - turn guidance advancing with location,
 * route replanning.
 * 
 * @author jono@nanosheep.net
 * @version Oct 4, 2010
 */
public class LiveRouteMap extends SpeechRouteMap implements LocationListener {
	/** Intent for replanning searches. **/
	protected Intent searchIntent;
	/** Replanning result receiver. **/
	protected BroadcastReceiver routeReceiver;
	/** Planning dialog tracker. **/
	protected boolean mShownDialog;
	private boolean spoken;
	private Object lastSegment;
	
	@Override
	public void onCreate(final Bundle savedState) {
		super.onCreate(savedState);
		mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
		//Handle rotations
		final Object[] data = (Object[]) getLastNonConfigurationInstance();
		if (data != null) {
			isSearching = (Boolean) data[2];
			if (isSearching) {
				routeReceiver = new ReplanReceiver();
				registerReceiver(routeReceiver, new IntentFilter(RoutePlannerService.INTENT_ID));
			}
			mShownDialog = (Boolean) data[1];
		}
		
		spoken = false;
		lastSegment = app.getSegment();
	}
	
	/**
	 * Retain any state if the screen is rotated.
	 */
	
	@Override
	public Object onRetainNonConfigurationInstance() {
		Object[] objs = new Object[3];
		objs[0] = directionsVisible;
		objs[1] = mShownDialog;
		objs[2] = isSearching;
	    return objs;
	}
	
	@Override
	public final boolean onPrepareOptionsMenu(final Menu menu) {
		final MenuItem replan = menu.findItem(R.id.replan);
		replan.setVisible(true);
		if (app.getRoute() == null) {
			replan.setVisible(false);
		}
		return super.onPrepareOptionsMenu(menu);
	}
	
	@Override
	public void showStep() {
		if (!spoken) {
			speak(app.getSegment());
			spoken = true;
		}
		super.showStep();
		mLocationOverlay.enableMyLocation();
	}
	
	/**
	 * Fire a replanning request (current location -> last point of route.)
	 * to the routing service, display a dialog while in progress.
	 */
	
	private void replan() {
		if (tts) {
			directionsTts.speak("Reeplanning.", TextToSpeech.QUEUE_FLUSH, null);
		}
		showDialog(BikeRouteConsts.PLAN);
		
		mLocationOverlay.runOnFirstFix(new Runnable() {
			@Override
			public void run() {
						Location self = mLocationOverlay.getLastFix();
						
						if (self == null) {
							self = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
						}
						if (self == null) {
							self = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
						}
						if (self != null) {
							searchIntent = new Intent(LiveRouteMap.this, RoutePlannerService.class);
							searchIntent.putExtra(RoutePlannerService.PLAN_TYPE, RoutePlannerService.REPLAN_PLAN);
							searchIntent.putExtra(RoutePlannerService.START_LOCATION, self);
							searchIntent.putExtra(RoutePlannerService.END_POINT,
									app.getRoute().getPoints().get(app.getRoute().getPoints().size() - 1));
							isSearching = true;
							startService(searchIntent);
							routeReceiver = new ReplanReceiver();
							registerReceiver(routeReceiver, new IntentFilter(RoutePlannerService.INTENT_ID));
						} else {
							dismissDialog(BikeRouteConsts.PLAN);
							showDialog(BikeRouteConsts.PLAN_FAIL_DIALOG);
						}
				}
		});
	}
	

	public Dialog onCreateDialog(final int id) {
		Dialog dialog;
		switch(id) {
		case BikeRouteConsts.PLAN:
			ProgressDialog pDialog = new ProgressDialog(this);
			pDialog.setCancelable(true);
			pDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
			pDialog.setMessage(getText(R.string.plan_msg));
			pDialog.setOnDismissListener(new OnDismissListener() {
				@Override
				public void onDismiss(final DialogInterface arg0) {
					removeDialog(BikeRouteConsts.PLAN);
					if (!LiveRouteMap.this.isSearching) {
						mShownDialog = false;
					}
				}
				});
			pDialog.setOnCancelListener(new OnCancelListener() {

				@Override
				public void onCancel(final DialogInterface arg0) {
					if (isSearching) {
						stopService(searchIntent);
						unregisterReceiver(routeReceiver);
						isSearching = false;
					}
				}
			
			});
			dialog = pDialog;
			break;
		case BikeRouteConsts.PLAN_FAIL_DIALOG:
			if (tts) {
				directionsTts.speak("Planning failed.", TextToSpeech.QUEUE_FLUSH, null);
			}
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage(getText(R.string.planfail_msg)).setCancelable(
				true).setPositiveButton("OK",
				new DialogInterface.OnClickListener() {
					public void onClick(final DialogInterface dialog,
							final int id) {
					}
				});
			dialog = builder.create();
			break;
		default:
			dialog = super.onCreateDialog(id);
		}
		return dialog;
	}
	
	/**
	 * Overridden to deal with rotations which require tracking
	 * displayed dialog to ensure it is not duplicated.
	 */
	
	@Override
	protected void onPrepareDialog(final int id, final Dialog dialog) {
		super.onPrepareDialog(id, dialog);
		if (id == BikeRouteConsts.PLAN) {
			mShownDialog = true;
		}
	}
	
	/**
	 * Handle option selection.
	 * @return true if option selected.
	 */
	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
		case R.id.replan:
			replan();
			break;
		default:
			return super.onOptionsItemSelected(item);
		}
		return true;
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		if (routeReceiver != null) {
			unregisterReceiver(routeReceiver);
		}
		mLocationManager.removeUpdates(this);
	}

	private class ReplanReceiver extends BroadcastReceiver {

		/* (non-Javadoc)
		 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
		 */
		@Override
		public void onReceive(Context arg0, Intent intent) {
				if (intent.getIntExtra("msg", BikeRouteConsts.PLAN_FAIL_DIALOG) == BikeRouteConsts.RESULT_OK) {
					
					app.setSegment(app.getRoute().getSegments().get(0));
					mOsmv.getController().setCenter(app.getSegment().startPoint());
					dismissDialog(BikeRouteConsts.PLAN);
					traverse(app.getSegment().startPoint());
					if (directionsVisible) {
						spoken = false;
						lastSegment = app.getSegment();
						showStep();
					}
				} else {
					dismissDialog(BikeRouteConsts.PLAN);
					showDialog(intent.getIntExtra("msg", 0));
				}
				isSearching = false;
		}
		
	}
	
	/**
	 * Listen for location changes, check for the nearest point to the new location
	 * find the segment for it and advance to that step of the directions. If that point
	 * is more than 50m away, fire a replan request,
	 */
	
	/* (non-Javadoc)
	 * @see android.location.LocationListener#onLocationChanged(android.location.Location)
	 */
	@Override
	public void onLocationChanged(Location location) {
		if (directionsVisible && !isSearching) {
		try {
			GeoPoint self = new GeoPoint(location.getLatitude(), location.getLongitude());
			GeoPoint near = app.getRoute().nearest(self);
			if (self.distanceTo(near) < 50) {
				app.setSegment(app.getRoute().getSegment(near));
				if (!lastSegment.equals(app.getSegment())) {
					spoken = false;
					lastSegment = app.getSegment();
				}
				showStep();
				traverse(near);
			} else {
				replan();
			}
			
		} catch (KeySizeException e) {
			Log.e("KD", e.toString());
		}
		}
	}

	/* (non-Javadoc)
	 * @see android.location.LocationListener#onProviderDisabled(java.lang.String)
	 */
	@Override
	public void onProviderDisabled(String provider) {
		// TODO Auto-generated method stub
		
	}

	/* (non-Javadoc)
	 * @see android.location.LocationListener#onProviderEnabled(java.lang.String)
	 */
	@Override
	public void onProviderEnabled(String provider) {
		// TODO Auto-generated method stub
		
	}

	/* (non-Javadoc)
	 * @see android.location.LocationListener#onStatusChanged(java.lang.String, int, android.os.Bundle)
	 */
	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
		// TODO Auto-generated method stub
		
	}
}