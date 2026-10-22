package de.westhain.mapeditor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** A local-first WebView host for the Stardew Map Studio editor. */
public final class MainActivity extends Activity {
    private static final int PICK_FILES = 411;
    private static final int SAVE_DOCUMENT = 412;
    private static final String BASE = "https://appassets.androidplatform.net/assets/";
    private ValueCallback<Uri[]> chooser;
    private WebView webView;
    private String pendingName;
    private String pendingMime;
    private File stagedFile;
    private FileOutputStream stagedStream;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF172F29);
        getWindow().setNavigationBarColor(0xFF172F29);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF4F4ED);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBuiltInZoomControls(false);

        // No INTERNET permission. App assets are exposed under a synthetic HTTPS origin.
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                if ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception ex) { Toast.makeText(MainActivity.this, "Browser nicht verfügbar", Toast.LENGTH_SHORT).show(); }
                }
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"appassets.androidplatform.net".equals(uri.getHost()) || !uri.getPath().startsWith("/assets/"))
                    return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0]));
                String path = uri.getPath().substring("/assets/".length());
                if (path.contains("..")) return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                String mime = path.endsWith(".js") ? "text/javascript" :
                              path.endsWith(".css") ? "text/css" :
                              path.endsWith(".png") ? "image/png" :
                              path.endsWith(".zip") ? "application/zip" : "text/html";
                try {
                    InputStream input = getAssets().open(path);
                    return new WebResourceResponse(mime, "UTF-8", input);
                } catch (Exception ex) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Missing", null, new ByteArrayInputStream(new byte[0]));
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView web, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, PICK_FILES);
                    return true;
                } catch (Exception ex) {
                    chooser = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }
        });
        webView.addJavascriptInterface(new ExportBridge(), "AndroidBridge");
        webView.loadUrl(BASE + "index.html");
    }

    public final class ExportBridge {
        @JavascriptInterface public synchronized void startSaveFile(String filename, String mime) {
            try {
                if (stagedStream != null) stagedStream.close();
                if (stagedFile != null) stagedFile.delete();
                stagedFile = new File(getCacheDir(), "stardew-export.tmp");
                stagedStream = new FileOutputStream(stagedFile);
                pendingName = filename;
                pendingMime = mime;
            } catch (Exception ex) {
                throw new IllegalStateException("Exportvorbereitung: " + ex.getMessage(), ex);
            }
        }

        @JavascriptInterface public synchronized void appendSaveChunk(String base64) {
            try {
                if (stagedStream == null) throw new IllegalStateException("Export nicht gestartet");
                stagedStream.write(Base64.decode(base64, Base64.DEFAULT));
            } catch (Exception ex) {
                throw new IllegalStateException("Exportdaten: " + ex.getMessage(), ex);
            }
        }

        @JavascriptInterface public synchronized void finishSaveFile() {
            try {
                if (stagedStream == null) throw new IllegalStateException("Export nicht gestartet");
                stagedStream.close();
                stagedStream = null;
                final String fileName = pendingName;
                final String mime = pendingMime;
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(mime == null ? "application/octet-stream" : mime);
                    intent.putExtra(Intent.EXTRA_TITLE, fileName);
                    startActivityForResult(intent, SAVE_DOCUMENT);
                });
            } catch (Exception ex) {
                throw new IllegalStateException("Exportabschluss: " + ex.getMessage(), ex);
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILES) {
            if (chooser != null) {
                Uri[] uris = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        uris = new Uri[count];
                        for (int i = 0; i < count; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        uris = new Uri[]{ data.getData() };
                    }
                }
                chooser.onReceiveValue(uris);
                chooser = null;
            }
        } else if (requestCode == SAVE_DOCUMENT) {
            try {
                if (resultCode == RESULT_OK && data != null && data.getData() != null && stagedFile != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                        if (out == null) throw new IllegalStateException("Zieldatei nicht beschreibbar");
                        try (InputStream in = new FileInputStream(stagedFile)) {
                            byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                        }
                    }
                    Toast.makeText(this, "Gespeichert: " + pendingName, Toast.LENGTH_LONG).show();
                }
            } catch (Exception ex) {
                Toast.makeText(this, "Speicherfehler: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                pendingName = null;
                pendingMime = null;
                if (stagedFile != null) stagedFile.delete();
                stagedFile = null;
            }
        }
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (chooser != null) { chooser.onReceiveValue(null); chooser = null; }
        if (webView != null) webView.destroy();
        if (stagedFile != null) stagedFile.delete();
        super.onDestroy();
    }
}
