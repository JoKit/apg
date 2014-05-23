/*
 * Copyright (C) 2014 Dominik Schürmann <dominik@dominikschuermann.de>
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
 */

package org.thialfihar.android.apg.ui.adapter;

import android.content.Context;
import android.support.v4.content.AsyncTaskLoader;

import org.thialfihar.android.apg.Constants;
import org.thialfihar.android.apg.keyimport.HkpKeyserver;
import org.thialfihar.android.apg.keyimport.ImportKeysListEntry;
import org.thialfihar.android.apg.keyimport.Keyserver;
import org.thialfihar.android.apg.util.Log;

import java.util.ArrayList;

public class ImportKeysListServerLoader
    extends AsyncTaskLoader<AsyncTaskResultWrapper<ArrayList<ImportKeysListEntry>>> {
    private Context mContext;

    private String mServerQuery;
    private String mKeyserver;

    private ArrayList<ImportKeysListEntry> mEntryList = new ArrayList<ImportKeysListEntry>();
    private AsyncTaskResultWrapper<ArrayList<ImportKeysListEntry>> mEntryListWrapper;

    public ImportKeysListServerLoader(Context context, String serverQuery, String keyServer) {
        super(context);
        mContext = context;
        mServerQuery = serverQuery;
        mKeyserver = keyServer;
    }

    @Override
    public AsyncTaskResultWrapper<ArrayList<ImportKeysListEntry>> loadInBackground() {

        mEntryListWrapper = new AsyncTaskResultWrapper<ArrayList<ImportKeysListEntry>>(mEntryList, null);

        if (mServerQuery == null) {
            Log.e(Constants.TAG, "mServerQuery is null!");
            return mEntryListWrapper;
        }

        if (mServerQuery.startsWith("0x") && mServerQuery.length() == 42) {
            Log.d(Constants.TAG, "This search is based on a unique fingerprint. Enforce a fingerprint check!");
            queryServer(mServerQuery, mKeyserver, true);
        } else {
            queryServer(mServerQuery, mKeyserver, false);
        }

        return mEntryListWrapper;
    }

    @Override
    protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();
    }

    @Override
    protected void onStartLoading() {
        forceLoad();
    }

    @Override
    protected void onStopLoading() {
        cancelLoad();
    }

    @Override
    public void deliverResult(AsyncTaskResultWrapper<ArrayList<ImportKeysListEntry>> data) {
        super.deliverResult(data);
    }

    /**
     * Query keyserver
     */
    private void queryServer(String query, String keyServer, boolean enforceFingerprint) {
        HkpKeyserver server = new HkpKeyserver(keyServer);
        try {
            ArrayList<ImportKeysListEntry> searchResult = server.search(query);

            mEntryList.clear();
            // add result to data
            if (enforceFingerprint) {
                String fingerprint = query.substring(2);
                Log.d(Constants.TAG, "fingerprint: " + fingerprint);
                // query must return only one result!
                if (searchResult.size() == 1) {
                    ImportKeysListEntry uniqueEntry = searchResult.get(0);
                    /*
                     * set fingerprint explicitly after query
                     * to enforce a check when the key is imported by KeychainIntentService
                     */
                    uniqueEntry.setFingerprintHex(fingerprint);
                    uniqueEntry.setSelected(true);
                    mEntryList.add(uniqueEntry);
                }
            } else {
                mEntryList.addAll(searchResult);
            }
            mEntryListWrapper = new AsyncTaskResultWrapper<ArrayList<ImportKeysListEntry>>(mEntryList, null);
        } catch (Keyserver.QueryFailedException e) {
            mEntryListWrapper = new AsyncTaskResultWrapper<ArrayList<ImportKeysListEntry>>(mEntryList, e);
        } catch (Keyserver.QueryNeedsRepairException e) {
            mEntryListWrapper = new AsyncTaskResultWrapper<ArrayList<ImportKeysListEntry>>(mEntryList, e);
        }
    }

}
