package in.ledgerflow.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String BUNDLED_HOST = "appassets.androidplatform.net";
    private static final String BUNDLED_ENTRY_URL = "https://" + BUNDLED_HOST + "/index.html";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private WebViewAssetLoader assetLoader;
    private String allowedAppHost = BUNDLED_HOST;
    private String entryUrl = BUNDLED_ENTRY_URL;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(BUNDLED_HOST)
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        String configuredUrl = BuildConfig.APP_URL != null ? BuildConfig.APP_URL.trim() : "";
        if (!configuredUrl.isEmpty()
                && (configuredUrl.startsWith("https://") || configuredUrl.startsWith("http://"))) {
            entryUrl = configuredUrl;
            Uri baseUri = Uri.parse(configuredUrl);
            if (baseUri.getHost() != null) {
                allowedAppHost = baseUri.getHost().toLowerCase(Locale.US);
            }
        } else {
            entryUrl = BUNDLED_ENTRY_URL;
            allowedAppHost = BUNDLED_HOST;
        }

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) {
                        return;
                    }
                    Uri[] results = WebChromeClient.FileChooserParams.parseResult(
                            result.getResultCode(),
                            result.getData()
                    );
                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                }
        );

        swipeRefresh = new SwipeRefreshLayout(this);
        swipeRefresh.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        swipeRefresh.setColorSchemeColors(Color.parseColor("#1D358F"), Color.parseColor("#0B1220"));

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.setBackgroundColor(Color.parseColor("#0B1220"));

        swipeRefresh.addView(webView);
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> webView.getScrollY() > 0);
        swipeRefresh.setOnRefreshListener(() -> webView.reload());
        setContentView(swipeRefresh);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new BlobDownloadBridge(), "LedgerFlowAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && BUNDLED_HOST.equalsIgnoreCase(uri.getHost())) {
                    WebResourceResponse response = assetLoader.shouldInterceptRequest(uri);
                    if (response != null) {
                        return response;
                    }
                    // SPA route fallback to index.html for extension-less paths
                    String path = uri.getPath();
                    if (path == null || path.equals("/") || !path.contains(".")) {
                        return assetLoader.shouldInterceptRequest(Uri.parse(BUNDLED_ENTRY_URL));
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) {
                    return false;
                }
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
                if ("mailto".equals(scheme) || "tel".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                        // Ignore if no handler is installed
                    }
                    return true;
                }
                if (!"https".equals(scheme) && !"http".equals(scheme)) {
                    return true;
                }
                String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.US) : "";
                if (host.equals(BUNDLED_HOST)
                        || host.equals(allowedAppHost)
                        || host.endsWith(".run.app")
                        || host.endsWith(".firebaseapp.com")
                        || host.endsWith(".web.app")) {
                    return false;
                }
                // Open external links (e.g., Google Drive web links) in the system browser
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    swipeRefresh.setRefreshing(false);
                    String failingUrl = request.getUrl() != null ? request.getUrl().toString() : "";
                    if (!failingUrl.startsWith("https://" + BUNDLED_HOST)) {
                        // Automatically fall back to bundled offline-ready LedgerFlow app
                        view.loadUrl(BUNDLED_ENTRY_URL);
                    }
                }
            }

            @Override
            public void onReceivedHttpError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceResponse errorResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame() && errorResponse != null) {
                    int status = errorResponse.getStatusCode();
                    String failingUrl = request.getUrl() != null ? request.getUrl().toString() : "";
                    if (status >= 500 && !failingUrl.startsWith("https://" + BUNDLED_HOST)) {
                        swipeRefresh.setRefreshing(false);
                        // Automatically fall back to bundled offline-ready LedgerFlow app
                        view.loadUrl(BUNDLED_ENTRY_URL);
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
                // Track anchor download filenames for blob: exports (PDF/Excel/JSON)
                String hookScript =
                        "(function(){"
                                + "if(window.__lfBlobHooked)return;"
                                + "window.__lfBlobHooked=true;"
                                + "window.__lfBlobNames={};"
                                + "var origClick=HTMLAnchorElement.prototype.click;"
                                + "HTMLAnchorElement.prototype.click=function(){"
                                + "try{if(this.href&&this.href.indexOf('blob:')===0&&this.download){"
                                + "window.__lfBlobNames[this.href]=this.download;}}catch(e){}"
                                + "return origClick.apply(this,arguments);"
                                + "};"
                                + "})();";
                view.evaluateJavascript(hookScript, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams fileChooserParams
            ) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    fileChooserLauncher.launch(intent);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Unable to open file picker.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null) {
                return;
            }
            if (url.startsWith("blob:")) {
                String fallbackName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                String js =
                        "(function(blobUrl, fallbackName, fallbackMime){"
                                + "var name=(window.__lfBlobNames&&window.__lfBlobNames[blobUrl])||fallbackName;"
                                + "fetch(blobUrl).then(function(r){return r.blob();}).then(function(b){"
                                + "var reader=new FileReader();"
                                + "reader.onloadend=function(){"
                                + "if(window.LedgerFlowAndroid&&typeof reader.result==='string'){"
                                + "window.LedgerFlowAndroid.saveBase64File(reader.result, name, b.type||fallbackMime||'application/octet-stream');"
                                + "}"
                                + "};"
                                + "reader.readAsDataURL(b);"
                                + "}).catch(function(){});"
                                + "})("
                                + JSONObject.quote(url) + ","
                                + JSONObject.quote(fallbackName) + ","
                                + JSONObject.quote(mimeType != null ? mimeType : "application/octet-stream")
                                + ");";
                webView.evaluateJavascript(js, null);
                return;
            }

            try {
                String fileName = sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType));
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) {
                    request.addRequestHeader("Cookie", cookies);
                }
                request.addRequestHeader("User-Agent", userAgent);
                request.setTitle(fileName);
                request.setDescription("Downloading LedgerFlow file...");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Download failed.", Toast.LENGTH_SHORT).show();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(entryUrl);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    private String sanitizeFileName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").replace("..", "_");
        if (name.isEmpty() || "downloadfile.bin".equalsIgnoreCase(name)) {
            name = "LedgerFlow-Export-" + System.currentTimeMillis() + ".pdf";
        }
        return name;
    }

    private class BlobDownloadBridge {
        @JavascriptInterface
        public void saveBase64File(String dataUrl, String suggestedName, String mimeType) {
            if (dataUrl == null || dataUrl.isEmpty()) {
                return;
            }
            String safeName = sanitizeFileName(suggestedName);
            String safeMime = (mimeType != null && !mimeType.isEmpty())
                    ? mimeType
                    : "application/octet-stream";

            try {
                int commaIdx = dataUrl.indexOf(',');
                String base64Payload = commaIdx >= 0 ? dataUrl.substring(commaIdx + 1) : dataUrl;
                byte[] bytes = Base64.decode(base64Payload, Base64.DEFAULT);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Downloads.MIME_TYPE, safeMime);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        throw new IllegalStateException("Could not create download entry.");
                    }
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) {
                            throw new IllegalStateException("Could not open output stream.");
                        }
                        out.write(bytes);
                        out.flush();
                    }
                } else {
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        downloadsDir.mkdirs();
                    }
                    File outFile = new File(downloadsDir, safeName);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                        fos.flush();
                    }
                }

                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "Saved to Downloads: " + safeName,
                        Toast.LENGTH_LONG
                ).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "Unable to save file: " + safeName,
                        Toast.LENGTH_LONG
                ).show());
            }
        }
    }
}
