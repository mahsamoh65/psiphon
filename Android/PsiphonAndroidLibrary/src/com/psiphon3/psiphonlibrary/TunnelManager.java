/*
 * Copyright (c) 2015, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.net.VpnService.Builder;
import android.preference.PreferenceManager;

import ca.psiphon.PsiphonTunnel;

import com.psiphon3.psiphonlibrary.UpgradeManager.VerifiedUpgradeFile;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

public class TunnelManager implements PsiphonTunnel.HostService {

    public enum NotificationState {
        CONNECTING,
        CONNECTED
    }

    private NotificationState m_state = NotificationState.CONNECTING;
    private Service m_parentService = null;
    private boolean m_serviceDestroyed = false;
    private boolean m_firstStart = true;
    private boolean m_signalledStop = false;
    private CountDownLatch m_tunnelThreadStopSignal;
    private Thread m_tunnelThread;
    private AtomicBoolean m_isReconnect;
    private AtomicBoolean m_isStopping;
    private PsiphonTunnel m_tunnel = null;
    private String m_lastUpstreamProxyErrorMessage;

    
    public TunnelManager(Service parentService) {
        m_parentService = parentService;
        m_isReconnect = new AtomicBoolean(false);
        m_isStopping = new AtomicBoolean(false);
        m_tunnel = PsiphonTunnel.newPsiphonTunnel(this);
    }

    // Implementation of android.app.Service.onStartCommand
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (m_firstStart) {
            m_parentService.startForeground(R.string.psiphon_service_notification_id, this.createNotification(false));
            MyLog.v(R.string.client_version, MyLog.Sensitivity.NOT_SENSITIVE, EmbeddedValues.CLIENT_VERSION);
            m_firstStart = false;
            m_tunnelThreadStopSignal = new CountDownLatch(1);
            m_tunnelThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runTunnel();
                    }
            });
            m_tunnelThread.start();
        }
        return android.app.Service.START_NOT_STICKY;
    }

    // Implementation of android.app.Service.onDestroy
    public void onDestroy() {
        m_serviceDestroyed = true;
        
        if (m_tunnelThread == null) {
            return;
        }

        // signalStopService should have been called, but in case is was not, call here.
        // If signalStopService was not already called, the join may block the calling
        // thread for some time.
        signalStopService();

        try {
            m_tunnelThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        m_tunnelThreadStopSignal = null;
        m_tunnelThread = null;
    }

    // signalStopService signals the runTunnel thread to stop. The thread will
    // self-stop the service. This is the preferred method for stopping the
    // Psiphon tunnel service:
    // 1. VpnService doesn't respond to stopService calls
    // 2. The UI will not block while waiting for stopService to return
    public void signalStopService() {
        m_signalledStop = true;
        if (m_tunnelThreadStopSignal != null) {
            m_tunnelThreadStopSignal.countDown();
        }
    }
    
    public boolean signalledStop() {
        return m_signalledStop;
    }

    private Notification createNotification(boolean alert) {
        int contentTextID = -1;
        int iconID = -1;
        CharSequence ticker = null;

        switch (getNotificationState()) {
        case CONNECTING:
            contentTextID = R.string.psiphon_service_notification_message_connecting;
            ticker = m_parentService.getText(R.string.psiphon_service_notification_message_connecting);
            iconID = PsiphonData.getPsiphonData().getNotificationIconConnecting();
            if (iconID == 0) {
                iconID = R.drawable.notification_icon_connecting_animation;
            }
            break;

        case CONNECTED:
            if (PsiphonData.getPsiphonData().getTunnelWholeDevice()) {
                contentTextID = R.string.psiphon_running_whole_device;
            }
            else {
                contentTextID = R.string.psiphon_running_browser_only;
            }

            iconID = PsiphonData.getPsiphonData().getNotificationIconConnected();
            if (iconID == 0) {
                iconID = R.drawable.notification_icon_connected;
            }
            break;

        default:
            assert(false);
        }

        Intent activityIntent = null;
        IEvents events = PsiphonData.getPsiphonData().getCurrentEventsInterface();
        if (events != null) {
            activityIntent = events.pendingSignalNotification(m_parentService);
        }

        if (activityIntent == null) {
            // Default intent if m_eventsInterface is null or returns a null pendingSignalNotification Intent.
            // This intent will launch nothing.
            // NOTE that setLatestEventInfo requires a PendingIntent.  And that calls to notify (ie from setState below)
            // require a contentView which is set by setLatestEventInfo.
            activityIntent = new Intent();
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        PendingIntent invokeActivityIntent =
                PendingIntent.getActivity(
                    m_parentService,
                    0,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification =
                new Notification(
                        iconID,
                        ticker,
                        System.currentTimeMillis());

        if (alert) {
            if (PreferenceManager.getDefaultSharedPreferences(m_parentService).getBoolean(
                    m_parentService.getString(R.string.preferenceNotificationsWithSound), false)) {
                notification.defaults |= Notification.DEFAULT_SOUND;
            }
            if (PreferenceManager.getDefaultSharedPreferences(m_parentService).getBoolean(
                    m_parentService.getString(R.string.preferenceNotificationsWithVibrate), false)) {
                notification.defaults |= Notification.DEFAULT_VIBRATE;
            }
        }

        notification.setLatestEventInfo(
            m_parentService,
            m_parentService.getText(R.string.app_name),
            m_parentService.getText(contentTextID),
            invokeActivityIntent);

        return notification;
    }

    private synchronized NotificationState getNotificationState() {
        return m_state;
    }

    private synchronized void setNotificationState(NotificationState newState) {
        if (m_serviceDestroyed) {
            return;
        }
        
        boolean alert = (newState != m_state);
        m_state = newState;

        String ns = Context.NOTIFICATION_SERVICE;
        NotificationManager notificationManager =
                (NotificationManager)m_parentService.getSystemService(ns);
        if (notificationManager != null) {
            notificationManager.notify(
                    R.string.psiphon_service_notification_id,
                    createNotification(alert));
        }
    }
    
    private final static String LEGACY_SERVER_ENTRY_FILENAME = "psiphon_server_entries.json";
    private final static int MAX_LEGACY_SERVER_ENTRIES = 100;

    private String getServerEntries() {
        StringBuilder list = new StringBuilder();
        
        for (String encodedServerEntry : EmbeddedValues.EMBEDDED_SERVER_LIST) {
            list.append(encodedServerEntry);
            list.append("\n");
        }
        
        // Import legacy server entries
        try {
            FileInputStream file = m_parentService.openFileInput(LEGACY_SERVER_ENTRY_FILENAME);
            BufferedReader reader = new BufferedReader(new InputStreamReader(file));
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            file.close();
            JSONObject obj = new JSONObject(json.toString());
            JSONArray jsonServerEntries = obj.getJSONArray("serverEntries");

            // MAX_LEGACY_SERVER_ENTRIES ensures the list we pass through to tunnel-core
            // is unlikely to trigger an OutOfMemoryError
            for (int i = 0; i < jsonServerEntries.length() && i < MAX_LEGACY_SERVER_ENTRIES; i++) {
                list.append(jsonServerEntries.getString(i));
                list.append("\n");
            }
            
            // Don't need to repeat the import again
            m_parentService.deleteFile(LEGACY_SERVER_ENTRY_FILENAME);
        } catch (FileNotFoundException e) {
            // pass
        } catch (IOException e) {
            MyLog.g("prepareServerEntries failed: %s", e.getMessage());
        } catch (JSONException e) {
            MyLog.g("prepareServerEntries failed: %s", e.getMessage());
        } catch (OutOfMemoryError e) {
            MyLog.g("prepareServerEntries failed: %s", e.getMessage());

            // Comment from legacy code:
            // Some mature client installs have so many server entries they cannot load them without
            // hitting out-of-memory, so they will not benefit from the MAX_SAVED_SERVER_ENTRIES_MEMORY_SIZE
            // limit added to saveServerEntries(). In this case, we simply ignore the saved list. The client
            // will proceed with the embedded list only, and going forward the MEMORY_SIZE limit will be
            // enforced.
        }
        
        return list.toString();
    }
    
    private void runTunnel() {

        Utils.checkSecureRandom();

        m_isStopping.set(false);
        m_isReconnect.set(false);
        
        // Notify if an upgrade has already been downloaded and is waiting for install
        UpgradeManager.UpgradeInstaller.notifyUpgrade(m_parentService);
        
        {
            // Don't hold a reference to the events object for long -- a new
            // Activity may register a new one and we ought to release the old
            // one for garbage collection.
            IEvents events = PsiphonData.getPsiphonData().getCurrentEventsInterface();
            if (events != null) {
                events.signalTunnelStarting(m_parentService);
            }
        }

        MyLog.v(R.string.current_network_type, MyLog.Sensitivity.NOT_SENSITIVE, Utils.getNetworkTypeName(m_parentService));

        MyLog.v(R.string.starting_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);
        
        PsiphonData.getPsiphonData().clearHomePages();
        
        PsiphonData.getPsiphonData().getDataTransferStats().startSession();
        
        boolean runVpn =
            PsiphonData.getPsiphonData().getTunnelWholeDevice() &&
            Utils.hasVpnService() &&
            // Guard against trying to start WDM mode when the global option flips while starting a TunnelService
            (m_parentService instanceof TunnelVpnService);

        try {
            if (runVpn) {
                if (!m_tunnel.startRouting()) {
                    throw new PsiphonTunnel.Exception("application is not prepared or revoked");
                }
                MyLog.v(R.string.vpn_service_running, MyLog.Sensitivity.NOT_SENSITIVE);
            }
            
            m_tunnel.startTunneling(getServerEntries());
            
            try {
                m_tunnelThreadStopSignal.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            m_isStopping.set(true);

        } catch (PsiphonTunnel.Exception e) {
            MyLog.e(R.string.start_tunnel_failed, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } finally {
            
            MyLog.v(R.string.stopping_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);
            
            {
                IEvents events = PsiphonData.getPsiphonData().getCurrentEventsInterface();
                if (events != null) {
                    events.signalTunnelStopping(m_parentService);
                }
            }

            m_tunnel.stop();
            
            PsiphonData.getPsiphonData().getDataTransferStats().stop();
            
            MyLog.v(R.string.stopped_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

            // Stop service
            m_parentService.stopForeground(true);
            m_parentService.stopSelf();
        }
    }

    @Override
    public String getAppName() {
        return m_parentService.getString(R.string.app_name);
    }

    @Override
    public Context getContext() {
        return m_parentService;
    }

    @Override
    public VpnService getVpnService() {
        return ((TunnelVpnService)m_parentService);
    }

    @Override
    public Builder newVpnServiceBuilder() {
        return ((TunnelVpnService)m_parentService).newBuilder();
    }

    @Override
    public String getPsiphonConfig() {        
        try {            
            JSONObject json = new JSONObject();
            
            // Don't re-download the upgrade package when a verified upgrade file is
            // awaiting application by the user. A previous upgrade download will have
            // completed and have been extracted to this verified upgrade file.
            // Without this check, tunnel-core won't know that the upgrade is already
            // downloaded, as the file name differs from UpgradeDownloadFilename, and
            // so the entire upgrade will be re-downloaded on each tunnel connect until
            // the user actually applies the upgrade.
            // As a result of this check, a user that delays applying an upgrade until
            // after a subsequent upgrade is released will first apply a stale upgrade
            // and then download the next upgrade.
            // Note: depends on getAvailableCompleteUpgradeFile deleting VerifiedUpgradeFile
            // after upgrade is complete. Otherwise, no further upgrades would download. 
            // TODO: implement version tracking for the verified upgrade file so that
            // we can proceed with downloading a newer upgrade when             
            boolean hasUpgradePending = (new VerifiedUpgradeFile(m_parentService)).exists();
            
            if (!hasUpgradePending &&
                0 < EmbeddedValues.UPGRADE_URL.length() && 
                EmbeddedValues.hasEverBeenSideLoaded(m_parentService) &&
                PsiphonData.getPsiphonData().getDownloadUpgrades()) {
                json.put("UpgradeDownloadUrl", EmbeddedValues.UPGRADE_URL);
                
                json.put("UpgradeDownloadFilename",
                        new UpgradeManager.DownloadedUpgradeFile(m_parentService).getFullPath());                
            }
            
            json.put("ClientPlatform", PsiphonConstants.PLATFORM);
            
            json.put("ClientVersion", EmbeddedValues.CLIENT_VERSION);
            
            json.put("PropagationChannelId", EmbeddedValues.PROPAGATION_CHANNEL_ID);
            
            json.put("SponsorId", EmbeddedValues.SPONSOR_ID);

            json.put("RemoteServerListUrl", EmbeddedValues.REMOTE_SERVER_LIST_URL);

            json.put("RemoteServerListSignaturePublicKey", EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY);

            json.put("LocalHttpProxyPort", PsiphonData.getPsiphonData().getConfiguredLocalHttpProxyPort());

            json.put("LocalSocksProxyPort", PsiphonData.getPsiphonData().getConfiguredLocalSocksProxyPort());

            json.put("UpstreamProxyUrl", PsiphonData.getPsiphonData().getUpstreamProxyUrl(m_parentService));            
            
            String egressRegion = PsiphonData.getPsiphonData().getEgressRegion();
            MyLog.g("EgressRegion", "regionCode", egressRegion);
            json.put("EgressRegion", egressRegion);
            
            return json.toString();

        } catch (JSONException e) {
            return "";
        }
    }

    @Override
    public void onDiagnosticMessage(String message) {
        MyLog.g("diagnostic", "msg", message);
    }

    @Override
    public void onAvailableEgressRegions(List<String> regions) {
        for (String region : regions) {
            RegionAdapter.setServerExists(m_parentService, region, false);
        }
    }

    @Override
    public void onSocksProxyPortInUse(int port) {
        MyLog.e(R.string.socks_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
        signalStopService();
    }

    @Override
    public void onHttpProxyPortInUse(int port) {
        MyLog.e(R.string.http_proxy_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
        signalStopService();
    }

    @Override
    public void onListeningSocksProxyPort(int port) {
        MyLog.v(R.string.socks_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
        PsiphonData.getPsiphonData().setListeningLocalSocksProxyPort(port);
    }

    @Override
    public void onListeningHttpProxyPort(int port) {
        MyLog.v(R.string.http_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
        PsiphonData.getPsiphonData().setListeningLocalHttpProxyPort(port);
    }

    @Override
    public void onUpstreamProxyError(String message) {
        // Display the error message only once, and continue trying to connect in
        // case the issue is temporary.
        if (m_lastUpstreamProxyErrorMessage != null && !m_lastUpstreamProxyErrorMessage.equals(message)) {
            MyLog.v(R.string.upstream_proxy_error, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, message);
            m_lastUpstreamProxyErrorMessage = message;
        }        
    }

    @Override
    public void onConnecting() {
        
        PsiphonData.getPsiphonData().getDataTransferStats().stop();

        // Don't update notification to CONNECTING, etc., when a stop was commanded.
        if (!m_isStopping.get()) {
            setNotificationState(NotificationState.CONNECTING);
            
            MyLog.v(R.string.tunnel_connecting, MyLog.Sensitivity.NOT_SENSITIVE);
            
            if (m_isReconnect.get()) {
                IEvents events = PsiphonData.getPsiphonData().getCurrentEventsInterface();
                if (events != null) {
                    events.signalUnexpectedDisconnect(m_parentService);
                }
            }
        }
    }

    @Override
    public void onConnected() {
        setNotificationState(NotificationState.CONNECTED);
        
        MyLog.v(R.string.tunnel_connected, MyLog.Sensitivity.NOT_SENSITIVE);
        
        PsiphonData.getPsiphonData().getDataTransferStats().startConnected();
        
        IEvents events = PsiphonData.getPsiphonData().getCurrentEventsInterface();
        if (events != null) {
            events.signalHandshakeSuccess(m_parentService, m_isReconnect.get());
        }

        // Any subsequent onConnecting after this first onConnect will be a reconnect.
        m_isReconnect.set(true);
    }

    @Override
    public void onHomepage(String url) {
        PsiphonData.getPsiphonData().addHomePage(url);        
    }

    @Override
    public void onClientRegion(String region) {
        PsiphonData.getPsiphonData().setClientRegion(region);
    }

    @Override
    public void onClientUpgradeDownloaded(String filename) {
        UpgradeManager.UpgradeInstaller.notifyUpgrade(m_parentService);      
    }

    @Override
    public void onSplitTunnelRegion(String region) {
        MyLog.v(R.string.split_tunnel_region, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, region);
    }

    @Override
    public void onUntunneledAddress(String address) {
        MyLog.v(R.string.untunneled_address, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, address);
    }

    @Override
    public void onBytesTransferred(long sent, long received) {
        PsiphonData.DataTransferStats stats = PsiphonData.getPsiphonData().getDataTransferStats();
        stats.addBytesSent(sent);
        stats.addBytesReceived(received);
    }

    @Override
    public void onStartedWaitingForNetworkConnectivity() {
        MyLog.v(R.string.waiting_for_network_connectivity, MyLog.Sensitivity.NOT_SENSITIVE);      
    }
}
