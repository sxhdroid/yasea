package net.ossrs.yasea.demo;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import com.github.faucamp.simplertmp.RtmpHandler;
import com.seu.magicfilter.base.gpuimage.GPUWaterMarkFilter;
import com.seu.magicfilter.utils.MagicFilterType;

import net.ossrs.yasea.SrsCameraView;
import net.ossrs.yasea.SrsEncodeHandler;
import net.ossrs.yasea.SrsPublisher;
import net.ossrs.yasea.SrsRecordHandler;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements RtmpHandler.RtmpListener,
                        SrsRecordHandler.SrsRecordListener, SrsEncodeHandler.SrsEncodeListener {

    private static final String TAG = "Yasea";

    private Button btnPublish;
    private Button btnSwitchCamera;
    private Button btnRecord;
    private Button btnSwitchEncoder;
    private Button btnPause;

    private SharedPreferences sp;
    private String rtmpUrl = "rtmp://192.168.0.254:1935/b2a0844ecddf339c/73";
//        private String rtmpUrl = "rtmp://ossrs.net/" + getRandomAlphaString(3) + '/' + getRandomAlphaDigitString(5);
    private String recPath = Environment.getExternalStorageDirectory().getPath() + "/test.mp4";

    private SrsPublisher mPublisher;
    private SrsCameraView mCameraView;

    private Camera2Manager camera2Manager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // response screen rotation event
//        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);

        // restore data.
//        sp = getSharedPreferences("Yasea", MODE_PRIVATE);
//        rtmpUrl = sp.getString("rtmpUrl", rtmpUrl);

        // initialize url.
//        final EditText efu = (EditText) findViewById(R.id.url);
//        efu.setText(rtmpUrl);

        btnPublish = (Button) findViewById(R.id.publish);
        btnSwitchCamera = (Button) findViewById(R.id.swCam);
        btnRecord = (Button) findViewById(R.id.record);
        btnSwitchEncoder = (Button) findViewById(R.id.swEnc);
        btnPause = (Button) findViewById(R.id.pause);
        btnPause.setEnabled(false);
        mCameraView = findViewById(R.id.glsurfaceview_camera);
        mCameraView.setSurfaceCreatedCallback(new SrsCameraView.SurfaceCreatedCallback() {
            @Override
            public void onSurfaceCreated() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        camera2Manager = new Camera2Manager(MainActivity.this);
                        camera2Manager.setPreviewView(mCameraView);
                        camera2Manager.openCamera(1920, 1080);
                        GPUWaterMarkFilter filter = new GPUWaterMarkFilter();
                        filter.setPosition(400, 400, 0, 0);
                        filter.setBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher));
                        mCameraView.setFilter(filter);
                    }
                });
            }
        });

        mPublisher = new SrsPublisher(mCameraView);
        mPublisher.setEncodeHandler(new SrsEncodeHandler(this));
        mPublisher.setRtmpHandler(new RtmpHandler(this));
        mPublisher.setRecordHandler(new SrsRecordHandler(this));
        mPublisher.setOutputResolution(1920, 1080);
        mPublisher.setBitrate(12000 * 1000);
//        mPublisher.setVideoHDMode();
//        mPublisher.startCamera();

        btnPublish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (btnPublish.getText().toString().contentEquals("publish")) {
//                    rtmpUrl = efu.getText().toString();
//                    SharedPreferences.Editor editor = sp.edit();
//                    editor.putString("rtmpUrl", rtmpUrl);
//                    editor.apply();

                    mPublisher.startPublish(rtmpUrl);
//                    mPublisher.startCamera();

                    if (btnSwitchEncoder.getText().toString().contentEquals("soft encoder")) {
                        Toast.makeText(getApplicationContext(), "Use hard encoder", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getApplicationContext(), "Use soft encoder", Toast.LENGTH_SHORT).show();
                    }
                    btnPublish.setText("stop");
                    btnSwitchEncoder.setEnabled(false);
                    btnPause.setEnabled(true);
                } else if (btnPublish.getText().toString().contentEquals("stop")) {
                    mPublisher.stopPublish();
                    mPublisher.stopRecord();
                    btnPublish.setText("publish");
                    btnRecord.setText("record");
                    btnSwitchEncoder.setEnabled(true);
                    btnPause.setEnabled(false);
                }
            }
        });
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(btnPause.getText().toString().equals("Pause")){
                    mPublisher.pausePublish();
                    btnPause.setText("resume");
                }else{
                    mPublisher.resumePublish();
                    btnPause.setText("Pause");
                }
            }
        });

        btnSwitchCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                mPublisher.switchCameraFace((mPublisher.getCameraId() + 1) % Camera.getNumberOfCameras());
            }
        });

        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (btnRecord.getText().toString().contentEquals("record")) {
                    mPublisher.startEncode();
                    if (mPublisher.startRecord(recPath)) {
                        btnRecord.setText("pause");
                    }
                } else if (btnRecord.getText().toString().contentEquals("pause")) {
                    mPublisher.stopEncode();
                    mPublisher.stopRecord();
                    btnRecord.setText("resume");
                } else if (btnRecord.getText().toString().contentEquals("resume")) {
                    mPublisher.resumeRecord();
                    btnRecord.setText("pause");
                }
            }
        });

        btnSwitchEncoder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (btnSwitchEncoder.getText().toString().contentEquals("soft encoder")) {
                    mPublisher.switchToSoftEncoder();
                    btnSwitchEncoder.setText("hard encoder");
                } else if (btnSwitchEncoder.getText().toString().contentEquals("hard encoder")) {
                    mPublisher.switchToHardEncoder();
                    btnSwitchEncoder.setText("soft encoder");
                }
            }
        });

        findViewById(R.id.capture).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPublisher.requestCaptureFrame(new SrsCameraView.CaptureFrameCallback() {
                    @Override
                    public void onCaptureFrame(byte[] data, int width, int height) {
                        File path = Environment
                                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                        File file = new File(path,  "测试_" + System.currentTimeMillis() + ".jpg");
                        BitmapUtils.saveBitmap(file.getAbsolutePath(), ByteBuffer.wrap(data), width, height);
                    }
                });
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        } else {
            switch (id) {
                case R.id.cool_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.COOL);
                    break;
                case R.id.beauty_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.BEAUTY);
                    break;
                case R.id.early_bird_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.EARLYBIRD);
                    break;
                case R.id.evergreen_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.EVERGREEN);
                    break;
                case R.id.n1977_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.N1977);
                    break;
                case R.id.nostalgia_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.NOSTALGIA);
                    break;
                case R.id.romance_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.ROMANCE);
                    break;
                case R.id.sunrise_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.SUNRISE);
                    break;
                case R.id.sunset_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.SUNSET);
                    break;
                case R.id.tender_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.TENDER);
                    break;
                case R.id.toast_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.TOASTER2);
                    break;
                case R.id.valencia_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.VALENCIA);
                    break;
                case R.id.walden_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.WALDEN);
                    break;
                case R.id.warm_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.WARM);
                    break;
                case R.id.original_filter:
                default:
                    mPublisher.switchCameraFilter(MagicFilterType.NONE);
                    break;
            }
        }
        setTitle(item.getTitle());

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
//        if(mPublisher.getCamera() == null){
//            //if the camera was busy and available again
//            mPublisher.startCamera();
//        }
    }                           
                          
    @Override
    protected void onResume() {
        super.onResume();
        final Button btn = (Button) findViewById(R.id.publish);
        btn.setEnabled(true);
        mPublisher.resumeRecord();
        mCameraView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPublisher.pauseRecord();
        mCameraView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPublisher.stopPublish();
        mPublisher.stopRecord();
        camera2Manager.closeCamera();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mPublisher.stopEncode();
        mPublisher.stopRecord();
        btnRecord.setText("record");
        mPublisher.setScreenOrientation(newConfig.orientation);
        if (btnPublish.getText().toString().contentEquals("stop")) {
            mPublisher.startEncode();
        }
//        mPublisher.startCamera();
    }

    private static String getRandomAlphaString(int length) {
        String base = "abcdefghijklmnopqrstuvwxyz";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }

    private static String getRandomAlphaDigitString(int length) {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }

    private void handleException(Exception e) {
        try {
            Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
            mPublisher.stopPublish();
            mPublisher.stopRecord();
            btnPublish.setText("publish");
            btnRecord.setText("record");
            btnSwitchEncoder.setEnabled(true);
        } catch (Exception e1) {
            //
        }
    }

    // Implementation of SrsRtmpListener.

    @Override
    public void onRtmpConnecting(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpConnected(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpVideoStreaming() {
    }

    @Override
    public void onRtmpAudioStreaming() {
    }

    @Override
    public void onRtmpStopped() {
        Toast.makeText(getApplicationContext(), "Stopped", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpDisconnected() {
        Toast.makeText(getApplicationContext(), "Disconnected", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpVideoFpsChanged(double fps) {
//        Log.i(TAG, String.format("Output Fps: %f", fps));
    }

    @Override
    public void onRtmpVideoBitrateChanged(double bitrate) {
//        int rate = (int) bitrate;
//        if (rate / 1000 > 0) {
//            Log.i(TAG, String.format("Video bitrate: %f kbps", bitrate / 1000));
//        } else {
//            Log.i(TAG, String.format("Video bitrate: %d bps", rate));
//        }
    }

    @Override
    public void onRtmpAudioBitrateChanged(double bitrate) {
//        int rate = (int) bitrate;
//        if (rate / 1000 > 0) {
//            Log.i(TAG, String.format("Audio bitrate: %f kbps", bitrate / 1000));
//        } else {
//            Log.i(TAG, String.format("Audio bitrate: %d bps", rate));
//        }
    }

    @Override
    public void onRtmpSocketException(SocketException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIOException(IOException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalStateException(IllegalStateException e) {
        handleException(e);
    }

    // Implementation of SrsRecordHandler.

    @Override
    public void onRecordPause() {
        Toast.makeText(getApplicationContext(), "Record paused", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordResume() {
        Toast.makeText(getApplicationContext(), "Record resumed", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordStarted(String msg) {
        Toast.makeText(getApplicationContext(), "Recording file: " + msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordFinished(String msg) {
        Toast.makeText(getApplicationContext(), "MP4 file saved: " + msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordIOException(IOException e) {
        handleException(e);
    }

    @Override
    public void onRecordIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    // Implementation of SrsEncodeHandler.

    @Override
    public void onNetworkWeak() {
//        Toast.makeText(getApplicationContext(), "Network weak", Toast.LENGTH_SHORT).show();
        Log.e(TAG, "Network weak");
    }

    @Override
    public void onNetworkResume() {
        Toast.makeText(getApplicationContext(), "Network resume", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onEncodeIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }
}
