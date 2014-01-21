/*
 * Copyright (C) 2010, T-Mobile USA, Inc.
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
 */

package com.tmobile.thememanager.provider;

import com.tmobile.thememanager.Constants;
import com.tmobile.themes.provider.Themes;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriMatcher;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Proxy uri-based access to assets from theme packages. DRM security is upheld
 * at this layer which is why we can't just use the built-in android.resource://
 * uri scheme.
 */
public class PackageResourcesProvider extends ContentProvider {
    private static final UriMatcher URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int TYPE_RESOURCE_ID = 0;
    private static final int TYPE_RESOURCE_ENTRY = 1;
    private static final int TYPE_ASSET_PATH = 2;

    private static final int DEFAULT_ORIENTATION = Configuration.ORIENTATION_PORTRAIT;

    /* Cache AssetManager objects to speed up ringtone manipulation. */
    private final Map<PackageKey, SoftReference<Resources>> mResourcesTable =
        new HashMap<PackageKey, SoftReference<Resources>>();

    @Override
    public boolean onCreate() {
        /*
         * Detect package removal for the purpose of clearing the resource table
         * cache. Even though it's a soft ref cache it is important to
         * immediately clear on removal so that updated theme packages
         * immediately reflect updated media assets.
         */
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addCategory(Intent.CATEGORY_THEME_PACKAGE_INSTALLED_STATE_CHANGE);
        filter.setPriority(-500);
        filter.addDataScheme("package");
        getContext().registerReceiver(mThemePackageReceiver, filter);

        return true;
    }

    private final BroadcastReceiver mThemePackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            String pkg = intent.getData().getSchemeSpecificPart();
            deleteResourcesForTheme(pkg);
        }
    };

    private static AssetManager createAssetManager(String packageFileName,
            String packageLockedZipFile) {
        AssetManager assets = new AssetManager();
        assets.addAssetPath(packageFileName);
        if (packageLockedZipFile != null) {
            assets.addAssetPath(packageLockedZipFile);
        }
        return assets;
    }

    private synchronized Resources getResourcesForTheme(String packageName,
            int orientation) throws NameNotFoundException {
        final PackageKey key = new PackageKey(packageName, orientation);
        SoftReference<Resources> ref = mResourcesTable.get(key);
        Resources res = ref != null ? ref.get() : null;
        if (res != null) {
            return res;
        }

        PackageInfo pi = getContext().getPackageManager().getPackageInfo(packageName, 0);
        if (pi == null || pi.applicationInfo == null) {
            return null;
        }

        return createResourcesForTheme(key,
                pi.applicationInfo.publicSourceDir, pi.getLockedZipFilePath());
    }

    private synchronized Resources createResourcesForTheme(final PackageKey key,
            String packageFileName, String packageLockedZipFile) {
        AssetManager assets = createAssetManager(packageFileName, packageLockedZipFile);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(metrics);

        Configuration config = new Configuration();
        config.orientation = key.orientation;
        Resources r = new Resources(assets, metrics, config);

        mResourcesTable.put(key, new SoftReference<Resources>(r));
        return r;
    }

    private synchronized void deleteResourcesForTheme(String packageName) {
        // When a theme package is uninstalled, remove all cached
        // resource packages with the theme's package name.
        Iterator<Map.Entry<PackageKey, SoftReference<Resources>>> iter = mResourcesTable.entrySet().iterator();

        while (iter.hasNext()) {
            if (iter.next().getKey().packageName.equals(packageName)) {
                iter.remove();
            }
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        /*
         * When the C++ side (usually through MediaPlayerService) tries to open
         * the file, it uses ActivityManagerService#openContentUri which differs
         * in implementation from ContentResolver#openFileDescriptor(Uri, ...).
         * Specifically, openFile is called directly instead of openAssetFile,
         * with no logic in place to wrap the AssetFileDescriptor returned.
         */
        if (Constants.DEBUG) {
            Log.d(Constants.TAG, "openFile called in place of openAssetFile!");
        }
        AssetFileDescriptor afd = openAssetFile(uri, mode);
        if (afd == null) {
            return null;
        }
        return afd.getParcelFileDescriptor();
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        int type = URI_MATCHER.match(uri);

        List<String> segments = uri.getPathSegments();
        if (segments.size() < 3) {
            throw new IllegalArgumentException("Can't handle URI: " + uri);
        }

        String packageName = segments.get(0);
        Resources packageRes = null;
        try {
            packageRes = getResourcesForTheme(packageName,
                    getOrientation(uri));
        } catch (NameNotFoundException e) {
            throw new FileNotFoundException(e.toString());
        }
        if (packageRes == null) {
            throw new FileNotFoundException("Unable to access package: " + packageName);
        }

        switch (type) {
            case TYPE_RESOURCE_ID:
            case TYPE_RESOURCE_ENTRY:
                int resId;
                if (type == TYPE_RESOURCE_ID) {
                    resId = (int)ContentUris.parseId(uri);
                } else {
                    resId = packageRes.getIdentifier(segments.get(3), segments.get(2),
                            packageName);
                }
                if (resId == 0) {
                    throw new IllegalArgumentException("No resource found for URI: " + uri);
                }
                return packageRes.openRawResourceFd(resId);

            case TYPE_ASSET_PATH:
                String assetPath = uri.getLastPathSegment();
                try {
                    return packageRes.getAssets().openFd(assetPath);
                } catch (FileNotFoundException e) {
                    throw e;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    private int getOrientation(Uri uri) {
        int orientation = DEFAULT_ORIENTATION;
        String o = uri.getQueryParameter(Themes.KEY_ORIENTATION);
        if (!TextUtils.isEmpty(o)) {
            orientation = Integer.valueOf(o);
        }
        return orientation;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    static {
        /* See PackageResources#makeRingtoneUri. */
        URI_MATCHER.addURI(PackageResources.AUTHORITY, "*/res/#", TYPE_RESOURCE_ID);
        URI_MATCHER.addURI(PackageResources.AUTHORITY, "*/res/*/*", TYPE_RESOURCE_ENTRY);
        URI_MATCHER.addURI(PackageResources.AUTHORITY, "*/assets/*", TYPE_ASSET_PATH);
    }

    private class PackageKey {
        public String packageName;
        public int orientation;

        public PackageKey(String packageName, int orientation) {
            this.packageName = packageName;
            this.orientation = orientation;
        }

        @Override public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PackageKey)) {
                return false;
            }
            PackageKey other = (PackageKey)obj;
            return (orientation == other.orientation &&
                    packageName.equals(other.packageName));
        }

        @Override public int hashCode() {
            return 37*packageName.hashCode() + 421*orientation + 7789;
        }
    }
}
