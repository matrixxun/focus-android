/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.webkit;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.support.annotation.WorkerThread;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.mozilla.focus.R;
import org.mozilla.focus.webkit.matcher.UrlMatcher;

public class TrackingProtectionWebViewClient extends WebViewClient {

    private String currentPageURL;

    private static volatile UrlMatcher MATCHER;

    public static void triggerPreload(final Context context) {
        // Only trigger loading if MATCHER is null. (If it's null, MATCHER could already be loading,
        // but we don't have any way of being certain - and there's no real harm since we're not
        // blocking anything else.)
        if (MATCHER == null) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... voids) {
                    // We don't need the result here - we just want to trigger loading
                    getMatcher(context);
                    return null;
                }
            }.execute();
        }
    }

    @WorkerThread private static synchronized UrlMatcher getMatcher(final Context context) {
        if (MATCHER == null) {
            MATCHER = new UrlMatcher(context, R.raw.blocklist, R.raw.entitylist);
        }
        return MATCHER;
    }

    public TrackingProtectionWebViewClient(final Context context) {
        // Hopefully we have loaded background data already. We call triggerPreload() to try to trigger
        // background loading of the lists as early as possible.
        triggerPreload(context);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        // shouldInterceptRequest() might be called _before_ onPageStarted or shouldOverrideUrlLoading
        // are called (this happens when the webview is first shown). However we are notified of the URL
        // via notifyCurrentURL in that case.
        final String scheme = request.getUrl().getScheme();

        if (!request.isForMainFrame() &&
                !scheme.equals("http") && !scheme.equals("https")) {
            // Block any malformed non-http(s) URIs. Webkit will already ignore things like market: URLs,
            // but not in all cases (malformed market: URIs, such as market:://... will still end up here).
            // (Note: data: URIs are automatically handled by webkit, and won't end up here either.)
            // file:// URIs are disabled separately by setting WebSettings.setAllowFileAccess()
            return new WebResourceResponse(null, null, null);
        }

        final UrlMatcher matcher = getMatcher(view.getContext());

        // Don't block the main frame from being loaded. This also protects against cases where we
        // open a link that redirects to another app (e.g. to the play store).
        if ((!request.isForMainFrame()) &&
                matcher.matches(request.getUrl().toString(), currentPageURL)) {
            return new WebResourceResponse(null, null, null);
        }

        return super.shouldInterceptRequest(view, request);
    }

    /**
     * Notify that the user has requested a new URL. This MUST be called before loading a new URL
     * into the webview: sometimes content requests might begin before the WebView itself notifies
     * the WebViewClient via onpageStarted/shouldOverrideUrlLoading. If we don't know the current page
     * URL then the entitylist whitelists might not work if we're trying to load an explicitly whitelisted
     * page.
     */
    public void notifyCurrentURL(final String url) {
        currentPageURL = url;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        currentPageURL = url;

        super.onPageStarted(view, url, favicon);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        currentPageURL = url;

        return super.shouldOverrideUrlLoading(view, url);
    }

}