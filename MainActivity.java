package com.toonmaker.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int FILE_REQ = 1;
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private ValueCallback<Uri[]> filePathCallback;
    private MediaRecorder rec;
    private File recFile;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tts = new TextToSpeech(this, this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choisir des fichiers"), FILE_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FILE_REQ && filePathCallback != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    r = new Uri[n];
                    for (int k = 0; k < n; k++) r[k] = data.getClipData().getItemAt(k).getUri();
                } else if (data.getData() != null) {
                    r = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(r);
            filePathCallback = null;
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.FRENCH);
            ttsReady = true;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onError(String id) { done(); }
                @Override public void onDone(String id) { done(); }
                private void done() {
                    runOnUiThread(() -> web.evaluateJavascript("window.ttsDone&&window.ttsDone()", null));
                }
            });
        }
    }

    private void releaseRec() {
        if (rec != null) {
            try { rec.release(); } catch (Exception e) { }
            rec = null;
        }
    }

    public class Bridge {
        @JavascriptInterface
        public boolean speak(String text, float pitch, float rate, String lang) {
            if (!ttsReady) return false;
            try {
                tts.setLanguage((lang == null || lang.isEmpty()) ? Locale.FRENCH : Locale.forLanguageTag(lang));
            } catch (Exception e) { }
            tts.setPitch(pitch);
            tts.setSpeechRate(rate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "toon");
            return true;
        }

        @JavascriptInterface
        public void stop() { if (ttsReady) tts.stop(); }

        @JavascriptInterface
        public String startRecord() {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2));
                return "perm";
            }
            try {
                if (ttsReady) tts.stop();
                releaseRec();
                recFile = File.createTempFile("rec", ".m4a", getCacheDir());
                rec = new MediaRecorder();
                rec.setAudioSource(MediaRecorder.AudioSource.MIC);
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                rec.setAudioChannels(1);
                rec.setAudioSamplingRate(16000);
                rec.setAudioEncodingBitRate(32000);
                rec.setOutputFile(recFile.getAbsolutePath());
                rec.setMaxDuration(60000);
                rec.prepare();
                rec.start();
                return "ok";
            } catch (Exception e) {
                releaseRec();
                return "err";
            }
        }

        @JavascriptInterface
        public boolean isRecording() { return rec != null; }

        @JavascriptInterface
        public String stopRecord() {
            if (rec == null) return "";
            try { rec.stop(); } catch (Exception e) { }
            releaseRec();
            try {
                FileInputStream in = new FileInputStream(recFile);
                byte[] buf = new byte[(int) recFile.length()];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                in.close();
                recFile.delete();
                return "data:audio/mp4;base64," + Base64.encodeToString(buf, 0, off, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void landscape(boolean on) {
            runOnUiThread(() -> setRequestedOrientation(on
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        }

        @JavascriptInterface
        public String saveFile(String name, String text) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) return "";
                    OutputStream o = getContentResolver().openOutputStream(u);
                    o.write(text.getBytes("UTF-8"));
                    o.close();
                    return "Téléchargements";
                }
            } catch (Exception e) { }
            return "";
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Partager"));
                } catch (Exception e) { }
            });
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onBack?window.onBack():false)", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        releaseRec();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
