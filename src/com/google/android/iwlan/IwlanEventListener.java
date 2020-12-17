// Copyright 2020 Google Inc. All Rights Reserved

package com.google.android.iwlan;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.util.SparseArray;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class IwlanEventListener {

    public static final int UNKNOWN_EVENT = -1;

    /** On receiving {@link CarrierConfigManager#ACTION_CARRIER_CONFIG_CHANGED} intent. */
    public static final int CARRIER_CONFIG_CHANGED_EVENT = 1;

    /** Wifi turned off or disabled. */
    public static final int WIFI_DISABLE_EVENT = 2;

    /** Airplane mode turned off or disabled. */
    public static final int APM_DISABLE_EVENT = 3;
    /** Airplame mode turned on or enabled */
    public static final int APM_ENABLE_EVENT = 4;

    /** Wifi AccessPoint changed. */
    public static final int WIFI_AP_CHANGED_EVENT = 5;

    /** Wifi calling turned off or disabled */
    public static final int WIFI_CALLING_DISABLE_EVENT = 6;

    @IntDef({
        CARRIER_CONFIG_CHANGED_EVENT,
        WIFI_DISABLE_EVENT,
        APM_DISABLE_EVENT,
        APM_ENABLE_EVENT,
        WIFI_AP_CHANGED_EVENT,
        WIFI_CALLING_DISABLE_EVENT
    })
    @interface IwlanEventType {};

    private final String LOG_TAG;

    private static Boolean sIsAirplaneModeOn;

    private static Map<Integer, IwlanEventListener> mInstances = new ConcurrentHashMap<>();

    private Context mContext;
    private int mSlotId;

    SparseArray<Set<Handler>> eventHandlers = new SparseArray<>();

    /** Returns IwlanEventListener instance */
    public static IwlanEventListener getInstance(@NonNull Context context, int slotId) {
        return mInstances.computeIfAbsent(slotId, k -> new IwlanEventListener(context, slotId));
    }

    /**
     * Adds handler for the list of events.
     *
     * @param events lists of events for which the handler needs to be notified.
     * @param handler handler to be called when the events happen
     */
    public synchronized void addEventListener(List<Integer> events, Handler handler) {
        for (@IwlanEventType int event : events) {
            if (eventHandlers.contains(event)) {
                eventHandlers.get(event).add(handler);
            } else {
                Set<Handler> handlers = new HashSet<>();
                handlers.add(handler);
                eventHandlers.append(event, handlers);
            }
        }
    }

    /**
     * Removes handler for the list of events.
     *
     * @param events lists of events for which the handler needs to be removed.
     * @param handler handler to be removed
     */
    public synchronized void removeEventListener(List<Integer> events, Handler handler) {
        for (int event : events) {
            if (eventHandlers.contains(event)) {
                Set<Handler> handlers = eventHandlers.get(event);
                handlers.remove(handler);
                if (handlers.isEmpty()) {
                    eventHandlers.delete(event);
                }
            }
        }
        if (eventHandlers.size() == 0) {
            mInstances.remove(mSlotId, this);
        }
    }

    /**
     * Removes handler for all events it is registered
     *
     * @param handler handler to be removed
     */
    public synchronized void removeEventListener(Handler handler) {
        for (int i = 0; i < eventHandlers.size(); i++) {
            Set<Handler> handlers = eventHandlers.valueAt(i);
            handlers.remove(handler);
            if (handlers.isEmpty()) {
                eventHandlers.delete(eventHandlers.keyAt(i));
            }
            eventHandlers.valueAt(i).remove(handler);
        }
        if (eventHandlers.size() == 0) {
            mInstances.remove(mSlotId, this);
        }
    }

    /**
     * Report a Broadcast received. Mainly used by IwlanBroadcastReceiver to report the following
     * broadcasts CARRIER_CONFIG_CHANGED
     *
     * @param Intent intent
     */
    public static synchronized void onBroadcastReceived(Intent intent) {
        int event = UNKNOWN_EVENT;
        switch (intent.getAction()) {
            case CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED:
                int slotId =
                        intent.getIntExtra(
                                CarrierConfigManager.EXTRA_SLOT_INDEX,
                                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                Context context = IwlanDataService.getContext();
                if (slotId != SubscriptionManager.INVALID_SIM_SLOT_INDEX && context != null) {
                    event = CARRIER_CONFIG_CHANGED_EVENT;
                    getInstance(context, slotId).updateHandlers(event);
                }
                break;
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                Boolean isAirplaneModeOn = new Boolean(intent.getBooleanExtra("state", false));
                if (sIsAirplaneModeOn != null && sIsAirplaneModeOn.equals(isAirplaneModeOn)) {
                    // no change in apm state
                    break;
                }
                sIsAirplaneModeOn = isAirplaneModeOn;
                event = sIsAirplaneModeOn ? APM_ENABLE_EVENT : APM_DISABLE_EVENT;
                for (Map.Entry<Integer, IwlanEventListener> entry : mInstances.entrySet()) {
                    IwlanEventListener instance = entry.getValue();
                    instance.updateHandlers(event);
                }
                break;
        }
    }

    /**
     * Returns the Event id of the String. String that matches the name of the event
     *
     * @param event String form of the event.
     * @param int form of the event.
     */
    public static int getUnthrottlingEvent(String event) {
        int ret = UNKNOWN_EVENT;
        switch (event) {
            case "CARRIER_CONFIG_CHANGED_EVENT":
                ret = CARRIER_CONFIG_CHANGED_EVENT;
                break;
            case "WIFI_DISABLE_EVENT":
                ret = WIFI_DISABLE_EVENT;
                break;
            case "APM_DISABLE_EVENT":
                ret = APM_DISABLE_EVENT;
                break;
            case "APM_ENABLE_EVENT":
                ret = APM_ENABLE_EVENT;
                break;
            case "WIFI_AP_CHANGED_EVENT":
                ret = WIFI_AP_CHANGED_EVENT;
                break;
            case "WIFI_CALLING_DISABLE_EVENT":
                ret = WIFI_CALLING_DISABLE_EVENT;
                break;
        }
        return ret;
    }

    private IwlanEventListener(Context context, int slotId) {
        mContext = context;
        mSlotId = slotId;
        LOG_TAG = IwlanEventListener.class.getSimpleName() + "[" + slotId + "]";
        sIsAirplaneModeOn = null;
    }

    private synchronized void updateHandlers(int event) {
        if (eventHandlers.contains(event)) {
            Log.d(LOG_TAG, "Updating handlers for the event: " + event);
            for (Handler handler : eventHandlers.get(event)) {
                handler.obtainMessage(event).sendToTarget();
            }
        }
    }
}
