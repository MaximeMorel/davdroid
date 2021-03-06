    /*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package at.bitfire.davdroid.syncadapter;

import android.accounts.Account;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.NotificationCompat;
import android.text.TextUtils;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import at.bitfire.dav4android.DavResource;
import at.bitfire.dav4android.exception.ConflictException;
import at.bitfire.dav4android.exception.DavException;
import at.bitfire.dav4android.exception.HttpException;
import at.bitfire.dav4android.exception.PreconditionFailedException;
import at.bitfire.dav4android.exception.ServiceUnavailableException;
import at.bitfire.dav4android.exception.UnauthorizedException;
import at.bitfire.dav4android.property.GetCTag;
import at.bitfire.dav4android.property.GetETag;
import at.bitfire.davdroid.AccountSettings;
import at.bitfire.davdroid.App;
import at.bitfire.davdroid.HttpClient;
import at.bitfire.davdroid.InvalidAccountException;
import at.bitfire.davdroid.R;
import at.bitfire.davdroid.resource.LocalCollection;
import at.bitfire.davdroid.resource.LocalResource;
import at.bitfire.davdroid.ui.AccountActivity;
import at.bitfire.davdroid.ui.AccountSettingsActivity;
import at.bitfire.davdroid.ui.DebugInfoActivity;
import at.bitfire.ical4android.CalendarStorageException;
import at.bitfire.vcard4android.ContactsStorageException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

abstract public class SyncManager {

    protected final int SYNC_PHASE_PREPARE = 0,
                        SYNC_PHASE_QUERY_CAPABILITIES = 1,
                        SYNC_PHASE_PROCESS_LOCALLY_DELETED = 2,
                        SYNC_PHASE_PREPARE_DIRTY = 3,
                        SYNC_PHASE_UPLOAD_DIRTY = 4,
                        SYNC_PHASE_CHECK_SYNC_STATE = 5,
                        SYNC_PHASE_LIST_LOCAL = 6,
                        SYNC_PHASE_LIST_REMOTE = 7,
                        SYNC_PHASE_COMPARE_LOCAL_REMOTE = 8,
                        SYNC_PHASE_DOWNLOAD_REMOTE = 9,
                        SYNC_PHASE_SAVE_SYNC_STATE = 10;

    protected final NotificationManager notificationManager;
    protected final int notificationId;

    protected final Context context;
    protected final Account account;
    protected final Bundle extras;
    protected final String authority;
    protected final SyncResult syncResult;

    protected final AccountSettings settings;
    protected LocalCollection localCollection;

    protected OkHttpClient httpClient;
    protected HttpUrl collectionURL;
    protected DavResource davCollection;


    /** remote CTag at the time of {@link #listRemote()} */
    protected String remoteCTag = null;

    /** sync-able resources in the local collection, as enumerated by {@link #listLocal()} */
    protected Map<String, LocalResource> localResources;

    /** sync-able resources in the remote collection, as enumerated by {@link #listRemote()} */
    protected Map<String, DavResource> remoteResources;

    /** resources which have changed on the server, as determined by {@link #compareLocalRemote()} */
    protected Set<DavResource> toDownload;



    public SyncManager(int notificationId, Context context, Account account, Bundle extras, String authority, SyncResult syncResult) throws InvalidAccountException {
        this.context = context;
        this.account = account;
        this.extras = extras;
        this.authority = authority;
        this.syncResult = syncResult;

        // get account settings (for sync interval etc.)
        settings = new AccountSettings(context, account);

        // create HttpClient with given logger
        httpClient = HttpClient.create(context, account);

        // dismiss previous error notifications
        notificationManager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(account.name, this.notificationId = notificationId);
    }

    protected abstract String getSyncErrorTitle();

    @TargetApi(21)
    public void performSync() {
        int syncPhase = SYNC_PHASE_PREPARE;
        try {
            App.log.info("Preparing synchronization");
            prepare();

            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_QUERY_CAPABILITIES;
            App.log.info("Querying capabilities");
            queryCapabilities();

            syncPhase = SYNC_PHASE_PROCESS_LOCALLY_DELETED;
            App.log.info("Processing locally deleted entries");
            processLocallyDeleted();

            if (Thread.interrupted())
                return;
            syncPhase = SYNC_PHASE_PREPARE_DIRTY;
            App.log.info("Locally preparing dirty entries");
            prepareDirty();

            syncPhase = SYNC_PHASE_UPLOAD_DIRTY;
            App.log.info("Uploading dirty entries");
            uploadDirty();

            syncPhase = SYNC_PHASE_CHECK_SYNC_STATE;
            App.log.info("Checking sync state");
            if (checkSyncState()) {
                syncPhase = SYNC_PHASE_LIST_LOCAL;
                App.log.info("Listing local entries");
                listLocal();

                if (Thread.interrupted())
                    return;
                syncPhase = SYNC_PHASE_LIST_REMOTE;
                App.log.info("Listing remote entries");
                listRemote();

                if (Thread.interrupted())
                    return;
                syncPhase = SYNC_PHASE_COMPARE_LOCAL_REMOTE;
                App.log.info("Comparing local/remote entries");
                compareLocalRemote();

                syncPhase = SYNC_PHASE_DOWNLOAD_REMOTE;
                App.log.info("Downloading remote entries");
                downloadRemote();

                syncPhase = SYNC_PHASE_SAVE_SYNC_STATE;
                App.log.info("Saving sync state");
                saveSyncState();
            } else
                App.log.info("Remote collection didn't change, skipping remote sync");

        } catch (IOException|ServiceUnavailableException e) {
            App.log.log(Level.WARNING, "I/O exception during sync, trying again later", e);
            syncResult.stats.numIoExceptions++;

            if (e instanceof ServiceUnavailableException) {
                Date retryAfter = ((ServiceUnavailableException) e).retryAfter;
                if (retryAfter != null) {
                    // how many seconds to wait? getTime() returns ms, so divide by 1000
                    syncResult.delayUntil = (retryAfter.getTime() - new Date().getTime()) / 1000;
                }
            }

        } catch(Exception|OutOfMemoryError e) {
            final int messageString;

            if (e instanceof UnauthorizedException) {
                App.log.log(Level.SEVERE, "Not authorized anymore", e);
                messageString = R.string.sync_error_unauthorized;
                syncResult.stats.numAuthExceptions++;
            } else if (e instanceof HttpException || e instanceof DavException) {
                App.log.log(Level.SEVERE, "HTTP/DAV Exception during sync", e);
                messageString = R.string.sync_error_http_dav;
                syncResult.stats.numParseExceptions++;
            } else if (e instanceof CalendarStorageException || e instanceof ContactsStorageException) {
                App.log.log(Level.SEVERE, "Couldn't access local storage", e);
                messageString = R.string.sync_error_local_storage;
                syncResult.databaseError = true;
            } else {
                App.log.log(Level.SEVERE, "Unknown sync error", e);
                messageString = R.string.sync_error;
                syncResult.stats.numParseExceptions++;
            }

            final Intent detailsIntent;
            if (e instanceof UnauthorizedException) {
                detailsIntent = new Intent(context, AccountSettingsActivity.class);
                detailsIntent.putExtra(AccountSettingsActivity.EXTRA_ACCOUNT, account);
            } else {
                detailsIntent = new Intent(context, DebugInfoActivity.class);
                detailsIntent.putExtra(DebugInfoActivity.KEY_EXCEPTION, e);
                detailsIntent.putExtra(DebugInfoActivity.KEY_ACCOUNT, account);
                detailsIntent.putExtra(DebugInfoActivity.KEY_AUTHORITY, authority);
                detailsIntent.putExtra(DebugInfoActivity.KEY_PHASE, syncPhase);
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
            builder .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(getSyncErrorTitle())
                    .setContentIntent(PendingIntent.getActivity(context, notificationId, detailsIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setLocalOnly(true);

            try {
                String[] phases = context.getResources().getStringArray(R.array.sync_error_phases);
                String message = context.getString(messageString, phases[syncPhase]);
                builder.setContentText(message);
            } catch (IndexOutOfBoundsException ex) {
                // should never happen
            }

            notificationManager.notify(account.name, notificationId, builder.build());
        }
    }


    abstract protected void prepare() throws ContactsStorageException;

    abstract protected void queryCapabilities() throws IOException, HttpException, DavException, CalendarStorageException, ContactsStorageException;

    /**
     * Process locally deleted entries (DELETE them on the server as well).
     * Checks Thread.interrupted() before each request to allow quick sync cancellation.
     */
    protected void processLocallyDeleted() throws CalendarStorageException, ContactsStorageException {
        // Remove locally deleted entries from server (if they have a name, i.e. if they were uploaded before),
        // but only if they don't have changed on the server. Then finally remove them from the local address book.
        LocalResource[] localList = localCollection.getDeleted();
        for (LocalResource local : localList) {
            if (Thread.interrupted())
                return;

            final String fileName = local.getFileName();
            if (!TextUtils.isEmpty(fileName)) {
                App.log.info(fileName + " has been deleted locally -> deleting from server");
                try {
                    new DavResource(httpClient, collectionURL.newBuilder().addPathSegment(fileName).build())
                            .delete(local.getETag());
                } catch (IOException|HttpException e) {
                    App.log.warning("Couldn't delete " + fileName + " from server; ignoring (may be downloaded again)");
                }
            } else
                App.log.info("Removing local record #" + local.getId() + " which has been deleted locally and was never uploaded");
            local.delete();
            syncResult.stats.numDeletes++;
        }
    }

    protected void prepareDirty() throws CalendarStorageException, ContactsStorageException {
        // assign file names and UIDs to new contacts so that we can use the file name as an index
        for (LocalResource local : localCollection.getWithoutFileName()) {
            String uuid = UUID.randomUUID().toString();
            App.log.info("Found local record #" + local.getId() + " without file name; assigning file name/UID based on " + uuid);
            local.updateFileNameAndUID(uuid);
        }
    }

    abstract protected RequestBody prepareUpload(LocalResource resource) throws IOException, CalendarStorageException, ContactsStorageException;

    /**
     * Uploads dirty records to the server, using a PUT request for each record.
     * Checks Thread.interrupted() before each request to allow quick sync cancellation.
     */
    protected void uploadDirty() throws IOException, HttpException, CalendarStorageException, ContactsStorageException {
        // upload dirty contacts
        for (LocalResource local : localCollection.getDirty()) {
            if (Thread.interrupted())
                return;

            final String fileName = local.getFileName();

            DavResource remote = new DavResource(httpClient, collectionURL.newBuilder().addPathSegment(fileName).build());

            // generate entity to upload (VCard, iCal, whatever)
            RequestBody body = prepareUpload(local);

            try {

                if (local.getETag() == null) {
                    App.log.info("Uploading new record " + fileName);
                    remote.put(body, null, true);
                } else {
                    App.log.info("Uploading locally modified record " + fileName);
                    remote.put(body, local.getETag(), false);
                }

            } catch (ConflictException|PreconditionFailedException e) {
                // we can't interact with the user to resolve the conflict, so we treat 409 like 412
                App.log.log(Level.INFO, "Resource has been modified on the server before upload, ignoring", e);
            }

            String eTag = null;
            GetETag newETag = (GetETag) remote.properties.get(GetETag.NAME);
            if (newETag != null) {
                eTag = newETag.eTag;
                App.log.fine("Received new ETag=" + eTag + " after uploading");
            } else
                App.log.fine("Didn't receive new ETag after uploading, setting to null");

            local.clearDirty(eTag);
        }
    }

    /**
     * Checks the current sync state (e.g. CTag) and whether synchronization from remote is required.
     * @return <ul>
     *      <li><code>true</code>   if the remote collection has changed, i.e. synchronization from remote is required</li>
     *      <li><code>false</code>  if the remote collection hasn't changed</li>
     * </ul>
     */
    protected boolean checkSyncState() throws CalendarStorageException, ContactsStorageException {
        // check CTag (ignore on manual sync)
        GetCTag getCTag = (GetCTag)davCollection.properties.get(GetCTag.NAME);
        if (getCTag != null)
            remoteCTag = getCTag.cTag;

        String localCTag = null;
        if (extras.containsKey(ContentResolver.SYNC_EXTRAS_MANUAL))
            App.log.info("Manual sync, ignoring CTag");
        else
            localCTag = localCollection.getCTag();

        if (remoteCTag != null && remoteCTag.equals(localCTag)) {
            App.log.info("Remote collection didn't change (CTag=" + remoteCTag + "), no need to query children");
            return false;
        } else
            return true;
    }

    /**
     * Lists all local resources which should be taken into account for synchronization into {@link #localResources}.
     */
    protected void listLocal() throws CalendarStorageException, ContactsStorageException {
        // fetch list of local contacts and build hash table to index file name
        LocalResource[] localList = localCollection.getAll();
        localResources = new HashMap<>(localList.length);
        for (LocalResource resource : localList) {
            App.log.fine("Found local resource: " + resource.getFileName());
            localResources.put(resource.getFileName(), resource);
        }
    }

    /**
     * Lists all members of the remote collection which should be taken into account for synchronization into {@link #remoteResources}.
     */
    abstract protected void listRemote() throws IOException, HttpException, DavException;

    /**
     * Compares {@link #localResources} and {@link #remoteResources} by file name and ETag:
     * <ul>
     *     <li>Local resources which are not available in the remote collection (anymore) will be removed.</li>
     *     <li>Resources whose remote ETag has changed will be added into {@link #toDownload}</li>
     * </ul>
     */
    protected void compareLocalRemote() throws IOException, HttpException, DavException, CalendarStorageException, ContactsStorageException {
        /* check which contacts
           1. are not present anymore remotely -> delete immediately on local side
           2. updated remotely -> add to downloadNames
           3. added remotely  -> add to downloadNames
         */
        toDownload = new HashSet<>();
        for (String localName : localResources.keySet()) {
            DavResource remote = remoteResources.get(localName);
            if (remote == null) {
                App.log.info(localName + " is not on server anymore, deleting");
                localResources.get(localName).delete();
                syncResult.stats.numDeletes++;
            } else {
                // contact is still on server, check whether it has been updated remotely
                GetETag getETag = (GetETag)remote.properties.get(GetETag.NAME);
                if (getETag == null || getETag.eTag == null)
                    throw new DavException("Server didn't provide ETag");
                String localETag = localResources.get(localName).getETag(),
                        remoteETag = getETag.eTag;
                if (remoteETag.equals(localETag))
                    syncResult.stats.numSkippedEntries++;
                else {
                    App.log.info(localName + " has been changed on server (current ETag=" + remoteETag + ", last known ETag=" + localETag + ")");
                    toDownload.add(remote);
                }

                // remote entry has been seen, remove from list
                remoteResources.remove(localName);
            }
        }

        // add all unseen (= remotely added) remote contacts
        if (!remoteResources.isEmpty()) {
            App.log.info("New resources have been found on the server: " + TextUtils.join(", ", remoteResources.keySet()));
            toDownload.addAll(remoteResources.values());
        }
    }

    /**
     * Downloads the remote resources in {@link #toDownload} and stores them locally.
     * Must check Thread.interrupted() periodically to allow quick sync cancellation.
     */
    abstract protected void downloadRemote() throws IOException, HttpException, DavException, ContactsStorageException, CalendarStorageException;

    protected void saveSyncState() throws CalendarStorageException, ContactsStorageException {
        /* Save sync state (CTag). It doesn't matter if it has changed during the sync process
           (for instance, because another client has uploaded changes), because this will simply
           cause all remote entries to be listed at the next sync. */
        App.log.info("Saving CTag=" + remoteCTag);
        localCollection.setCTag(remoteCTag);
    }

}
