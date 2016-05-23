package com.osmiumusa.androidparallelplatform;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Scanner;

public class MainActivity extends AppCompatActivity {

    Node node;
    int frames = 0, peers = 0, rank = -1, nextrank = 0, rankrequestid = 0;
    TextView stats;
    WebView webview;
    Switch masterswitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button choosebutton = (Button) findViewById(R.id.choosefile);
        Button startbutton = (Button) findViewById(R.id.startbutton);
        stats = (TextView) findViewById(R.id.textView3);
        masterswitch = (Switch) findViewById(R.id.switch1);
        webview = (WebView) findViewById(R.id.webView);
        WebSettings settings = webview.getSettings();
        settings.setJavaScriptEnabled(true);
        webview.addJavascriptInterface(new WebAppInterface(this), "CONTROL");
        updatestats();
        node = new Node(this);
        node.start();

        choosebutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("file/*");
                startActivityForResult(intent, 0);
                //byte[] y = {(byte) -1, (byte) nextrank};
                //node.broadcastFrame(y);
            }
        });

        startbutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                byte[] x = {(byte) 2};
                node.broadcastFrame(x);
                webview.loadUrl("javascript:start(" + node.getLinks().size() + "," + rank + ");");
            }
        });

        masterswitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    byte[] x = {(byte) 3};
                    node.broadcastFrame(x);
                    rank = 0;
                    nextrank = 1;
                }
            }
        });

        verifyStoragePermissions(this);
    }

    public void refreshPeers() {
        peers = node.getLinks().size();
        updatestats();
    }

    public void refreshFrames() {
        frames = node.getFramesCount();
        updatestats();
    }

    public void updatestats() {
        stats.setText("Peers Connected: " + peers + "\nFrame Count: " + frames + "\nMy Rank: " + rank);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        String content = "";
        try {
            content = new Scanner(new File(data.getDataString().substring(6))).useDelimiter("\\Z").next();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        byte[] contentbytes = content.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(contentbytes.length + 1);
        buffer.put((byte) 0); //This is code being sent to other nodes
        buffer.put(contentbytes);
        webview.loadData(content + "<script>" + content + "</script>", "text/html", "UTF-8");
        node.broadcastFrame(buffer.array()); //Send to everyone listening
        super.onActivityResult(requestCode, resultCode, data);
    }


    public void dataReceived(byte[] frameData) {
        switch (frameData[0]) {
            case 0: //Code Transfer
                byte[] codebuffer = new byte[frameData.length - 1];
                System.arraycopy(frameData, 1, codebuffer, 0, frameData.length - 1);
                String code = new String(codebuffer);
                Log.d("DA CODE LENGTH", frameData.length + "");
                Log.d("DA CODE", code);
                webview.loadData("<html><body>" + code + "<script>" + code + "</script></body></html>", "text/html", "UTF-8");
                break;
            case 1: //Generic Send
                byte[] msgbuffer = new byte[frameData.length - 5]; //command, source, dest, label, isbroadcast, data...
                if (frameData[2] != 0 && masterswitch.isChecked()) {
                    node.broadcastFrame(frameData);
                }
                if ((frameData[2] == -1 || frameData[2] == rank) && frameData[1] != rank) {
                    System.arraycopy(frameData, 5, msgbuffer, 0, frameData.length - 5);
                    String message = new String(msgbuffer);
                    webview.loadUrl("javascript:recv(\"" + message + "\"," + frameData[1] + "," + frameData[3] + "," + frameData[4] + ");");
                }
                break;
            case 2: //Start execution
                Log.d("JAVASCRIPT", "javascript:start(" + node.getLinks().size() + "," + rank + ");");
                webview.loadUrl("javascript:start(" + node.getLinks().size() + "," + rank + ");");
                break;
            case 3: //Someone else is the master, ask for a rank
                masterswitch.setChecked(false);
                byte[] x = new byte[2];
                new Random().nextBytes(x);
                x[0] = 4; //Command
                rankrequestid = x[1]; //Rank Request ID (so we don't get duplicates
                node.broadcastFrame(x);
                break;
            case 4: //Request for a rank id
                if (masterswitch.isChecked()) {
                    byte[] y = {(byte) 5, (byte) nextrank, frameData[1]};
                    node.broadcastFrame(y);
                    nextrank++;
                }
                break;
            case 5: //Response to a request for a rank from the master
                if (frameData[2] == rankrequestid) rank = frameData[1];
                break;
            case -1: //Debug
                Toast.makeText(this, "Received from button!", Toast.LENGTH_SHORT).show();
            default:
                break;
        }
    }

    class WebAppInterface {
        Context mContext;
        Bitmap bitmap;
        FileOutputStream fos;

        /**
         * Instantiate the interface and set the context
         */
        WebAppInterface(Context c) {
            mContext = c;
        }

        /**
         * Show a toast from the web page
         */
        @JavascriptInterface
        public void send(String message, int dest, int label) {
            byte[] msgbuf = message.getBytes();
            ByteBuffer buffer = ByteBuffer.allocate(msgbuf.length + 5);
            buffer.put((byte) 1); //This is a generic send
            buffer.put((byte) rank); //Source
            buffer.put((byte) dest); //Destination
            buffer.put((byte) label); //Label
            if (dest == -1) buffer.put((byte) 1); //Was a broadcast
            else buffer.put((byte) 0); //Was not a broadcast
            buffer.put(msgbuf); //The message
            node.broadcastFrame(buffer.array()); //Send to everyone listening
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d("PARALLEL PROGRAM", message);
        }

        @JavascriptInterface
        public void toast(String toast) {
            Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show();
        }

        @JavascriptInterface
        public void canvas_create(int width, int height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        }

        @JavascriptInterface
        public void canvas_draw_point(int x, int y, int r, int g, int b) {
            bitmap.setPixel(x,y,Color.argb(255,r,g,b));
        }

        @JavascriptInterface
        public void canvas_dump(String filename) {
            try {
                fos = new FileOutputStream(filename);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fos.close();
                fos = null;
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                        fos = null;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    // Storage Permissions
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    /**
     * Checks if the app has permission to write to device storage
     * <p/>
     * If the app does not has permission then the user will be prompted to grant permissions
     *
     * @param activity
     */
    public static void verifyStoragePermissions(Activity activity) {
        // Check if we have write permission
        int permission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    activity,
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }
    }
}
